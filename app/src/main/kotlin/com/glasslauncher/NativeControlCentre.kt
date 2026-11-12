package com.glasslauncher

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.KeyEvent

/**
 * Control Centre (S4) drawn natively: controlcentre.css geometry (right-hand panel 800 px wide, 32 px inset, 52 px
 * radius, 4-column grid, slide-in over 420 ms) with every tile of controlcentre.js. Rules and texts live in
 * [CcLogic]; state is read on the worker by [ControlCentreController] and arrives through the set* methods below
 * (UI thread), which also pre-wrap the subtitles so [draw] never formats or allocates.
 */
class NativeControlCentre(private val home: NativeHomeView) {
    companion object {
        const val PW = 800f
        const val INSET = 32f
        const val PR = 52f
        const val TILE_R = 36f
        private const val GRID_X = 38f      // border 1 + padding 32 + grid padding 5
        private const val GRID_TOP = 163f
        private const val PAD_X = 26f
        private const val CHIP = 70f
        private const val TXT_GAP = 22f
        private const val DIM = .58f
        private const val BIG = 0
        private const val WIDE = 1
        private const val FULL = 2
        private const val MINI = 3
        private val CLASS = intArrayOf(BIG, WIDE, WIDE, FULL, FULL, MINI, MINI, MINI, MINI)
        private val SUB_LINES = intArrayOf(1, 1, 1, 2, 2, 0, 0, 0, 0)
    }

    var isOpen = false
    var sel = 0
    var selAmt = 1f

    @JvmField var sun: Bitmap? = null
    @JvmField var moon: Bitmap? = null

    // tile state: written and read on the UI thread only
    private val tiles = arrayOf(
        CcTile("", "", false, CcIcon.THEME), CcTile("", "…", false, CcIcon.WIFI_OFF), CcTile("", "…", false, CcIcon.BLUETOOTH_OFF),
        CcTile("", "…", false, CcIcon.VOLUME), CcTile("", "…", false, CcIcon.DEVICE), CcTile("", "", false, CcIcon.SETTINGS),
        CcTile("", "", false, CcIcon.VOLUME), CcTile("", "", false, CcIcon.DISPLAY), CcTile("", "", false, CcIcon.SEARCH),
    )
    private val subLines = Array(CcLogic.COUNT) { arrayOf("") }
    private var volPct = -1
    private var volMuted = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrim = Paint()
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val semi: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val reg: Typeface = NativeHomeView.REGULAR
    private val panelSheen = LinearGradient(0f, 0f, PW * .82f, 1016f * .57f, 0x21FFFFFF, 0x0EFFFFFF, Shader.TileMode.CLAMP)
    private val tileSheen = LinearGradient(0f, 0f, 300f, 210f, 0x25FFFFFF, 0x11FFFFFF, Shader.TileMode.CLAMP)
    private val focusSheen = LinearGradient(0f, 0f, 300f, 210f, 0x4AFFFFFF, 0x29FFFFFF, Shader.TileMode.CLAMP)
    private val lm = Matrix()
    private val r = RectF()
    private var aSel: ValueAnimator? = null
    private var tintColor = 0
    private var tintFilter: PorterDuffColorFilter? = null

    init {
        stroke.style = Paint.Style.STROKE
        scrim.shader = LinearGradient(0f, 0f, NativeHomeView.W, 0f, intArrayOf(0x00060810, 0x00060810, 0x57060810), floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP)
        for (i in 0 until CcLogic.COUNT) layout(i)
    }

    // ---------------- state (UI thread) ----------------
    fun setTile(i: Int, t: CcTile) { tiles[i] = t; layout(i); home.invalidate() }

    fun setVolume(v: CcVolume) {
        volPct = if (v.available) v.pct else -1
        volMuted = v.muted
        setTile(CcLogic.T_VOL, v.tile)
    }

    /** Sound / Display dim when this box has no such settings page (registry.hasSettings). */
    fun setShortcutDim(i: Int, dim: Boolean) {
        val t = tiles[i]
        if (t.dim != dim) setTile(i, CcTile(t.value, t.sub, t.on, t.icon, dim))
    }

    private fun subWidth(i: Int): Float = when (CLASS[i]) {
        BIG -> CcLogic.tileW(i) - 60f
        else -> CcLogic.tileW(i) - PAD_X - (PAD_X + CHIP + TXT_GAP)
    }

