package com.glasslauncher

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.KeyEvent

/**
 * Control Centre panel for the native prototype (controlcentre.css geometry: right-hand panel 800 px wide with a
 * 32 px inset, 52 px radius, 4-column grid, slide-in over 420 ms). The prototype carries the six most used tiles;
 * state comes from SystemControls on NativeHomeActivity's worker thread, never from the draw pass.
 */
class NativeControlCentre(private val home: NativeHomeView) {
    companion object {
        const val PW = 800f
        const val INSET = 32f
        const val PR = 52f
        const val PAD_X = 32f
        const val PAD_TOP = 30f
        const val GRID_GAP = 20f
        const val TILE_R = 36f

        val IDS = arrayOf("wifi", "bluetooth", "volume", "theme", "settings", "device")

        /** column, row, column span */
        val GRID = arrayOf(intArrayOf(0, 0, 2), intArrayOf(2, 0, 2), intArrayOf(0, 1, 4), intArrayOf(0, 2, 2), intArrayOf(2, 2, 2), intArrayOf(0, 3, 4))
        val ROW_H = floatArrayOf(152f, 152f, 160f, 140f)
    }

    var isOpen = false
    var sel = 0
    var selAmt = 1f

    // live state (set from the worker thread, read on the UI thread)
    @Volatile var wifiTitle = "Wi-Fi"
    @Volatile var wifiSub = "…"
    @Volatile var btTitle = "Bluetooth"
    @Volatile var btSub = "…"
    @Volatile var volSub = "…"
    @Volatile var deviceSub = "…"
    @Volatile var wifiOn = false
    @Volatile var btOn = false
    @Volatile var muted = false
    @Volatile var vol = -1
    @Volatile var volMax = 15
    @JvmField var sun: Bitmap? = null
    @JvmField var moon: Bitmap? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrim = Paint()
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val semi: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val reg: Typeface = NativeHomeView.REGULAR
    private val panelSheen = LinearGradient(0f, 0f, PW * .82f, 1016f * .57f, 0x21FFFFFF, 0x0EFFFFFF, Shader.TileMode.CLAMP)
    private val tileSheen = LinearGradient(0f, 0f, 300f, 210f, 0x25FFFFFF, 0x11FFFFFF, Shader.TileMode.CLAMP)
    private val lm = Matrix()
    private val r = RectF()
    private var aSel: ValueAnimator? = null

    init {
        stroke.style = Paint.Style.STROKE
        scrim.shader = LinearGradient(0f, 0f, NativeHomeView.W, 0f, intArrayOf(0x00060810, 0x00060810, 0x57060810), floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP)
    }

    fun open() {
        isOpen = true; sel = 0; selAmt = 1f
        home.host.controlCentreAction("refresh", 0)
    }

    fun onKey(code: Int, e: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_BACK -> { home.closeControlCentre(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> return move(nearest(sel, -1))
            KeyEvent.KEYCODE_DPAD_DOWN -> return move(nearest(sel, 1))
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (IDS[sel] == "volume") { home.host.controlCentreAction("volume", -1); return true }
                return move(if (GRID[sel][0] > 0) sel - 1 else -1)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (IDS[sel] == "volume") { home.host.controlCentreAction("volume", 1); return true }
                return move(if (GRID[sel][0] + GRID[sel][2] < 4 && sel + 1 < IDS.size && GRID[sel + 1][1] == GRID[sel][1]) sel + 1 else -1)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (e.repeatCount > 0) return true
                home.host.playClick()
                home.host.controlCentreAction(IDS[sel], 0)
                return true
            }
            else -> return true     // the panel is modal: nothing reaches the home screen behind it
        }
    }

    /** Spatial up/down: the tile in the next grid row whose column range overlaps the current one, left-most first. */
    private fun nearest(from: Int, dir: Int): Int {
        val row = GRID[from][1] + dir
        val c0 = GRID[from][0]
        val c1 = c0 + GRID[from][2]
        for (i in IDS.indices) {
            if (GRID[i][1] != row) continue
            val a0 = GRID[i][0]
            val a1 = a0 + GRID[i][2]
            if (a0 < c1 && a1 > c0) return i
        }
        return -1
    }

    private fun move(to: Int): Boolean {
        if (to < 0 || to == sel) return true
        sel = to
        home.host.playNav()
        aSel = home.anim(aSel, 0f, 1f, 180, NativeHomeView.EASE) { selAmt = it }
        return true
    }

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
        c.drawText(home.ccTime, PW - PAD_X - 18, PAD_TOP + 64, text)
        text.typeface = reg; text.textSize = 23f; text.color = 0xADFFFFFF.toInt(); text.alpha = (173 * mix).toInt()
        c.drawText(home.date, PW - PAD_X - 18, PAD_TOP + 64 + 36, text)

