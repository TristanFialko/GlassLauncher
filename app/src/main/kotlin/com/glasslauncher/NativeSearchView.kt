package com.glasslauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.os.Trace
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import java.util.concurrent.Executor

/** Supplies app icons. Called on the background executor only (PackageManager and bitmap work are allowed there). */
fun interface IconSource {
    /** A square bitmap of about [sizePx] pixels for the package, or null if it has none. The view owns it afterwards. */
    fun loadIcon(pkg: String, sizePx: Int): Bitmap?
}

/**
 * Full-screen native Search overlay (surface S5): panel with field, results list, on-screen D-pad keyboard and hint bar.
 * Geometry is css/search.css at 1920x1080 in px (rem = 20); the canvas is scaled by width/1920. Behaviour lives in
 * [SearchController]; this class only draws, animates and translates Android key events. Nothing here touches disk,
 * PackageManager, JNI or bitmap decoding on the UI thread, and onDraw allocates nothing.
 *
 * Wiring: stack it above the home view in a FrameLayout (MATCH_PARENT), call [setBackdrops]/[setDark], [open] from the
 * Search button, and forward key events to [onKeyDown] while [isOpen]. See docs/nativeui/SEARCH.md.
 */
class NativeSearchView(
    context: Context,
    private val host: SearchHost,
    engine: SearchEngine,
    private val icons: IconSource,
    private val background: Executor,
) : View(context) {

    companion object {
        // ---- geometry (design px) ----
        const val W = 1920f; const val H = 1080f
        const val PX = 60f; const val PY = 170f; const val PW = 1800f; const val PR = 60f
        const val BAR_X = PX + 37; const val BAR_Y = PY + 37; const val BAR_W = PW - 74; const val BAR_H = 112f; const val BAR_R = 40f
        const val BODY_Y = BAR_Y + BAR_H + 30
        const val KB_X = BAR_X + BAR_W - SearchKeyboard.ROW_W
        const val KB_STRIDE = SearchKeyboard.KEY_H + SearchKeyboard.GAP
        const val KB_H = 4 * SearchKeyboard.KEY_H + 3 * SearchKeyboard.GAP
        const val LEFT_X = BAR_X; const val LEFT_W = KB_X - 32 - BAR_X; const val LEFT_R = 40f
        const val ROW_X = LEFT_X + 17; const val ROW_W = LEFT_W - 34; const val ROW_H = 94f; const val ROW_STRIDE = 98f; const val ROW_R = 26f
        const val LIST_Y = BODY_Y + 17; const val HDR_H = 41f; const val VIEW_H = KB_H - 2 - 16
        const val HINT_Y = BODY_Y + KB_H + 22; const val HINT_H = 22f
        const val PH = HINT_Y + HINT_H + 37 - PY
        const val ICON_BOX = 64f; const val ICON_R = 17f
        const val INK = 0xFF14161C.toInt()
        val EASE = PathInterpolator(.2f, .8f, .2f, 1f)         // --ease
        private val CSS_EASE = PathInterpolator(.25f, .1f, .25f, 1f)
        private const val OPEN_MS = 420L
        private val HINTS = arrayOf("OK" to "Select", "Left" to "Results", "Back" to "Close")
        private val MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private val REGULAR = Typeface.create("sans-serif", Typeface.NORMAL)
        private val BOLD = Typeface.create("sans-serif", Typeface.BOLD)

        fun lerpColor(a: Int, b: Int, t: Float): Int {
            val u = 1f - t
            return Color.argb(
                (Color.alpha(a) * u + Color.alpha(b) * t).toInt(), (Color.red(a) * u + Color.red(b) * t).toInt(),
                (Color.green(a) * u + Color.green(b) * t).toInt(), (Color.blue(a) * u + Color.blue(b) * t).toInt())
        }
    }

    // ---- logic ----
    private val controller = SearchController(host, engine, { SystemClock.uptimeMillis() }, ::onChange)
    val isOpen: Boolean get() = controller.isOpen

    init {
        engine.listener = object : SearchEngine.Listener {
            override fun onResults(query: String, results: List<SearchResult>) = controller.onResults(query, results)
            override fun onFailed(query: String) = controller.onFailed(query)
        }
        visibility = INVISIBLE
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ---- paints (all preallocated) ----
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val pGlass = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pShadow = Paint(Paint.FILTER_BITMAP_FLAG)
    private fun text(size: Float, face: Typeface, align: Paint.Align = Paint.Align.LEFT) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = face; textAlign = align }
    private val tField = text(40f, MEDIUM).apply { letterSpacing = .005f }
    private val tPlaceholder = text(40f, REGULAR)
    private val tTitle = text(27f, MEDIUM)
    private val tSub = text(18f, REGULAR)
    private val tTag = text(15.6f, BOLD).apply { letterSpacing = .08f }
    private val tHdr = text(16.4f, BOLD).apply { letterSpacing = .1f }
    private val tInit = text(22f, BOLD, Paint.Align.CENTER)
    private val tKey = text(37f, MEDIUM, Paint.Align.CENTER)
    private val tKeyFn = text(26f, MEDIUM, Paint.Align.CENTER)
    private val tHint = text(19f, REGULAR)
    private val tHintB = text(19f, BOLD)
    private val tNone = text(24f, REGULAR).apply { color = 0xADFFFFFF.toInt() }
    // Sizes and faces of these paints never change, so their metrics are read once: Paint.getFontMetrics() allocates.
    private val fmField = tField.fontMetrics
    private val fmHdr = tHdr.fontMetrics
    private val fmInit = tInit.fontMetrics
    private val fmTitle = tTitle.fontMetrics
    private val fmSub = tSub.fontMetrics
    private val fmTag = tTag.fontMetrics
    private val fmKey = tKey.fontMetrics
    private val fmKeyFn = tKeyFn.fontMetrics
    private val saturate = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.55f) })
    private val dashEffect = DashPathEffect(floatArrayOf(2.6f, 3.1f), 0f)

    // ---- reusable geometry ----
    private val r = RectF()
    private val r2 = RectF()
    private val src = Rect()
    private val m = Matrix()
    private val clip = Path()
    private val iconPaths = HashMap<String, Array<Path>>()

    // ---- gradients, built once in design space and moved with local matrices ----
    private val sheen = run {
        val a = (PW - PH) / 4; val b = (PH - PW) / 4; val c = (3 * PW + PH) / 4; val d = (3 * PH + PW) / 4
        LinearGradient(a, b, c, d, intArrayOf(0x42FFFFFF, 0x17FFFFFF, 0x0AFFFFFF, 0x26FFFFFF), floatArrayOf(0f, .36f, .62f, 1f), Shader.TileMode.CLAMP)
    }
    private val topHi = LinearGradient(0f, 0f, 0f, PR, 0x75FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    private fun vgrad(top: Int, bottom: Int, h: Float) = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
    private val gBar = vgrad(0x33FFFFFF, 0x17FFFFFF, BAR_H)
    private val gLeft = vgrad(0x1AFFFFFF, 0x0BFFFFFF, KB_H)
    private val gRowFocus = vgrad(0xF7FFFFFF.toInt(), 0xE6EEF1F8.toInt(), ROW_H)
    private val gKey = vgrad(0x3DFFFFFF, 0x1CFFFFFF, SearchKeyboard.KEY_H)
    private val gKeyFn = vgrad(0x66121420, 0x42121420, SearchKeyboard.KEY_H)
    private val gKeyFocus = vgrad(0xFFFFFFFF.toInt(), 0xF0ECF0F8.toInt(), SearchKeyboard.KEY_H)
    private val gKeyShift = vgrad(0xE0FFFFFF.toInt(), 0xCCECF0F8.toInt(), SearchKeyboard.KEY_H)

    // ---- backdrop ----
    private var blurLight: Bitmap? = null
    private var blurDark: Bitmap? = null
    private var shLight: BitmapShader? = null
    private var shDark: BitmapShader? = null
    private var dark = false

    fun setBackdrops(light: Bitmap?, darkBmp: Bitmap?) {
        blurLight = light; blurDark = darkBmp
        shLight = light?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { m.setScale(W / it.width, H / it.height); setLocalMatrix(m) } }
        shDark = darkBmp?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { m.setScale(W / it.width, H / it.height); setLocalMatrix(m) } }
        invalidate()
    }

    fun setDark(d: Boolean) { if (d != dark) { dark = d; invalidate() } }

    // ---- baked shadows (nine-slice), produced off the UI thread ----
    private class Shadow(val bmp: Bitmap, val pad: Float, val corner: Float)
    private var shPanel: Shadow? = null
    private var shRow: Shadow? = null
    private var shKey: Shadow? = null
    private var baking = false
    private val xs = FloatArray(4); private val ys = FloatArray(4); private val sx = IntArray(4); private val sy = IntArray(4)

    private fun bake(radius: Float, blur: Float, alpha: Int): Shadow {
        val pad = Math.ceil(blur * 1.5).toFloat()
        val corner = radius + pad
        val size = (corner * 2 + 2).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, 0, 0, 0)
            maskFilter = BlurMaskFilter(maxOf(1f, (blur / 2f - .5f) / .57735f), BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(b).drawRoundRect(pad, pad, size - pad, size - pad, radius, radius, p)
        return Shadow(b, pad, corner)
    }

    private fun bakeShadows() {
        if (baking) return
        baking = true
        background.execute {
            val a = bake(PR, 88f, 255); val b = bake(ROW_R, 28f, 255); val c = bake(27f, 36f, 255)
            post { shPanel = a; shRow = b; shKey = c; invalidate() }
        }
    }

    /** Draws [s] under the rounded rect in [rect] (offset dy, opacity [alpha] 0..255 applied on top of the baked 100%). */
    private fun drawShadow(c: Canvas, s: Shadow?, rect: RectF, dy: Float, alpha: Int) {
        if (s == null || alpha <= 0) return
        val bw = s.bmp.width; val bh = s.bmp.height; val k = s.corner.toInt()
        val l = rect.left - s.pad; val t = rect.top - s.pad + dy; val rr = rect.right + s.pad; val bt = rect.bottom + s.pad + dy
        pShadow.alpha = alpha
        xs[0] = l; xs[1] = l + s.corner; xs[2] = rr - s.corner; xs[3] = rr; ys[0] = t; ys[1] = t + s.corner; ys[2] = bt - s.corner; ys[3] = bt
        sx[1] = k; sx[2] = bw - k; sx[3] = bw; sy[1] = k; sy[2] = bh - k; sy[3] = bh
        for (j in 0 until 3) for (i in 0 until 3) {
            src.set(sx[i], sy[j], sx[i + 1], sy[j + 1]); r2.set(xs[i], ys[j], xs[i + 1], ys[j + 1])
            c.drawBitmap(s.bmp, src, r2, pShadow)
        }
    }

    // ---- state mirrored from the controller for drawing ----
    private class Row(val res: SearchResult, val title: String, val sub: String?, val tag: String, val tagW: Float, val initials: String)
    private var rows: Array<Row> = emptyArray()
    private var hasHeader = true
    private var noneLayout: StaticLayout? = null
    private var queryText = ""
    private var queryW = 0f
    private var labels: Array<Array<String>> = emptyArray()
    private var scroll = 0f           // design px, positive = content moved up
    private var scrollTarget = 0f
    private var openedAt = 0L
    private var pressRow = -1; private var pressIdx = -1; private var pressUntil = 0L
    private val hintX = FloatArray(HINTS.size * 2)
    private val hintBoldW = FloatArray(HINTS.size)

    // ---- animation ----
    private var openV = 0f
    private val aOpen = ValueAnimator.ofFloat(0f, 1f).apply {
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { openV = it.animatedValue as Float; invalidate() }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) { if (hiding) { visibility = INVISIBLE; hiding = false } }
        })
    }
    private var hiding = false

    private inner class FocusAnim(private val ms: Long) {
        var from = -1; var to = -1; var t = 1f
        private val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms; interpolator = EASE
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
        }
        fun set(id: Int, animate: Boolean) {
            if (id == to) return
            from = to; to = id
            a.cancel()
            if (animate) { t = 0f; a.start() } else { t = 1f; from = -1 }
        }
        fun amt(id: Int): Float = when (id) { to -> if (from == to) 1f else t; from -> 1f - t; else -> 0f }
        fun cancel() { a.cancel() }
    }
    private val keyFocus = FocusAnim(160)
    private val rowFocus = FocusAnim(180)
    private val aScroll = ValueAnimator.ofFloat(0f, 1f).apply { duration = 220; interpolator = EASE }
    private var scrollFrom = 0f
    init { aScroll.addUpdateListener { scroll = scrollFrom + (scrollTarget - scrollFrom) * (it.animatedValue as Float); invalidate() } }

    init {
        for ((k, shapes) in SvgIcons.ICONS) iconPaths[k] = Array(shapes.size) { toPath(shapes[it].cmds) }
        measureHints()
        rebuildLabels()
    }

    private fun toPath(c: FloatArray): Path {
        val p = Path()
        var i = 0
        while (i < c.size) {
            when (c[i]) {
                SvgIcons.MOVE -> { p.moveTo(c[i + 1], c[i + 2]); i += 3 }
                SvgIcons.LINE -> { p.lineTo(c[i + 1], c[i + 2]); i += 3 }
                SvgIcons.CUBIC -> { p.cubicTo(c[i + 1], c[i + 2], c[i + 3], c[i + 4], c[i + 5], c[i + 6]); i += 7 }
                else -> { p.close(); i += 1 }
            }
        }
        return p
    }

    private fun measureHints() {
        var total = 0f
        for ((i, h) in HINTS.withIndex()) {
            hintBoldW[i] = tHintB.measureText(h.first)
            total += hintBoldW[i] + 8f + tHint.measureText(h.second)
        }
        total += 48f * (HINTS.size - 1)
        var x = PX + PW / 2 - total / 2
        for ((i, h) in HINTS.withIndex()) {
            hintX[i * 2] = x; hintX[i * 2 + 1] = x + hintBoldW[i] + 8f
            x += hintBoldW[i] + 8f + tHint.measureText(h.second) + 48f
        }
    }

    // ---- public API ----
    fun open() {
        if (controller.isOpen) return
        controller.open()
    }

    /** Closes Search; [restoreFocus] is passed to [SearchHost.onSearchClosed]. */
    fun close(restoreFocus: Boolean = true) { controller.close(restoreFocus) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!controller.isOpen) return false
        val rep = event.repeatCount > 0
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { if (!rep) controller.key(SKey.BACK); true }
            KeyEvent.KEYCODE_DPAD_UP -> controller.key(SKey.UP, rep)
            KeyEvent.KEYCODE_DPAD_DOWN -> controller.key(SKey.DOWN, rep)
            KeyEvent.KEYCODE_DPAD_LEFT -> controller.key(SKey.LEFT, rep)
            KeyEvent.KEYCODE_DPAD_RIGHT -> controller.key(SKey.RIGHT, rep)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> controller.key(SKey.OK, rep)
            KeyEvent.KEYCODE_DEL -> controller.key(SKey.BACKSPACE, rep)
            KeyEvent.KEYCODE_TAB -> { controller.tab(if (event.isShiftPressed) -1 else 1); true }
            else -> {
                if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
                val u = event.unicodeChar
                if (u < 0x20 || u == 0x7f || (u and KeyCharacterMap.COMBINING_ACCENT) != 0) return false
                controller.hardwareChar(String(Character.toChars(u)))
                true
            }
        }
    }

    // ---- controller events ----
    private fun onChange(what: Int) {
        if (what and SearchController.OPEN != 0) { if (controller.isOpen) show() else hide() }
        if (what and SearchController.RESULTS != 0) rebuildRows()
        if (what and SearchController.QUERY != 0) { queryText = controller.query; queryW = tField.measureText(queryText) }
        if (what and SearchController.KEYS != 0) rebuildLabels()
        if (what and (SearchController.FOCUS or SearchController.KEYS or SearchController.OPEN) != 0) syncFocus(what and SearchController.OPEN == 0)
        if (what and SearchController.PRESS != 0) {
            pressRow = controller.kb.row; pressIdx = controller.kb.index; pressUntil = SystemClock.uptimeMillis() + 110
            postInvalidateDelayed(115)
        }
        invalidate()
    }

    private fun show() {
        hiding = false
        visibility = VISIBLE
        openedAt = SystemClock.uptimeMillis()
        bakeShadows()
        aOpen.cancel(); aOpen.duration = OPEN_MS; aOpen.setFloatValues(openV, 1f); aOpen.start()
        requestFocus()
    }

    private fun hide() {
        aOpen.cancel()
        hiding = true
        aOpen.duration = (OPEN_MS * openV).toLong().coerceAtLeast(1)
        aOpen.setFloatValues(openV, 0f)
        aOpen.start()
    }

    private fun syncFocus(animate: Boolean) {
        keyFocus.set(if (controller.kb.active) controller.kb.row * 16 + controller.kb.index else -1, animate)
        rowFocus.set(if (controller.focusInList) controller.sel else -1, animate)
        if (controller.focusInList) ensureVisible()
    }

    private fun rebuildLabels() {
        val kb = controller.kb
        val rs = kb.rows
        labels = Array(rs.size) { ri -> Array(rs[ri].size) { i -> kb.label(rs[ri][i]) } }
    }

    private fun rebuildRows() {
        val res = controller.results
        val q = controller.query
        hasHeader = q.isEmpty()
        noneLayout = null
        if (q.isNotEmpty() && res.isEmpty()) {
            // Same block and style as the no-match text; an internal failure must not read as "no match".
            val msg = if (controller.failed) "Search isn\u2019t working right now. Close Search and open it again to retry."
                else "No apps or settings match \u201c$q\u201d"
            noneLayout = StaticLayout.Builder.obtain(msg, 0, msg.length, tNone, (ROW_W - 48).toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        }
        rows = Array(res.size) { i ->
            val e = res[i]
            val sug = e.kind == ResultKind.SUGGEST
            val tag = if (sug) "" else e.tag.uppercase()
            val tagW = if (tag.isEmpty()) 0f else tTag.measureText(tag)
            val avail = ROW_W - 40 - (if (sug) 40f else ICON_BOX) - 22 - (if (sug) 0f else tagW + 22)
            Row(e, TextUtils.ellipsize(e.title, tTitle, avail, TextUtils.TruncateAt.END).toString(),
                if (e.kind == ResultKind.SHORTCUT && e.subtitle.isNotEmpty()) TextUtils.ellipsize(e.subtitle, tSub, avail, TextUtils.TruncateAt.END).toString() else null,
                tag, tagW, if (e.kind == ResultKind.APP) SearchInitials.of(e.title) else "")
        }
        scroll = 0f; scrollTarget = 0f; aScroll.cancel()
        if (controller.focusInList) ensureVisible()
        requestIcons()
    }

    private fun ensureVisible() {
        val sel = controller.sel
        val top = (if (hasHeader) HDR_H else 0f) + sel * ROW_STRIDE
        val cur = scrollTarget
        var y = cur
        if (top - 36 < cur) y = maxOf(0f, top - 36) else if (top + ROW_H > cur + VIEW_H) y = top + ROW_H - VIEW_H + 8
        if (y != cur) {
            scrollFrom = scroll; scrollTarget = y
            aScroll.cancel(); aScroll.start()
            requestIcons()
        }
    }

    // ---- icons: LRU of pre-scaled bitmaps, loaded in the background for visible rows only ----
    private val iconCache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    private val iconMissing = HashSet<String>()
    private val iconPending = HashSet<String>()
    private var iconPx = 64

    private fun requestIcons() {
        val first = maxOf(0, ((scrollTarget - (if (hasHeader) HDR_H else 0f)) / ROW_STRIDE).toInt() - 1)
        val last = minOf(rows.size - 1, first + (VIEW_H / ROW_STRIDE).toInt() + 3)
        for (i in first..last) {
            val pkg = rows.getOrNull(i)?.res?.pkg ?: continue
            if (iconCache.get(pkg) != null || pkg in iconMissing || !iconPending.add(pkg)) continue
            val px = iconPx
            background.execute {
                val b = try { icons.loadIcon(pkg, px) } catch (t: Throwable) { null }
                post {
                    iconPending.remove(pkg)
                    if (b != null) iconCache.put(pkg, b) else { if (iconMissing.size > 512) iconMissing.clear(); iconMissing.add(pkg) }
                    invalidate()
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        iconPx = maxOf(32, Math.round(ICON_BOX * w / W))
        clip.reset()
        r.set(LEFT_X + 1, BODY_Y + 1, LEFT_X + LEFT_W - 1, BODY_Y + KB_H - 1)
        clip.addRoundRect(r, LEFT_R - 1, LEFT_R - 1, Path.Direction.CW)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        aOpen.cancel(); aScroll.cancel(); keyFocus.cancel(); rowFocus.cancel()
    }

    // ---- drawing ----
    override fun onDraw(c: Canvas) {
        if (openV <= 0f) return
        Trace.beginSection("Glass:searchDraw")
        try {
            val k = width / W
            c.save(); c.scale(k, k)
            val now = SystemClock.uptimeMillis()
            val el = openV * OPEN_MS
            val dim = EASE.getInterpolation(minOf(1f, el / 300f))
            pFill.shader = null; pFill.color = Color.argb((87 * dim).toInt(), 6, 8, 14)
            c.drawRect(0f, 0f, W, H, pFill)
            val p = EASE.getInterpolation(openV)
            val a = CSS_EASE.getInterpolation(minOf(1f, el / 320f))
            val sc = .965f + .035f * p
            c.save()
            c.translate(0f, (1 - p) * 32f)
            c.scale(sc, sc, PX + PW / 2, PY + PH / 2)
            val layer = a < 1f
            if (layer) c.saveLayerAlpha(PX - 200, PY - 200, PX + PW + 200, PY + PH + 300, (255 * a).toInt())
            drawPanel(c)
            drawBar(c, now)
            drawResults(c)
            drawKeyboard(c, now)
            drawHint(c)
            if (layer) c.restore()
            c.restore()
            c.restore()
        } finally { Trace.endSection() }
    }

    private fun drawPanel(c: Canvas) {
        r.set(PX, PY, PX + PW, PY + PH)
        drawShadow(c, shPanel, r, 36f, 66)
        val sh = if (dark) shDark else shLight
        if (sh != null) { pGlass.shader = sh; pGlass.colorFilter = saturate; c.drawRoundRect(r, PR, PR, pGlass); pGlass.shader = null; pGlass.colorFilter = null }
        else { pFill.shader = null; pFill.color = if (dark) 0xDB12141C.toInt() else 0xD6282C38.toInt(); c.drawRoundRect(r, PR, PR, pFill) }
        pFill.shader = null; pFill.color = if (dark) 0x700A0C12 else 0x4D181B24
        c.drawRoundRect(r, PR, PR, pFill)
        m.setTranslate(PX, PY); sheen.setLocalMatrix(m); pFill.shader = sheen
        c.drawRoundRect(r, PR, PR, pFill); pFill.shader = null
        pStroke.shader = null; pStroke.color = 0x33FFFFFF
        r.inset(.5f, .5f); c.drawRoundRect(r, PR - .5f, PR - .5f, pStroke)
        r.set(PX + 1, PY + 1, PX + PW - 1, PY + PH - 1)
        m.setTranslate(0f, PY + 1); topHi.setLocalMatrix(m); pStroke.shader = topHi
        c.drawRoundRect(r, PR - 1, PR - 1, pStroke); pStroke.shader = null
    }

    private fun drawBar(c: Canvas, now: Long) {
        r.set(BAR_X, BAR_Y, BAR_X + BAR_W, BAR_Y + BAR_H)
        m.setTranslate(0f, BAR_Y); gBar.setLocalMatrix(m); pFill.shader = gBar
        c.drawRoundRect(r, BAR_R, BAR_R, pFill); pFill.shader = null
        pStroke.color = 0x24FFFFFF; r.inset(.5f, .5f); c.drawRoundRect(r, BAR_R, BAR_R, pStroke)
        val cy = BAR_Y + BAR_H / 2
        drawIcon(c, "search", BAR_X + 38 + 21, cy, 42f, Color.WHITE, 204)
        val fx = BAR_X + 38 + 42 + 24
        val avail = BAR_X + BAR_W - 38 - fx - 3.7f
        val fm = fmField
        val base = cy - (fm.ascent + fm.descent) / 2
        if (queryText.isEmpty()) {
            tPlaceholder.color = 0x80FFFFFF.toInt()
            c.drawText("Search apps and settings", fx, base, tPlaceholder)
        } else {
            tField.color = Color.WHITE
            if (queryW > avail) { c.save(); c.clipRect(fx, cy - 40, fx + avail, cy + 40); c.drawText(queryText, fx, base, tField); c.restore() }
            else c.drawText(queryText, fx, base, tField)
        }
        val caretOn = ((now - openedAt) % 1050) < 525
        if (caretOn) {
            val cx = fx + minOf(queryW, avail) + 1.6f
            pFill.color = Color.WHITE; r.set(cx, cy - 23, cx + 2.8f, cy + 23); c.drawRoundRect(r, 2f, 2f, pFill)
        }
        if (!hiding && openV >= 1f) {
            val delay = 525 - ((now - openedAt) % 525)
            val s = width / W
            postInvalidateDelayed(delay, (fx * s).toInt(), ((cy - 30) * s).toInt(), ((fx + avail + 8) * s).toInt(), ((cy + 30) * s).toInt())
        }
    }

    private fun drawResults(c: Canvas) {
        r.set(LEFT_X, BODY_Y, LEFT_X + LEFT_W, BODY_Y + KB_H)
        m.setTranslate(0f, BODY_Y); gLeft.setLocalMatrix(m); pFill.shader = gLeft
        c.drawRoundRect(r, LEFT_R, LEFT_R, pFill); pFill.shader = null
        pStroke.color = 0x17FFFFFF; r.inset(.5f, .5f); c.drawRoundRect(r, LEFT_R, LEFT_R, pStroke)
        c.save(); c.clipPath(clip)
        val oy = LIST_Y - scroll
        var y = 0f
        if (hasHeader) {
            tHdr.color = 0xADFFFFFF.toInt()
            val fm = fmHdr
            c.drawText("SUGGESTIONS", ROW_X + 20, oy + 10 - fm.ascent, tHdr)
            y = HDR_H
        }
        val nl = noneLayout
        if (nl != null) { c.save(); c.translate(ROW_X + 24, oy + 28); nl.draw(c); c.restore() }
        for (i in rows.indices) {
            val top = oy + y + i * ROW_STRIDE
            if (top > BODY_Y + KB_H || top + ROW_H < BODY_Y) continue
            drawRow(c, rows[i], i, top)
        }
        c.restore()
    }

    private fun drawRow(c: Canvas, row: Row, i: Int, top: Float) {
        val f = rowFocus.amt(i)
        val sug = row.res.kind == ResultKind.SUGGEST
        c.save()
        if (f > 0f) {
            val s = 1f + .02f * f
            c.scale(s, s, ROW_X + ROW_W / 2, top + ROW_H / 2)
            r.set(ROW_X, top, ROW_X + ROW_W, top + ROW_H)
            drawShadow(c, shRow, r, 12f, (66 * f).toInt())
            m.setTranslate(0f, top); gRowFocus.setLocalMatrix(m); pFill.shader = gRowFocus; pFill.alpha = (255 * f).toInt()
            c.drawRoundRect(r, ROW_R, ROW_R, pFill); pFill.shader = null; pFill.alpha = 255
        }
        val fg = lerpColor(Color.WHITE, INK, f)
        val ib = if (sug) 40f else ICON_BOX
        val ix = ROW_X + 20; val iy = top + (ROW_H - ib) / 2
        if (!sug) {
            pFill.color = lerpColor(0x24FFFFFF, 0x14141620, f)
            r.set(ix, iy, ix + ib, iy + ib); c.drawRoundRect(r, ICON_R, ICON_R, pFill)
        }
        val res = row.res
        when {
            sug -> drawIcon(c, res.icon, ix + 20, iy + 20, 40f, fg, 204)
            res.kind == ResultKind.APP -> {
                val bmp = res.pkg?.let { iconCache.get(it) }
                if (bmp != null) { r.set(ix, iy, ix + ib, iy + ib); c.drawBitmap(bmp, null, r, pBmp) }
                else { tInit.color = fg; val fm = fmInit; c.drawText(row.initials, ix + ib / 2, iy + ib / 2 - (fm.ascent + fm.descent) / 2, tInit) }
            }
            else -> drawIcon(c, res.icon, ix + ib / 2, iy + ib / 2, ib * .6f, fg, 255)
        }
        val tx = ix + ib + 22
        tTitle.color = fg
        val f1 = fmTitle
        val l1 = f1.descent - f1.ascent + f1.leading
        if (row.sub != null) {
            tSub.color = fg; tSub.alpha = 158
            val f2 = fmSub
            val l2 = f2.descent - f2.ascent + f2.leading
            val bt = top + (ROW_H - (l1 + 1 + l2)) / 2
            c.drawText(row.title, tx, bt - f1.ascent, tTitle)
            c.drawText(row.sub, tx, bt + l1 + 1 - f2.ascent, tSub)
        } else c.drawText(row.title, tx, top + (ROW_H - l1) / 2 - f1.ascent, tTitle)
        if (!sug && row.tag.isNotEmpty()) {
            tTag.color = fg; tTag.alpha = 128
            val fm = fmTag
            c.drawText(row.tag, ROW_X + ROW_W - 20 - row.tagW, top + ROW_H / 2 - (fm.ascent + fm.descent) / 2, tTag)
        }
        c.restore()
    }

    private fun drawKeyboard(c: Canvas, now: Long) {
        val kb = controller.kb
        val rs = kb.rows
        for (pass in 0..1) for (ri in rs.indices) for (i in rs[ri].indices) {
            val amt = keyFocus.amt(ri * 16 + i)
            val raised = amt > 0f || (ri == pressRow && i == pressIdx && now < pressUntil)
            if ((pass == 1) != raised) continue
            drawKey(c, kb, rs[ri][i], ri, i, amt, now)
        }
    }

    private fun drawKey(c: Canvas, kb: SearchKeyboard, key: SearchKeyboard.Key, ri: Int, i: Int, amt: Float, now: Long) {
        val x = KB_X + kb.keyLeft(ri, i); val w = kb.keyWidth(ri, i); val y = BODY_Y + ri * KB_STRIDE; val h = SearchKeyboard.KEY_H
        val pressed = ri == pressRow && i == pressIdx && now < pressUntil
        val s = if (pressed) .97f else 1f + .1f * amt
        c.save()
        if (s != 1f) c.scale(s, s, x + w / 2, y + h / 2)
        r.set(x, y, x + w, y + h)
        if (amt > 0f) drawShadow(c, shKey, r, 16f, (87 * amt).toInt())
        val shiftOn = key.type == SearchKeyboard.Type.SHIFT && kb.shifted
        val base = if (shiftOn) gKeyShift else if (key.fn) gKeyFn else gKey
        m.setTranslate(0f, y); base.setLocalMatrix(m); pFill.shader = base
        c.drawRoundRect(r, 27f, 27f, pFill)
        pStroke.color = 0x1FFFFFFF; r.inset(.5f, .5f); c.drawRoundRect(r, 27f, 27f, pStroke); r.inset(-.5f, -.5f)
        if (amt > 0f) {
            gKeyFocus.setLocalMatrix(m); pFill.shader = gKeyFocus; pFill.alpha = (255 * amt).toInt()
            c.drawRoundRect(r, 27f, 27f, pFill); pFill.alpha = 255
            pStroke.color = Color.WHITE; pStroke.alpha = (255 * amt).toInt(); r.inset(.5f, .5f); c.drawRoundRect(r, 27f, 27f, pStroke); pStroke.alpha = 255
        }
        pFill.shader = null
        val fg = lerpColor(if (key.type == SearchKeyboard.Type.SPACE) 0xE6FFFFFF.toInt() else Color.WHITE, INK, if (shiftOn) 1f else amt)
        val cx = x + w / 2; val cy = y + h / 2
        if (key.icon != null) drawIcon(c, if (shiftOn) "shiftOn" else key.icon, cx, cy, 38f, fg, 255)
        else {
            val tp = if (key.fn || key.type == SearchKeyboard.Type.SPACE) tKeyFn else tKey
            tp.color = fg
            val fm = if (tp === tKey) fmKey else fmKeyFn
            c.drawText(labels[ri][i], cx, cy - (fm.ascent + fm.descent) / 2, tp)
        }
        c.restore()
    }

    private fun drawHint(c: Canvas) {
        val base = HINT_Y + 17f
        for (i in HINTS.indices) {
            val h = HINTS[i]
            tHintB.color = 0xE6FFFFFF.toInt(); c.drawText(h.first, hintX[i * 2], base, tHintB)
            tHint.color = 0x9EFFFFFF.toInt(); c.drawText(h.second, hintX[i * 2 + 1], base, tHint)
        }
    }

    /** Draws a 24x24 built-in icon centred at (cx, cy) with the given box size, following [color] like `currentColor`. */
    private fun drawIcon(c: Canvas, key: String, cx: Float, cy: Float, size: Float, color: Int, alpha: Int) {
        val kk = if (SvgIcons.ICONS.containsKey(key)) key else "settings"
        val shapes = SvgIcons.ICONS.getValue(kk)
        val paths = iconPaths.getValue(kk)
        c.save()
        c.translate(cx - size / 2, cy - size / 2); c.scale(size / 24f, size / 24f)
        val a = (Color.alpha(color) * alpha / 255f).toInt()
        pIcon.color = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        for (i in shapes.indices) {
            val sh = shapes[i]
            pIcon.style = if (sh.fill) Paint.Style.FILL else Paint.Style.STROKE
            pIcon.pathEffect = if (sh.dash != null) dashEffect else null
            c.drawPath(paths[i], pIcon)
        }
        pIcon.pathEffect = null
        c.restore()
    }
}