    private fun layout(i: Int) {
        if (SUB_LINES[i] == 0) return
        text.typeface = reg; text.textSize = 20f
        subLines[i] = wrap(tiles[i].sub, subWidth(i), SUB_LINES[i])
    }

    /** Greedy word wrap into at most [maxLines] lines; the last line is ellipsized. */
    private fun wrap(s: String, maxW: Float, maxLines: Int): Array<String> {
        if (s.isEmpty()) return arrayOf("")
        val out = ArrayList<String>(maxLines)
        var rest = s
        while (rest.isNotEmpty() && out.size < maxLines) {
            if (text.measureText(rest) <= maxW) { out.add(rest); rest = ""; break }
            if (out.size == maxLines - 1) { out.add(ellipsize(rest, maxW)); rest = ""; break }
            var n = text.breakText(rest, true, maxW, null)
            val sp = rest.lastIndexOf(' ', n - 1)
            if (sp > 0) n = sp
            out.add(rest.substring(0, n).trimEnd())
            rest = rest.substring(n).trimStart()
        }
        return out.toTypedArray()
    }

    private fun ellipsize(s: String, max: Float): String {
        if (text.measureText(s) <= max) return s
        var e: String = s
        while (e.length > 1 && text.measureText("$e…") > max) e = e.substring(0, e.length - 1)
        return "$e…"
    }

    // ---------------- input ----------------
    fun open(focus: Int = 0) {
        isOpen = true; sel = focus; selAmt = 1f
        home.host.controlCentreAction("open", 0)
    }

    fun onKey(code: Int, e: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_BACK -> { home.closeControlCentre(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> return move(CcLogic.NAV[sel][CcLogic.UP])
            KeyEvent.KEYCODE_DPAD_DOWN -> return move(CcLogic.NAV[sel][CcLogic.DOWN])
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (sel == CcLogic.T_VOL) { home.host.controlCentreAction("vol", -1); return true }
                return move(CcLogic.NAV[sel][CcLogic.LEFT])
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (sel == CcLogic.T_VOL) { home.host.controlCentreAction("vol", 1); return true }
                return move(CcLogic.NAV[sel][CcLogic.RIGHT])
            }
            KeyEvent.KEYCODE_TAB -> { selectNav(CcLogic.tab(sel, if (e.isShiftPressed) -1 else 1)); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (e.repeatCount > 0) return true      // holding OK must not toggle repeatedly
                home.host.playClick()
                if (sel == CcLogic.T_SRCH) home.closeControlCentre()
                home.host.controlCentreAction(CcLogic.IDS[sel], 0)
                return true
            }
            // hardware volume keys belong to the system; the activity re-reads the volume ~250 ms later
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return false
            else -> return true     // the panel is modal: nothing reaches the home screen behind it
        }
    }

    private fun move(to: Int): Boolean {
        if (to >= 0 && to != sel) selectNav(to)
        return true
    }

    private fun selectNav(to: Int) {
        sel = to
        home.host.playNav()
        aSel = home.anim(aSel, 0f, 1f, 180, NativeHomeView.EASE) { selAmt = it }
    }

    // ---------------- drawing ----------------
    fun draw(c: Canvas, mix: Float) {
        scrim.alpha = (255 * minOf(1f, mix * 1.2f)).toInt()
        c.drawRect(0f, 0f, NativeHomeView.W, NativeHomeView.H, scrim)
        val x = NativeHomeView.W - INSET - PW + (1f - mix) * (PW + INSET + 60)
        val y = INSET
        val h = NativeHomeView.H - 2 * INSET
        c.save()
        c.translate(x, y)
        r.set(0f, 0f, PW, h)
        home.drawBlurBackdrop(c, r, PR, x, y, mix)   // controlcentre.css backdrop-filter: blur(1.5rem) saturate(1.5)
        fill.shader = null; fill.color = Color.argb((168 * mix).toInt(), 25, 29, 40)
        c.drawRoundRect(r, PR, PR, fill)
        fill.shader = panelSheen; fill.alpha = (255 * mix).toInt()
        c.drawRoundRect(r, PR, PR, fill)
        fill.shader = null
        stroke.strokeWidth = 1f; stroke.color = Color.argb((48 * mix).toInt(), 255, 255, 255)
        c.drawRoundRect(r, PR, PR, stroke)

        text.textAlign = Paint.Align.RIGHT; text.typeface = semi; text.color = Color.WHITE; text.alpha = (255 * mix).toInt()
        text.textSize = 72f
        c.drawText(home.ccTime, PW - 32 - 18, 30f + 64, text)
        text.typeface = reg; text.textSize = 23f; text.color = 0xADFFFFFF.toInt(); text.alpha = (173 * mix).toInt()
        c.drawText(home.date, PW - 32 - 18, 30f + 64 + 36, text)

        for (i in 0 until CcLogic.COUNT) {
            drawTile(c, i, GRID_X + CcLogic.tileX(i), GRID_TOP + CcLogic.tileY(i), CcLogic.tileW(i), CcLogic.tileH(i),
                if (isOpen && i == sel) selAmt else 0f, mix)
        }
        c.restore()
    }

