package com.glasslauncher

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Trace
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import java.util.Calendar

/**
 * Native prototype of the Glass home screen: one custom View that draws the whole screen (wallpaper, clock, two glass
 * docks of cards, header buttons, Control Centre panel, toast). Geometry is the WebView layout at 1920x1080 in device
 * pixels (docs/nativeui/PARITY_CHECKLIST.md); other sizes scale uniformly. Nothing here touches PackageManager, disk or
 * JNI: artwork, installed apps and Control Centre state arrive from NativeHomeActivity's worker thread.
 */
class NativeHomeView(ctx: Context, @JvmField val cfg: NativeHomeConfig, @JvmField val host: Host) : View(ctx) {
    interface Host {
        fun launch(card: NativeHomeConfig.Card)
        fun openSettings(kind: String)
        fun controlCentreAction(id: String, delta: Int)
        fun playNav()
        fun playClick()
        fun openSearch()
        fun launchRecent()
        fun exitPrototype()
    }

    fun interface Setter { fun set(v: Float) }

    companion object {
        // ---- geometry (px at 1920x1080) ----
        const val W = 1920f
        const val H = 1080f
        const val TILE_W = 280f
        const val TILE_H = 168f
        const val TILE_R = 22f
        const val GAP = 32f
        const val PAD_X = 72f
        const val PAD_Y = 32f
        const val DOCK_W = 1674f
        const val DOCK_H = 234f
        const val DOCK_R = 48f
        const val ROW_GAP = 48f
        const val ROW0_TOP = 461f
        const val ROWS_SHIFT = 303f
        const val HEAD_LIFT = 85f
        const val ROWS_HALO = 60f
        const val FADE_TOP_START = 134f
        const val FADE_TOP_END = 158f
        const val FADE_BOT_START = 992f
        const val FADE_BOT_END = 1072f
        const val HEAD_SCALE = 0.6f
        const val BTN_R = 34f
        const val BTN_Y = 94f
        const val BTN_SEARCH_X = 1746f
        const val BTN_CC_X = 1814f
        const val REC_X = 72f
        const val REC_Y = 60f
        const val REC_W = 328f
        const val REC_H_EMPTY = 101f
        const val REC_H_HAS = 142f
        const val HDR_BASE = 1000
        const val RTHUMB_W = 122f
        const val RTHUMB_H = 73f

        @JvmField val EASE = PathInterpolator(.2f, .8f, .2f, 1f)   // --ease
        @JvmField val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        @JvmField val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

        @JvmStatic fun isNight(c: Calendar): Boolean { val h = c.get(Calendar.HOUR_OF_DAY); return h >= 19 || h < 7 }   // theme.js

        @JvmStatic fun blend(a: Int, b: Int, t: Float): Int =
            Color.argb((Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

        /** White silhouette of a black-on-transparent icon (UI_ICONS invert:true), for the moonsun PNGs. */
        @JvmStatic fun tint(color: Int): PorterDuffColorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    // ---- state ----
    @JvmField var dark = false
    @JvmField var zone = 0            // 0 rows, 1 header
    @JvmField var vpos = 0
    @JvmField var hdrIdx = 0
    @JvmField val col = IntArray(cfg.rows.size)
    @JvmField var wallLight: Bitmap? = null
    @JvmField var wallDark: Bitmap? = null
    @JvmField var blurLight: Bitmap? = null
    @JvmField var blurDark: Bitmap? = null
    @JvmField val artLight: Array<Array<Bitmap?>> = Array(cfg.rows.size) { arrayOfNulls<Bitmap>(cfg.rows[it].cards.size) }
    @JvmField val artDark: Array<Array<Bitmap?>> = Array(cfg.rows.size) { arrayOfNulls<Bitmap>(cfg.rows[it].cards.size) }
    /** Theme-independent card images (overrides / installed-app icons), already composited at tile size. */
    @JvmField val cardIcon: Array<Array<Bitmap?>> = Array(cfg.rows.size) { arrayOfNulls<Bitmap>(cfg.rows[it].cards.size) }
    private val labels: Array<Array<String>> = Array(cfg.rows.size) { r -> Array(cfg.rows[r].cards.size) { HomeArt.label(cfg.rows[r].cards[it]) } }
    @JvmField var dockShadow: Bitmap? = null
    @JvmField var tileShadow: Bitmap? = null
    @JvmField var clock = ""
    @JvmField var date = ""
    @JvmField var ccTime = ""
    private var toast: String? = null
    private var toastUntil = 0L

    // ---- Recently Used (set by the activity; strings/lines are precomputed so onDraw never allocates) ----
    private var recentOn = false
    private var recentLines: List<String> = emptyList()
    private var recentInitials = "?"
    private var recentLight: Bitmap? = null
    private var recentDark: Bitmap? = null
    private var pointerX = -1e4f
    private var pointerY = -1e4f
    private var wheelAt = 0L
    private var downTarget = -1

    // ---- animated values ----
    private var darkMix = 0f
    private var scroll = 0f
    private var focusAmt = 1f
    private var shine = 1f
    private var btnAmt = 1f
    private var ccMix = 0f
    private var toastMix = 0f
    private var aDark: ValueAnimator? = null
    private var aScroll: ValueAnimator? = null
    private var aFocus: ValueAnimator? = null
    private var aShine: ValueAnimator? = null
    private var aBtn: ValueAnimator? = null
    private var aCc: ValueAnimator? = null
    private var aToast: ValueAnimator? = null
    private var aScrollTarget = 0f

    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pGlass = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pShadow = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pShade = Paint()
    private val pMask = Paint()
    private var maskGrad: LinearGradient? = null
    private val pSheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pShine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val lm = Matrix()
    private val saturate = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.55f) })
    private var clockGrad: LinearGradient? = null