        val gridTop = PAD_TOP + 64 + 36 + 34
        val innerW = PW - 2 * PAD_X
        val colW = (innerW - 3 * GRID_GAP) / 4f
        for (i in IDS.indices) {
            var ty = gridTop
            for (rr in 0 until GRID[i][1]) ty += ROW_H[rr] + GRID_GAP
            val tx = PAD_X + GRID[i][0] * (colW + GRID_GAP)
            val tw = GRID[i][2] * colW + (GRID[i][2] - 1) * GRID_GAP
            val th = ROW_H[GRID[i][1]]
            drawTile(c, i, tx, ty, tw, th, if (isOpen && i == sel) selAmt else 0f, mix)
        }
        c.restore()
    }

    private fun drawTile(c: Canvas, i: Int, x: Float, y: Float, w: Float, h: Float, f: Float, mix: Float) {
        val s = 1f + .018f * f
        c.save()
        c.scale(s, s, x + w / 2f, y + h / 2f)
        r.set(x, y, x + w, y + h)
        lm.setTranslate(x, y); tileSheen.setLocalMatrix(lm)
        fill.shader = tileSheen; fill.alpha = (255 * mix * (1f - f)).toInt()
        c.drawRoundRect(r, TILE_R, TILE_R, fill)
        fill.shader = null
        if (f > 0f) { fill.color = Color.argb((245 * f * mix).toInt(), 243, 245, 250); c.drawRoundRect(r, TILE_R, TILE_R, fill) }
        stroke.strokeWidth = 1f; stroke.color = Color.argb((38 * mix).toInt(), 255, 255, 255)
        c.drawRoundRect(r, TILE_R, TILE_R, stroke)

        val ink = NativeHomeView.blend(0xFFFFFFFF.toInt(), 0xFF14161C.toInt(), f)
        val chipX = x + 26 + 35
        val chipY = y + h / 2f
        fill.color = NativeHomeView.blend(0x2BFFFFFF, 0x1F14161C, f); fill.alpha = (Color.alpha(fill.color) * mix).toInt()
        c.drawCircle(chipX, chipY, 35f, fill)
        val id = IDS[i]
        val t1: String
        val t2: String
        when (id) {
            "wifi" -> { NativeIcons.wifi(c, chipX, chipY, 18f, ink, mix, wifiOn); t1 = wifiTitle; t2 = wifiSub }
            "bluetooth" -> { NativeIcons.bluetooth(c, chipX, chipY, 18f, ink, mix, btOn); t1 = btTitle; t2 = btSub }
            "volume" -> { NativeIcons.speaker(c, chipX, chipY, 18f, ink, mix, muted); t1 = "Volume"; t2 = volSub }
            "theme" -> {
                val b = if (home.dark) moon else sun
                if (b != null) {
                    bmp.colorFilter = NativeHomeView.tint(ink); bmp.alpha = (255 * mix).toInt()
                    r.set(chipX - 18, chipY - 18, chipX + 18, chipY + 18); c.drawBitmap(b, null, r, bmp)
                }
                t1 = "Appearance"; t2 = if (home.dark) "Dark" else "Light"
            }
            "settings" -> { NativeIcons.gear(c, chipX, chipY, 18f, ink, mix); t1 = "Settings"; t2 = "Android TV Settings" }
            else -> { NativeIcons.device(c, chipX, chipY, 18f, ink, mix); t1 = "About this box"; t2 = deviceSub }
        }
        text.textAlign = Paint.Align.LEFT; text.typeface = semi; text.textSize = 25f; text.color = ink; text.alpha = (Color.alpha(ink) * mix).toInt()
        val tx = chipX + 35 + 22
        c.drawText(t1, tx, chipY - 4, text)
        text.typeface = reg; text.textSize = 20f; text.color = NativeHomeView.blend(0xADFFFFFF.toInt(), 0xB314161C.toInt(), f)
        text.alpha = (Color.alpha(text.color) * mix).toInt()
        c.drawText(ellipsize(t2, x + w - tx - 20), tx, chipY + 24, text)
        val v = vol
        if (id == "volume" && v >= 0) {
            val bx = x + w - 330
            val by = chipY - 4
            val bw = 290f
            fill.color = NativeHomeView.blend(0x40FFFFFF, 0x3314161C, f); c.drawRoundRect(bx, by - 5, bx + bw, by + 5, 5f, 5f, fill)
            fill.color = ink; c.drawRoundRect(bx, by - 5, bx + bw * (if (muted) 0f else v / maxOf(1, volMax).toFloat()), by + 5, 5f, 5f, fill)
        }
        c.restore()
    }

    private fun ellipsize(s: String?, max: Float): String {
        if (s == null) return ""
        if (text.measureText(s) <= max) return s
        var e: String = s
        while (e.length > 1 && text.measureText("$e…") > max) e = e.substring(0, e.length - 1)
        return "$e…"
    }
}