    private fun base(y: Float, lineH: Float, size: Float): Float = y + lineH / 2f + size * .35f

    private fun drawTile(c: Canvas, i: Int, x: Float, y: Float, w: Float, h: Float, f: Float, mix: Float) {
        val t = tiles[i]
        val a = if (t.dim) mix * DIM else mix
        val s = 1f + .018f * f
        c.save()
        c.scale(s, s, x + w / 2f, y + h / 2f)
        r.set(x, y, x + w, y + h)
        lm.setTranslate(x, y)
        tileSheen.setLocalMatrix(lm); focusSheen.setLocalMatrix(lm)
        fill.shader = tileSheen; fill.alpha = (255 * a * (1f - f)).toInt()
        c.drawRoundRect(r, TILE_R, TILE_R, fill)
        if (f > 0f) { fill.shader = focusSheen; fill.alpha = (255 * a * f).toInt(); c.drawRoundRect(r, TILE_R, TILE_R, fill) }
        fill.shader = null
        stroke.strokeWidth = 1f; stroke.color = Color.argb(((38 + 49 * f) * a).toInt(), 255, 255, 255)
        c.drawRoundRect(r, TILE_R, TILE_R, stroke)

        val cls = CLASS[i]
        val chip = when (cls) { BIG -> 92f; MINI -> 60f; else -> CHIP }
        val chipA = if (f > 0f) 43 + 44 * f else if (t.on) 64f else 43f    // .17 / .on .25 / focused .34
        val isz = when (cls) { BIG -> 22.5f; MINI -> 15.5f; else -> 18f }     // half of the 45 / 31 / 36 px icon box
        val ccx: Float
        val ccy: Float
        val txX: Float
        val txW: Float
        val nameSize: Float
        when (cls) {
            BIG -> { ccx = x + 30 + chip / 2; ccy = y + 30 + chip / 2; txX = x + 30; txW = w - 60; nameSize = 28f }
            MINI -> {
                val top = y + (h - 92.8f) / 2f
                ccx = x + w / 2f; ccy = top + chip / 2; txX = x + w / 2f; txW = w - 16; nameSize = 19f
            }
            else -> { ccx = x + PAD_X + chip / 2; ccy = y + h / 2f; txX = x + PAD_X + CHIP + TXT_GAP; txW = w - PAD_X - (txX - x); nameSize = 25f }
        }
        fill.color = Color.argb((chipA * a).toInt(), 255, 255, 255)
        c.drawCircle(ccx, ccy, chip / 2, fill)
        drawIcon(c, t.icon, ccx, ccy, isz, a)

        val subA = (173 + 26 * f) * a                  // .68 -> .78 when focused
        val nameA = (245 + 10 * f) * a
        text.textAlign = Paint.Align.LEFT
        when (cls) {
            BIG -> {
                val bottom = y + h - 30
                val subY = bottom - 25f
                setText(reg, 20f, subA); c.drawText(subLines[i][0], txX, base(subY, 25f, 20f), text)
                val nameTop = subY - 3.6f - 33.6f
                setText(semi, nameSize, nameA); c.drawText(CcLogic.TITLES[i], txX, base(nameTop, 33.6f, nameSize), text)
            }
            WIDE -> {
                val total = 30f + 2f + 24.5f + 3.6f + 25f
                val top = y + (h - total) / 2f
                setText(semi, nameSize, nameA); c.drawText(CcLogic.TITLES[i], txX, base(top, 30f, nameSize), text)
                setText(semi, 20.4f, 209 * a); c.drawText(t.value, txX, base(top + 32f, 24.5f, 20.4f), text)
                setText(reg, 20f, subA); c.drawText(subLines[i][0], txX, base(top + 32f + 24.5f + 3.6f, 25f, 20f), text)
            }
            FULL -> {
                val hasBar = i == CcLogic.T_VOL
                val lines = subLines[i]
                val total = 30f + (if (hasBar) 26f else 0f) + 3.6f + 25f * lines.size
                val top = y + (h - total) / 2f
                setText(semi, nameSize, nameA); c.drawText(CcLogic.TITLES[i], txX, base(top, 30f, nameSize), text)
                if (t.value.isNotEmpty()) {
                    text.textAlign = Paint.Align.RIGHT
                    setText(semi, 20.4f, 209 * a); c.drawText(t.value, x + w - PAD_X, base(top, 30f, nameSize), text)
                    text.textAlign = Paint.Align.LEFT
                }
                var ly = top + 30f
                if (hasBar) {
                    val by = ly + 11f
                    fill.color = Color.argb((56 * a).toInt(), 255, 255, 255)
                    c.drawRoundRect(txX, by, txX + txW, by + 12f, 6f, 6f, fill)
                    val pct = if (volMuted || volPct < 0) 0 else volPct
                    if (pct > 0) {
                        fill.color = Color.argb((224 * a).toInt(), 255, 255, 255)
                        c.drawRoundRect(txX, by, txX + txW * pct / 100f, by + 12f, 6f, 6f, fill)
                    }
                    ly += 26f
                }
                ly += 3.6f
                setText(reg, 20f, subA)
                for (k in lines.indices) c.drawText(lines[k], txX, base(ly + 25f * k, 25f, 20f), text)
            }
            else -> {
                text.textAlign = Paint.Align.CENTER
                val top = y + (h - 92.8f) / 2f
                setText(semi, nameSize, nameA)
                c.drawText(CcLogic.TITLES[i], txX, base(top + 70f, 22.8f, nameSize), text)
            }
        }
        c.restore()
    }

