package com.glasslauncher

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The built-in icons Search uses (js/icons.js), as 24x24 path data. Pure Kotlin: [parse] turns SVG path data into
 * move/line/cubic/close commands (arcs become cubics) which NativeSearchView replays into android.graphics.Path once.
 * Stroked icons use width 1.8, round caps/joins; [Shape.fill] shapes are filled and unstroked (class="fill").
 */
object SvgIcons {
    const val MOVE = 0f; const val LINE = 1f; const val CUBIC = 2f; const val CLOSE = 3f

    class Shape(val cmds: FloatArray, val fill: Boolean = false, val dash: FloatArray? = null)

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"
    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) = "M${cx - rx} ${cy}a$rx $ry 0 1 0 ${2 * rx} 0a$rx $ry 0 1 0 ${-2 * rx} 0z"

    private fun s(d: String, fill: Boolean = false, dash: FloatArray? = null) = Shape(parse(d), fill, dash)
    private val MONITOR = listOf(s(rect(3f, 4.5f, 18f, 12f, 2f)), s("M9 20h6M12 16.5V20"))
    private val WIFI = listOf("M2 9a15 15 0 0 1 20 0", "M5 12.5a10.5 10.5 0 0 1 14 0", "M8.5 16a5.5 5.5 0 0 1 7 0")

    val ICONS: Map<String, List<Shape>> = mapOf(
        "search" to listOf(s(circle(11f, 11f, 6.5f)), s("M16 16l5 5")),
        "controls" to listOf(s(rect(3f, 4f, 18f, 7f, 3.5f)), s(circle(7.5f, 7.5f, 2f), true), s(rect(3f, 13f, 18f, 7f, 3.5f)), s(circle(16.5f, 16.5f, 2f), true)),
        "wifi" to WIFI.map { s(it) } + s(circle(12f, 19.5f, 1.2f), true),
        "bluetooth" to listOf(s("M6.5 7.5l11 9-5.5 4.5V3l5.5 4.5-11 9")),
        "volume" to listOf(s("M4 9v6h4l5 4V5L8 9H4z"), s("M16 9a4 4 0 0 1 0 6"), s("M18.5 6.5a8 8 0 0 1 0 11")),
        "themeDark" to listOf(s("M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5z")),
        "device" to MONITOR,
        "display" to MONITOR,
        "settings" to listOf(s(circle(12f, 12f, 3f)), s(circle(12f, 12f, 7.2f)), s(circle(12f, 12f, 9.4f), false, floatArrayOf(2.6f, 3.1f))),
        "apps" to listOf(s(rect(4f, 4f, 6.5f, 6.5f, 1.5f)), s(rect(13.5f, 4f, 6.5f, 6.5f, 1.5f)), s(rect(4f, 13.5f, 6.5f, 6.5f, 1.5f)), s(rect(13.5f, 13.5f, 6.5f, 6.5f, 1.5f))),
        "storage" to listOf(s(ellipse(12f, 6f, 7.5f, 3f)), s("M4.5 6v12c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3V6M4.5 12c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3")),
        "clock" to listOf(s(circle(12f, 12f, 9f)), s("M12 7v5l3.5 2")),
        "keyboard" to listOf(s(rect(2.5f, 6f, 19f, 12f, 2f)), s("M6 10h.5M10 10h.5M14 10h.5M18 10h.5M7 14h10")),
        "backspace" to listOf(s("M9.2 5.5H20a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5H9.2L2.8 12z"), s("M12.8 9.6l5 4.8M17.8 9.6l-5 4.8")),
        "shift" to listOf(s("M12 4.2l7.8 8.3h-4.6v6.3H8.8v-6.3H4.2z")),
        "shiftOn" to listOf(s("M12 4.2l7.8 8.3h-4.6v6.3H8.8v-6.3H4.2z", true)),
        "access" to listOf(s(circle(12f, 4.5f, 1.6f)), s("M5 8.5l7 1.5 7-1.5M12 10v5l-3 6M12 15l3 6")),
    )

    /** G.icons.svg(key): unknown keys fall back to the settings icon. */
    fun shapes(key: String): List<Shape> = ICONS[key] ?: ICONS.getValue("settings")

    private class Cursor(val d: String) {
        var i = 0
        fun skip() { while (i < d.length && (d[i] == ' ' || d[i] == ',' || d[i] == '\n' || d[i] == '\t')) i++ }
        fun atNumber(): Boolean { skip(); if (i >= d.length) return false; val c = d[i]; return c == '-' || c == '+' || c == '.' || c in '0'..'9' }
        fun number(): Float {
            skip()
            val st = i
            if (i < d.length && (d[i] == '-' || d[i] == '+')) i++
            var dot = false
            while (i < d.length) { val c = d[i]; if (c in '0'..'9') i++ else if (c == '.' && !dot) { dot = true; i++ } else break }
            return d.substring(st, i).toFloat()
        }
    }

    /** SVG path data (M L H V C S A Z, absolute and relative) -> command stream: MOVE x y | LINE x y | CUBIC x1 y1 x2 y2 x y | CLOSE. */
    fun parse(d: String): FloatArray {
        val out = ArrayList<Float>()
        val c = Cursor(d)
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
        var lcx = 0f; var lcy = 0f; var prevCubic = false
        var cmd = ' '
        c.skip()
        while (c.i < d.length) {
            val ch = d[c.i]
            if (ch.isLetter()) { cmd = ch; c.i++ } else if (cmd == ' ') error("bad path")
            val rel = cmd.isLowerCase()
            var cubic = false
            when (cmd.uppercaseChar()) {
                'Z' -> { out.add(CLOSE); x = sx; y = sy }
                'M' -> {
                    var first = true
                    while (c.atNumber()) {
                        var nx = c.number(); var ny = c.number()
                        if (rel) { nx += x; ny += y }
                        if (first) { out.add(MOVE); sx = nx; sy = ny; first = false } else out.add(LINE)
                        out.add(nx); out.add(ny); x = nx; y = ny
                    }
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> while (c.atNumber()) { var nx = c.number(); var ny = c.number(); if (rel) { nx += x; ny += y }; out.add(LINE); out.add(nx); out.add(ny); x = nx; y = ny }
                'H' -> while (c.atNumber()) { var nx = c.number(); if (rel) nx += x; out.add(LINE); out.add(nx); out.add(y); x = nx }
                'V' -> while (c.atNumber()) { var ny = c.number(); if (rel) ny += y; out.add(LINE); out.add(x); out.add(ny); y = ny }
                'C' -> while (c.atNumber()) {
                    var a = c.number(); var b = c.number(); var e = c.number(); var f = c.number(); var g = c.number(); var h = c.number()
                    if (rel) { a += x; b += y; e += x; f += y; g += x; h += y }
                    out.add(CUBIC); out.add(a); out.add(b); out.add(e); out.add(f); out.add(g); out.add(h)
                    lcx = e; lcy = f; x = g; y = h; cubic = true
                }
                'S' -> while (c.atNumber()) {
                    var e = c.number(); var f = c.number(); var g = c.number(); var h = c.number()
                    if (rel) { e += x; f += y; g += x; h += y }
                    val a = if (prevCubic) 2 * x - lcx else x
                    val b = if (prevCubic) 2 * y - lcy else y
                    out.add(CUBIC); out.add(a); out.add(b); out.add(e); out.add(f); out.add(g); out.add(h)
                    lcx = e; lcy = f; x = g; y = h; cubic = true; prevCubic = true
                }
                'A' -> while (c.atNumber()) {
                    val rx = c.number(); val ry = c.number(); val rot = c.number(); val large = c.number() != 0f; val sweep = c.number() != 0f
                    var nx = c.number(); var ny = c.number()
                    if (rel) { nx += x; ny += y }
                    arc(out, x, y, rx, ry, rot, large, sweep, nx, ny); x = nx; y = ny
                }
                else -> error("unsupported path command $cmd")
            }
            prevCubic = cubic
        }
        return out.toFloatArray()
    }

    /** SVG endpoint arc -> cubic Béziers (SVG 1.1 appendix F.6). */
    private fun arc(out: MutableList<Float>, x1: Float, y1: Float, rx0: Float, ry0: Float, rotDeg: Float, large: Boolean, sweep: Boolean, x2: Float, y2: Float) {
        var rx = abs(rx0.toDouble()); var ry = abs(ry0.toDouble())
        if (x1 == x2 && y1 == y2) return
        if (rx == 0.0 || ry == 0.0) { out.add(LINE); out.add(x2); out.add(y2); return }
        val phi = Math.toRadians(rotDeg.toDouble()); val cp = cos(phi); val sp = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val x1p = cp * dx + sp * dy; val y1p = -sp * dx + cp * dy
        val lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lam > 1) { val sl = sqrt(lam); rx *= sl; ry *= sl }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var co = sqrt(maxOf(0.0, num / den)); if (large == sweep) co = -co
        val cxp = co * rx * y1p / ry; val cyp = -co * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2.0; val cy = sp * cxp + cp * cyp + (y1 + y2) / 2.0
        val th1 = atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
        var dth = atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - th1
        if (sweep && dth < 0) dth += 2 * Math.PI else if (!sweep && dth > 0) dth -= 2 * Math.PI
        val n = ceil(abs(dth) / (Math.PI / 2) - 1e-9).toInt().coerceAtLeast(1)
        val step = dth / n
        val t = 4.0 / 3.0 * tan(step / 4)
        var a = th1
        for (k in 0 until n) {
            val b = a + step
            val ca = cos(a); val sa = sin(a); val cb = cos(b); val sb = sin(b)
            fun px(ex: Double, ey: Double) = (cp * rx * ex - sp * ry * ey + cx).toFloat()
            fun py(ex: Double, ey: Double) = (sp * rx * ex + cp * ry * ey + cy).toFloat()
            out.add(CUBIC)
            out.add(px(ca - t * sa, sa + t * ca)); out.add(py(ca - t * sa, sa + t * ca))
            out.add(px(cb + t * sb, sb - t * cb)); out.add(py(cb + t * sb, sb - t * cb))
            if (k == n - 1) { out.add(x2); out.add(y2) } else { out.add(px(cb, sb)); out.add(py(cb, sb)) }
            a = b
        }
    }
}