    // shaders are built once and positioned with local matrices: nothing is allocated per frame
    private var blurShLight: BitmapShader? = null
    private var blurShDark: BitmapShader? = null
    private val dockSheen = LinearGradient(0f, 0f, DOCK_W * .707f, DOCK_W * .707f,
        intArrayOf(0x42FFFFFF, 0x17FFFFFF, 0x0AFFFFFF, 0x26FFFFFF), floatArrayOf(0f, .36f, .62f, 1f), Shader.TileMode.CLAMP)
    private val shineGrad = LinearGradient(0f, 0f, TILE_W * .906f, TILE_H * .423f,
        intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x59FFFFFF, 0x00FFFFFF, 0x00FFFFFF), floatArrayOf(0f, .35f, .5f, .65f, 1f), Shader.TileMode.CLAMP)
    private var autoNight = isNight(Calendar.getInstance())

    @JvmField val cc = NativeControlCentre(this)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        pStroke.style = Paint.Style.STROKE
        pText.typeface = REGULAR
        pShade.shader = LinearGradient(0f, 0f, 0f, H, intArrayOf(0x1F000000, 0x00000000, 0x80000000.toInt()), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        dark = isNight(Calendar.getInstance())
        darkMix = if (dark) 1f else 0f
        tick()
    }

    /** main.js tick(): clock and date strings; theme flips only when day/night changes. Formats only here, never in onDraw. */
    fun tick() {
        val c = Calendar.getInstance()
        clock = HomeClock.clock(c)
        date = HomeClock.date(c)
        ccTime = HomeClock.ccTime(c)
        val night = isNight(c)
        if (night != autoNight) { autoNight = night; setDark(night) }
        invalidate()
    }

    fun setDark(d: Boolean) {
        if (d == dark) return
        dark = d
        aDark = anim(aDark, darkMix, if (d) 1f else 0f, 700, null) { darkMix = it }
    }

    // ---------------- input ----------------
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Trace.beginSection("Glass:nativeKey")
        try {
            // main.js: Escape and Backspace are Back everywhere on Home
            val code = if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_DEL) KeyEvent.KEYCODE_BACK else keyCode
            if (cc.isOpen) return cc.onKey(code, event) || super.onKeyDown(keyCode, event)
            if (code == KeyEvent.KEYCODE_BACK) { host.exitPrototype(); return true }
            if (code == KeyEvent.KEYCODE_TAB) { tab(if (event.isShiftPressed) -1 else 1); return true }
            if (zone == 0) return rowsKey(code, event) || super.onKeyDown(keyCode, event)
            return headerKey(code, event) || super.onKeyDown(keyCode, event)
        } finally { Trace.endSection() }
    }

    private fun rowsKey(code: Int, e: KeyEvent): Boolean {
        val cards = cfg.rows[vpos].cards
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (col[vpos] > 0) { col[vpos]--; moved() }; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (col[vpos] < cards.size - 1) { col[vpos]++; moved() }; return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (vpos < cfg.rows.size - 1) { val c = col[vpos]; vpos++; col[vpos] = minOf(c, cfg.rows[vpos].cards.size - 1); moved() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (vpos > 0) { val c = col[vpos]; vpos--; col[vpos] = minOf(c, cfg.rows[vpos].cards.size - 1); moved() }
                else { zone = 1; if (hdrIdx == HomeNav.RECENT && !recentOn) hdrIdx = HomeNav.SEARCH; moved() }   // home.js keeps the last header stop
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (e.repeatCount > 0) return true                  // main.js: held OK never repeats outside Search
                host.playClick(); host.launch(cards[col[vpos]]); return true
            }
            else -> return false
        }
    }

    private fun headerKey(code: Int, e: KeyEvent): Boolean {
        val order = HomeNav.headerOrder(recentOn)
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val n = HomeNav.headerStep(order, hdrIdx, if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                if (n != hdrIdx) { hdrIdx = n; moved() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> { zone = 0; vpos = 0; moved(); return true }   // home.js: Down enters rows (row 0, remembered column)
            KeyEvent.KEYCODE_DPAD_UP -> return true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (e.repeatCount > 0) return true
                activateHeader(hdrIdx)
                return true
            }
            else -> return false
        }
    }

    private fun activateHeader(stop: Int) {
        when (stop) {
            HomeNav.SEARCH -> { host.playClick(); host.openSearch() }
            HomeNav.CC -> { host.playClick(); openControlCentre() }
            else -> if (recentOn) { host.playClick(); host.launchRecent() }
        }
    }

    /** home.js tab(): Tab / Shift+Tab walk the header stops, then every card, wrapping. */
    private fun tab(dir: Int) {
        val sizes = IntArray(cfg.rows.size) { cfg.rows[it].cards.size }
        val t = HomeNav.tab(HomeNav.headerOrder(recentOn), sizes, zone == 1, hdrIdx, vpos, col[vpos], dir)
        if (t[0] < 0) { zone = 1; hdrIdx = t[1] } else { zone = 0; vpos = t[0]; col[vpos] = t[1] }
        moved()
    }

    // ---- pointer (home.js: hover focuses on real movement, click activates, wheel steps one row per 350 ms) ----
    /** Hit test in 1920x1080 space: card = row * 16 + col, header stop = HDR_BASE + stop, -1 = nothing. */
    private fun target(x: Float, y: Float): Int {
        if (!(zone == 0 && vpos >= 1)) {            // body.scrolled: the Recently Used panel stops taking the pointer
            if (recentOn && x >= REC_X && x <= REC_X + REC_W && y >= REC_Y && y <= REC_Y + REC_H_HAS) return HDR_BASE + HomeNav.RECENT
        }
        for (i in 0 until 2) {
            val cx = if (i == 0) BTN_SEARCH_X else BTN_CC_X
            val dx = x - cx
            val dy = y - BTN_Y
            if (dx * dx + dy * dy <= BTN_R * BTN_R) return HDR_BASE + i
        }
        var top = ROW0_TOP - ROWS_SHIFT * scroll
        for (ri in 0 until cfg.rows.size) {
            val n = cfg.rows[ri].cards.size
            val tx = (W - (n * TILE_W + (n - 1) * GAP)) / 2f
            val ty = top + 1 + PAD_Y
            if (y >= ty && y <= ty + TILE_H) {
                for (ci in 0 until n) { val cx = tx + ci * (TILE_W + GAP); if (x >= cx && x <= cx + TILE_W) return ri * 16 + ci }
            }
            top += DOCK_H + ROW_GAP
        }
        return -1
    }

    private fun focusTarget(t: Int): Boolean {          // true when focus actually moved
        if (t >= HDR_BASE) {
            val s = t - HDR_BASE
            if (zone == 1 && hdrIdx == s) return false
            zone = 1; hdrIdx = s
        } else {
            val ri = t shr 4
            val ci = t and 15
            if (zone == 0 && vpos == ri && col[ri] == ci) return false
            zone = 0; vpos = ri; col[ri] = ci
        }
        moved()
        return true
    }

    private fun pointerMoved(x: Float, y: Float): Boolean {
        if (Math.abs(x - pointerX) + Math.abs(y - pointerY) < 4f) return false   // glass.js G.pointer.moved: >= 4 px
        pointerX = x; pointerY = y
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (cc.isOpen) return super.onGenericMotionEvent(e)
        val k = width / W
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (!pointerMoved(e.x, e.y)) return true
                val t = target(e.x / k, e.y / k)
                if (t >= 0) focusTarget(t)
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val dy = -e.getAxisValue(MotionEvent.AXIS_VSCROLL)    // >0: wheel down
                if (Math.abs(dy) < 0.05f || e.eventTime - wheelAt < 350) return true
                wheelAt = e.eventTime
                if (dy > 0) { if (zone == 1) zone = 0 else if (vpos < cfg.rows.size - 1) vpos++ else return true }
                else { if (zone == 1 || vpos <= 0) return true; vpos--; zone = 0 }
                col[vpos] = col[vpos].coerceIn(0, cfg.rows[vpos].cards.size - 1)
                moved()
                return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (cc.isOpen) return super.onTouchEvent(e)
        val k = width / W
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downTarget = target(e.x / k, e.y / k); return true }
            MotionEvent.ACTION_UP -> {
                val t = target(e.x / k, e.y / k)
                if (t >= 0 && t == downTarget) {
                    focusTarget(t)
                    if (t >= HDR_BASE) activateHeader(t - HDR_BASE)
                    else { val card = cfg.rows[t shr 4].cards[t and 15]; host.playClick(); host.launch(card) }
                }
                downTarget = -1
                return true
            }
            MotionEvent.ACTION_CANCEL -> { downTarget = -1; return true }
        }
        return super.onTouchEvent(e)
    }

    // ---- Recently Used / focus persistence (called on the UI thread by the activity) ----
    fun setRecent(lines: List<String>, initials: String, light: Bitmap?, dark: Bitmap?) {
        recentOn = true; recentLines = lines; recentInitials = initials; recentLight = light; recentDark = dark
        invalidate()
    }

    fun clearRecent() {
        recentOn = false; recentLines = emptyList(); recentLight = null; recentDark = null
        if (zone == 1 && hdrIdx == HomeNav.RECENT) hdrIdx = HomeNav.SEARCH
        invalidate()
    }

    /** [zone, vpos, hdrIdx, col...] for onSaveInstanceState. */
    fun focusState(): IntArray = intArrayOf(zone, vpos, hdrIdx) + col

    fun restoreFocus(s: IntArray?) {
        if (s == null || s.size != 3 + col.size) return
        for (i in col.indices) col[i] = s[3 + i].coerceIn(0, maxOf(0, cfg.rows[i].cards.size - 1))
        vpos = s[1].coerceIn(0, cfg.rows.size - 1)
        hdrIdx = if (s[2] in 0..2) s[2] else 0
        zone = if (s[0] == 1) 1 else 0
        if (zone == 1 && hdrIdx == HomeNav.RECENT && !recentOn) hdrIdx = HomeNav.SEARCH
        settle()
    }

    /** Jump every focus-driven animation to its end state (after returning from another app; focus itself is untouched). */
    fun settle() {
        aScroll?.cancel(); aFocus?.cancel(); aShine?.cancel(); aBtn?.cancel()
        scroll = if (zone == 0 && vpos > 0) 1f else 0f
        focusAmt = 1f; shine = 1f; btnAmt = 1f
        invalidate()
    }

    /** Focus changed: sound, focus/halo animation, shine sweep and the row scroll, all vsync-driven. */
    fun moved() {
        host.playNav()
        if (zone == 0) {
            aFocus = anim(aFocus, 0f, 1f, 220, EASE) { focusAmt = it }
            aShine = anim(aShine, 0f, 1f, 700, null) { shine = it }
        }
        aBtn = anim(aBtn, 0f, 1f, 200, EASE) { btnAmt = it }
        val target = if (zone == 0 && vpos > 0) 1f else 0f
        val running = aScroll?.isRunning == true
        if (target != scroll && (!running || aScrollTarget != target)) {
            aScrollTarget = target
            aScroll = anim(aScroll, scroll, target, 450, EASE) { scroll = it }
        }
        invalidate()
    }

    fun openControlCentre(focusVolume: Boolean = false) { cc.open(if (focusVolume) CcLogic.T_VOL else 0); aCc = anim(aCc, ccMix, 1f, 420, EASE) { ccMix = it } }
    fun closeControlCentre() { cc.isOpen = false; aCc = anim(aCc, ccMix, 0f, 420, EASE) { ccMix = it }; host.playNav() }

    // Search overlay (NativeSearchView sits above this view): body.searchopen dims the home like body.ccopen.
    private var searchMix = 0f
    private var aSearch: ValueAnimator? = null
    /** Draws the toast above Search; null means this view draws it itself. */
    var toastLayer: View? = null

    fun setSearchOpen(open: Boolean) { aSearch = anim(aSearch, searchMix, if (open) 1f else 0f, 300, EASE) { searchMix = it } }
    private fun dimMix() = if (ccMix > searchMix) ccMix else searchMix

    /** Called by the toast layer's onDraw. */
    fun drawToastLayer(c: Canvas) {
        val k = width / W
        c.save(); c.scale(k, k); drawToast(c); c.restore()
    }

    fun showToast(text: String) {
        toast = text; toastUntil = System.currentTimeMillis() + 3200
        aToast = anim(aToast, toastMix, 1f, 300, null) { toastMix = it; toastLayer?.invalidate() }
        postDelayed({
            if (System.currentTimeMillis() >= toastUntil) aToast = anim(aToast, toastMix, 0f, 300, null) { toastMix = it; toastLayer?.invalidate() }
        }, 3200)
    }

    fun anim(old: ValueAnimator?, from: Float, to: Float, ms: Long, ip: TimeInterpolator?, s: Setter): ValueAnimator {
        old?.cancel()
        val a = ValueAnimator.ofFloat(from, to)
        a.duration = ms
        if (ip != null) a.interpolator = ip
        a.addUpdateListener { s.set(it.animatedValue as Float); invalidate() }
        a.start()
        return a
    }

    // ---------------- drawing ----------------
    override fun onDraw(c: Canvas) {
        Trace.beginSection("Glass:nativeDraw")
        try {
            val k = width / W
            c.save()
            c.scale(k, k)
            drawBackdrop(c)
            drawHead(c)
            drawRecent(c)
            drawButtons(c)
            if (searchMix > 0f) { c.saveLayerAlpha(0f, 0f, W, H, (255 * (1f - .5f * searchMix)).toInt()); drawRowsMasked(c); c.restore() } else drawRowsMasked(c)   // searchopen #stage .5
            if (ccMix > 0f) cc.draw(c, ccMix)
            if (toastLayer == null) drawToast(c)
            c.restore()
        } finally { Trace.endSection() }
    }

    private fun drawBackdrop(c: Canvas) {
        c.drawColor(0xFF0B0B14.toInt())
        val wl = wallLight
        val wd = wallDark
        if (wl != null && darkMix < 1f) { r.set(0f, 0f, W, H); c.drawBitmap(wl, null, r, pBmp) }
        if (wd != null && darkMix > 0f) {
            pBmp.alpha = (255 * darkMix).toInt(); r.set(0f, 0f, W, H); c.drawBitmap(wd, null, r, pBmp); pBmp.alpha = 255
        }
        c.drawRect(0f, 0f, W, H, pShade)
    }

    private fun drawHead(c: Canvas) {
        val s = 1f + (HEAD_SCALE - 1f) * scroll
        val alpha = (1f - 0.45f * scroll) * (1f - 0.7f * dimMix())         // .scrolled .55, ccopen/searchopen .3
        c.save()
        c.translate(0f, -HEAD_LIFT * scroll)
        c.scale(s, s, W / 2f, 0f)
        pText.textAlign = Paint.Align.CENTER
        pText.shader = null
        pText.color = Color.WHITE
        pText.alpha = (255 * .9f * alpha).toInt()
        pText.textSize = 30f; pText.isFakeBoldText = true
        pText.setShadowLayer(12f, 0f, 2f, 0x59000000)
        c.drawText(date, W / 2f, 54f + 30, pText)
        pText.clearShadowLayer()
        pText.textSize = 240f
        pText.typeface = BOLD; pText.isFakeBoldText = false
        pText.letterSpacing = -.02f
        var g = clockGrad
        if (g == null) {
            g = LinearGradient(0f, 90f, 0f, 90f + 264,
                intArrayOf(0xF5FFFFFF.toInt(), 0xF5FFFFFF.toInt(), 0x2EFFFFFF), floatArrayOf(0f, .10f, .95f), Shader.TileMode.CLAMP)
            clockGrad = g
        }
        pText.shader = g
        pText.alpha = (255 * alpha).toInt()
        c.drawText(clock, W / 2f, 90f + 12 + 223, pText)
        pText.shader = null; pText.letterSpacing = 0f; pText.typeface = REGULAR
        c.restore()
    }

    private fun drawButtons(c: Canvas) {
        val vis = 1f - dimMix()
        if (vis <= 0f) return
        for (i in 0 until 2) {
            val f = zone == 1 && hdrIdx == i
            val amt = if (f) btnAmt else 0f
            val cx = if (i == 0) BTN_SEARCH_X else BTN_CC_X
            val rad = BTN_R * (1f + .1f * amt)
            r.set(cx - rad, BTN_Y - rad, cx + rad, BTN_Y + rad)
            pFill.shader = null
            pFill.color = blend(0x8028283A.toInt(), 0xF7F3F5FA.toInt(), amt); pFill.alpha = (Color.alpha(pFill.color) * vis).toInt()
            c.drawOval(r, pFill)
            pStroke.strokeWidth = 1f; pStroke.color = blend(0x33FFFFFF, 0xFFFFFFFF.toInt(), amt); pStroke.alpha = (Color.alpha(pStroke.color) * vis).toInt()
            c.drawOval(r, pStroke)
            val ink = blend(0xFFFFFFFF.toInt(), 0xFF14161C.toInt(), amt)
            if (i == 0) NativeIcons.search(c, cx, BTN_Y, 16 * (1f + .1f * amt), ink, vis)
            else NativeIcons.sliders(c, cx, BTN_Y, 16 * (1f + .1f * amt), ink, vis)
        }
    }

    /**
     * #stage mask (home.js:88-90): alpha 0 to viewTop-24, ramp to 1 at viewTop, 1 to viewBottom-24, ramp to 0 at viewBottom+56.
     * The layer is limited to the rows' bounds and only used while they reach a fade band.
     */
    private fun drawRowsMasked(c: Canvas) {
        val top = ROW0_TOP - ROWS_SHIFT * scroll - ROWS_HALO
        val bottom = ROW0_TOP - ROWS_SHIFT * scroll + cfg.rows.size * (DOCK_H + ROW_GAP) - ROW_GAP + ROWS_HALO
        if (top >= FADE_TOP_END && bottom <= FADE_BOT_START) { drawRows(c); return }
        val t = if (top < 0f) 0f else top
        val b = if (bottom > H) H else bottom
        if (b <= t) return
        var g = maskGrad
        if (g == null) {
            g = LinearGradient(0f, 0f, 0f, H, intArrayOf(0, 0, Color.BLACK, Color.BLACK, 0),
                floatArrayOf(0f, FADE_TOP_START / H, FADE_TOP_END / H, FADE_BOT_START / H, FADE_BOT_END / H), Shader.TileMode.CLAMP)
            maskGrad = g
            pMask.shader = g
            pMask.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        val sc = c.saveLayer(0f, t, W, b, null)
        drawRows(c)
        c.drawRect(0f, t, W, b, pMask)
        c.restoreToCount(sc)
    }

    private fun drawRows(c: Canvas) {
        var y = ROW0_TOP - ROWS_SHIFT * scroll
        val blur = if (darkMix >= .5f) blurDark else blurLight
        for (ri in 0 until cfg.rows.size) {
            val row = cfg.rows[ri]
            // no row labels: home.js builds both rows with makeRow('', ...) (LAYOUT.showLabels is false)
            val left = (W - DOCK_W) / 2f
            drawGlass(c, left, y, DOCK_W, DOCK_H, DOCK_R, blur)
            val n = row.cards.size
            val tilesW = n * TILE_W + (n - 1) * GAP
            val tx = (W - tilesW) / 2f
            val ty = y + 1 + PAD_Y
            // unfocused cards first, the focused one last so its scaled halo sits on top (z-index:3)
            for (ci in 0 until n) if (!(zone == 0 && vpos == ri && col[ri] == ci)) drawCard(c, ri, ci, tx + ci * (TILE_W + GAP), ty, 0f)
            if (zone == 0 && vpos == ri) drawCard(c, ri, col[ri], tx + col[ri] * (TILE_W + GAP), ty, focusAmt)
            y += DOCK_H + ROW_GAP
        }
    }

    /** .glass: blurred + saturated backdrop, tint, 135deg sheen, hairline edge, soft shadow (baked). */
    private fun drawGlass(c: Canvas, x: Float, y: Float, w: Float, h: Float, rad: Float, blur: Bitmap?) {
        val ds = dockShadow
        if (ds != null && w == DOCK_W && h == DOCK_H) {
            pShadow.color = 0x42000000
            val pad = (ds.width - w) / 2f
            c.drawBitmap(ds, x - pad, y - pad + 36, pShadow)
        }
        r.set(x, y, x + w, y + h)
        if (blur != null) {
            pGlass.shader = blurShader(blur); pGlass.colorFilter = saturate
            c.drawRoundRect(r, rad, rad, pGlass)
            pGlass.shader = null; pGlass.colorFilter = null
        }
        pFill.shader = null
        pFill.color = if (darkMix >= .5f) 0x700A0C12 else 0x4D181B24
        c.drawRoundRect(r, rad, rad, pFill)
        lm.setTranslate(x, y); dockSheen.setLocalMatrix(lm)
        pSheen.shader = dockSheen
        c.drawRoundRect(r, rad, rad, pSheen)
        pStroke.strokeWidth = 1f; pStroke.color = 0x33FFFFFF
        r.inset(.5f, .5f)
        c.drawRoundRect(r, rad, rad, pStroke)
    }

    private fun drawCard(c: Canvas, ri: Int, ci: Int, x: Float, y: Float, f: Float) {
        val s = 1f + .06f * f
        c.save()
        c.scale(s, s, x + TILE_W / 2f, y + TILE_H / 2f)
        if (f > 0f) {
            // .slot.f::before halo (lite: flat white .3, no blur) and the card's shadow
            r.set(x - 7, y - 7, x + TILE_W + 7, y + TILE_H + 7)
            pFill.shader = null; pFill.color = Color.argb((255 * .3f * f).toInt(), 255, 255, 255)
            c.drawRoundRect(r, 29f, 29f, pFill)
            pStroke.strokeWidth = 2.4f; pStroke.color = Color.argb((255 * .85f * f).toInt(), 255, 255, 255)
            c.drawRoundRect(r, 29f, 29f, pStroke)
            val ts = tileShadow
            if (ts != null) {
                pShadow.color = Color.argb((255 * .45f * f).toInt(), 0, 0, 0)
                val pad = (ts.width - TILE_W) / 2f
                c.drawBitmap(ts, x - pad, y - pad + 16, pShadow)
            }
        }
        val night = darkMix >= .5f
        val card = cfg.rows[ri].cards[ci]
        var art = (if (night) artDark else artLight)[ri][ci]
        if (art == null) art = (if (night) artLight else artDark)[ri][ci]
        if (art == null) art = cardIcon[ri][ci]
        if (card.placeholder) drawPlaceholder(c, card, x, y)
        else if (art != null) c.drawBitmap(art, x, y, pBmp)
        else {
            // home.js onerror / no art: the card's text (or initials) on its background colour
            r.set(x, y, x + TILE_W, y + TILE_H)
            pFill.shader = null
            pFill.color = card.bg; c.drawRoundRect(r, TILE_R, TILE_R, pFill)
            val px = card.size * 20f
            pText.textAlign = Paint.Align.CENTER; pText.textSize = px; pText.isFakeBoldText = true; pText.color = card.fg
            c.drawText(labels[ri][ci], x + TILE_W / 2f, y + TILE_H / 2f + px * .34f, pText)
            pText.isFakeBoldText = false
        }
        if (f > 0f && shine < 1f && !card.placeholder) {
            // .tile::after: a 115deg light band swept from -120% to +120% of the card width in 700 ms
            val off = (-1.2f + 2.4f * shine) * TILE_W
            lm.setTranslate(x + off, y); shineGrad.setLocalMatrix(lm)
            pShine.shader = shineGrad
            r.set(x, y, x + TILE_W, y + TILE_H)
            c.drawRoundRect(r, TILE_R, TILE_R, pShine)
        }
        c.restore()
    }

    /** Screen-aligned shader over the pre-blurred wallpaper (one per theme, built once). */
    private fun blurShader(blur: Bitmap): BitmapShader {
        var sh = if (blur === blurDark) blurShDark else blurShLight
        if (sh == null) {
            sh = BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            lm.setScale(W / blur.width, H / blur.height)
            sh.setLocalMatrix(lm)
            if (blur === blurDark) blurShDark = sh else blurShLight = sh
        }
        return sh
    }

    /**
     * Blurred backdrop for a panel drawn under canvas.translate(tx, ty) (the Control Centre). Content behind the panel is
     * covered rather than blurred - backdrop-filter has no API 30 equivalent - so only the wallpaper shows through.
     */
    fun drawBlurBackdrop(c: Canvas, rr: RectF, rad: Float, tx: Float, ty: Float, alpha: Float) {
        val blur = (if (darkMix >= .5f) blurDark else blurLight) ?: return
        val sh = blurShader(blur)
        lm.setScale(W / blur.width, H / blur.height); lm.postTranslate(-tx, -ty); sh.setLocalMatrix(lm)
        pGlass.shader = sh; pGlass.colorFilter = saturate; pGlass.alpha = (255 * alpha).toInt()
        c.drawRoundRect(rr, rad, rad, pGlass)
        pGlass.shader = null; pGlass.colorFilter = null; pGlass.alpha = 255
        lm.setScale(W / blur.width, H / blur.height); sh.setLocalMatrix(lm)
    }

    private val recFocusGrad = LinearGradient(0f, 0f, 0f, REC_H_HAS, 0xF7FFFFFF.toInt(), 0xE6EEF1F8.toInt(), Shader.TileMode.CLAMP)
    private val pDash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f) }
    private val lightFace: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    /** `.tile.ph`: dashed outline, "+" above the name (a configured placeholder slot). */
    private fun drawPlaceholder(c: Canvas, k: NativeHomeConfig.Card, x: Float, y: Float) {
        r.set(x, y, x + TILE_W, y + TILE_H)
        pFill.shader = null; pFill.color = 0x12FFFFFF
        c.drawRoundRect(r, TILE_R, TILE_R, pFill)
        r.inset(1f, 1f)
        pDash.color = 0x59FFFFFF
        c.drawRoundRect(r, TILE_R - 1f, TILE_R - 1f, pDash)
        val cx = x + TILE_W / 2f
        pText.textAlign = Paint.Align.CENTER; pText.color = 0xB3FFFFFF.toInt()
        pText.typeface = lightFace; pText.textSize = 48f
        c.drawText("+", cx, y + 83f, pText)
        pText.typeface = BOLD; pText.textSize = 20f
        c.drawText(k.name, cx, y + 119f, pText)
        pText.typeface = REGULAR
    }

    /** #recent (recent.css): empty hint, or the last launched app's art and name; focusable only in the second state. */
    private fun drawRecent(c: Canvas) {
        val a = (1f - scroll) * (1f - .7f * dimMix())        // .scrolled: fades out; ccopen/searchopen: .3
        if (a <= 0.01f) return
        val has = recentOn
        val f = if (has && zone == 1 && hdrIdx == HomeNav.RECENT) btnAmt else 0f
        val x = REC_X
        val y = REC_Y
        val w = REC_W
        val h = if (has) REC_H_HAS else REC_H_EMPTY
        val rad = 36f
        c.save()
        c.translate(0f, -28 * scroll)
        if (f > 0f) c.scale(1f + .04f * f, 1f + .04f * f, x + w / 2f, y + h / 2f)       // #recent.f scale(1.04)
        r.set(x, y, x + w, y + h)
        pFill.shader = null; pFill.color = Color.argb((128 * a).toInt(), 30, 34, 46)
        c.drawRoundRect(r, rad, rad, pFill)
        lm.setTranslate(x, y); dockSheen.setLocalMatrix(lm); pSheen.shader = dockSheen; pSheen.alpha = (255 * a).toInt()
        c.drawRoundRect(r, rad, rad, pSheen); pSheen.alpha = 255
        if (f > 0f) {                                       // bright focus, the same inversion the header buttons use
            lm.setTranslate(x, y); recFocusGrad.setLocalMatrix(lm)
            pFill.shader = recFocusGrad; pFill.alpha = (255 * f * a).toInt()
            c.drawRoundRect(r, rad, rad, pFill)
            pFill.shader = null; pFill.alpha = 255
        }
        pStroke.strokeWidth = 1f; pStroke.color = blend(0x33FFFFFF, 0xFFFFFFFF.toInt(), f); pStroke.alpha = (Color.alpha(pStroke.color) * a).toInt()
        c.drawRoundRect(r, rad, rad, pStroke)
        pText.textAlign = Paint.Align.LEFT; pText.shader = null
        pText.typeface = BOLD; pText.textSize = 15f; pText.letterSpacing = .08f
        val cap = blend(0xB2FFFFFF.toInt(), 0x9914161C.toInt(), f)
        pText.color = cap; pText.alpha = (Color.alpha(cap) * a).toInt()
        c.drawText("RECENTLY USED", x + 22, y + 33, pText)
        pText.letterSpacing = 0f
        if (!has) {
            pText.typeface = REGULAR; pText.textSize = 19f; pText.color = Color.WHITE; pText.alpha = (200 * a).toInt()
            c.drawText("Apps you open will appear here", x + 22, y + 72, pText)
        } else {
            val tx = x + 22
            val ty = y + 49
            val th = if (darkMix >= .5f) recentDark ?: recentLight else recentLight ?: recentDark
            if (th != null) {
                pBmp.alpha = (255 * a).toInt(); c.drawBitmap(th, tx, ty, pBmp); pBmp.alpha = 255
            } else {
                r.set(tx, ty, tx + RTHUMB_W, ty + RTHUMB_H)
                pFill.color = Color.argb((31 * a).toInt(), 255, 255, 255)
                c.drawRoundRect(r, 16f, 16f, pFill)
                val ink = blend(0xFFFFFFFF.toInt(), 0xFF14161C.toInt(), f)
                pText.typeface = BOLD; pText.textAlign = Paint.Align.CENTER; pText.textSize = 24f
                pText.color = ink; pText.alpha = (Color.alpha(ink) * a).toInt()
                c.drawText(recentInitials, tx + RTHUMB_W / 2f, ty + RTHUMB_H / 2f + 8.5f, pText)
                pText.textAlign = Paint.Align.LEFT
            }
            val ink = blend(0xFFFFFFFF.toInt(), 0xFF14161C.toInt(), f)
            pText.typeface = BOLD; pText.textSize = 21.6f; pText.color = ink; pText.alpha = (255 * a).toInt()
            val cy = ty + RTHUMB_H / 2f
            val n = recentLines.size
            for (i in 0 until n) c.drawText(recentLines[i], tx + RTHUMB_W + 17f, cy + 7.6f + (i - (n - 1) / 2f) * 26f, pText)
            pText.typeface = REGULAR
        }
        pText.typeface = REGULAR; pText.alpha = 255
        c.restore()
    }

    private fun drawToast(c: Canvas) {
        val t = toast
        if (t == null || toastMix <= 0f) return
        pText.textAlign = Paint.Align.CENTER; pText.textSize = 22f; pText.color = Color.WHITE
        val tw = pText.measureText(t)
        r.set(W / 2f - tw / 2f - 36, H / 2f - 31, W / 2f + tw / 2f + 36, H / 2f + 31)
        pFill.shader = null; pFill.color = Color.argb((209 * toastMix).toInt(), 24, 27, 36)
        c.drawRoundRect(r, 28f, 28f, pFill)
        pText.alpha = (255 * toastMix).toInt()
        c.drawText(t, W / 2f, H / 2f + 8, pText)
    }

    /** Focused card index for tests: row, column (or -1, header index when the header has focus). */
    fun focusForTest(): IntArray = if (zone == 0) intArrayOf(vpos, col[vpos]) else intArrayOf(-1, hdrIdx)

    /** Lists of values that tests and the latency probe can read without reaching into drawing state. */
    fun cardNames(row: Int): List<String> = cfg.rows[row].cards.map { it.name }
}