    private fun setText(tf: Typeface, size: Float, alpha: Float) {
        text.typeface = tf; text.textSize = size; text.color = Color.WHITE; text.alpha = alpha.toInt().coerceIn(0, 255)
    }

    private fun drawIcon(c: Canvas, icon: Int, cx: Float, cy: Float, s: Float, a: Float) {
        val ink = Color.WHITE
        when (icon) {
            CcIcon.WIFI -> NativeIcons.wifi(c, cx, cy, s, ink, a, true)
            CcIcon.WIFI_OFF -> NativeIcons.wifi(c, cx, cy, s, ink, a, false)
            CcIcon.ETHERNET -> NativeIcons.ethernet(c, cx, cy, s, ink, a)
            CcIcon.BLUETOOTH -> NativeIcons.bluetooth(c, cx, cy, s, ink, a, true)
            CcIcon.BLUETOOTH_OFF -> NativeIcons.bluetooth(c, cx, cy, s, ink, a, false)
            CcIcon.VOLUME -> NativeIcons.speaker(c, cx, cy, s, ink, a, false)
            CcIcon.VOLUME_MUTED -> NativeIcons.speaker(c, cx, cy, s, ink, a, true)
            CcIcon.SETTINGS -> NativeIcons.gear(c, cx, cy, s, ink, a)
            CcIcon.SEARCH -> NativeIcons.search(c, cx, cy, s, ink, a)
            CcIcon.THEME -> {
                val b = if (home.dark) moon else sun
                if (b != null) {
                    if (tintFilter == null || tintColor != ink) { tintColor = ink; tintFilter = NativeHomeView.tint(ink) }
                    bmp.colorFilter = tintFilter; bmp.alpha = (255 * a).toInt()
                    r.set(cx - s, cy - s, cx + s, cy + s); c.drawBitmap(b, null, r, bmp)
                }
            }
            else -> NativeIcons.device(c, cx, cy, s, ink, a)     // device and display share the rectangle-on-stand glyph
        }
    }
}
