package com.glasslauncher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/** Vector icons drawn with one shared Paint/Path (UI thread only, nothing allocated per call). */
object NativeIcons {
    private val P = Paint(Paint.ANTI_ALIAS_FLAG)
    private val PATH = Path()

    fun search(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Float) {
        stroke(color, alpha, s * .2f)
        c.drawCircle(cx - s * .15f, cy - s * .15f, s * .55f, P)
        c.drawLine(cx + s * .25f, cy + s * .25f, cx + s * .75f, cy + s * .75f, P)
    }

    fun sliders(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Float) {
        stroke(color, alpha, s * .16f)
        for (i in -1..1) c.drawLine(cx - s * .8f, cy + i * s * .55f, cx + s * .8f, cy + i * s * .55f, P)
        P.style = Paint.Style.FILL
        c.drawCircle(cx - s * .35f, cy - s * .55f, s * .2f, P)
        c.drawCircle(cx + s * .35f, cy, s * .2f, P)
        c.drawCircle(cx - s * .1f, cy + s * .55f, s * .2f, P)
    }

    fun wifi(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Float, on: Boolean) {
        stroke(color, alpha * (if (on) 1f else .45f), s * .16f)
        for (i in 1..3) {
            val rr = s * .33f * i
            c.drawArc(cx - rr, cy + s * .55f - rr, cx + rr, cy + s * .55f + rr, 225f, 90f, false, P)
        }
        if (!on) c.drawLine(cx - s * .8f, cy - s * .7f, cx + s * .8f, cy + s * .7f, P)
    }

    fun bluetooth(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Float, on: Boolean) {
        stroke(color, alpha * (if (on) 1f else .45f), s * .14f)
        PATH.reset()
        PATH.moveTo(cx - s * .45f, cy - s * .4f); PATH.lineTo(cx + s * .4f, cy + s * .35f); PATH.lineTo(cx, cy + s * .75f)
        PATH.lineTo(cx, cy - s * .75f); PATH.lineTo(cx + s * .4f, cy - s * .35f); PATH.lineTo(cx - s * .45f, cy + s * .4f)
        c.drawPath(PATH, P)
    }

    fun speaker(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Float, muted: Boolean) {
        P.style = Paint.Style.FILL; P.color = color; P.alpha = (Color.alpha(color) * alpha).toInt()
        PATH.reset()
        PATH.moveTo(cx - s * .8f, cy - s * .25f); PATH.lineTo(cx - s * .4f, cy - s * .25f); PATH.lineTo(cx, cy - s * .65f)
        PATH.lineTo(cx, cy + s * .65f); PATH.lineTo(cx - s * .4f, cy + s * .25f); PATH.lineTo(cx - s * .8f, cy + s * .25f); PATH.close()
        c.drawPath(PATH, P)
        stroke(color, alpha, s * .13f)
        if (muted) {
            c.drawLine(cx + s * .25f, cy - s * .3f, cx + s * .8f, cy + s * .3f, P)
            c.drawLine(cx + s * .8f, cy - s * .3f, cx + s * .25f, cy + s * .3f, P)
        } else c.drawArc(cx - s * .2f, cy - s * .5f, cx + s * .6f, cy + s * .5f, -60f, 120f, false, P)
    }

    fun gear(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Float) {
        stroke(color, alpha, s * .16f)
        c.drawCircle(cx, cy, s * .38f, P)
        for (i in 0 until 8) {
            val a = Math.PI * i / 4
            c.drawLine(cx + Math.cos(a).toFloat() * s * .5f, cy + Math.sin(a).toFloat() * s * .5f,
                cx + Math.cos(a).toFloat() * s * .75f, cy + Math.sin(a).toFloat() * s * .75f, P)
        }
    }

    fun device(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Float) {
        stroke(color, alpha, s * .14f)
        c.drawRoundRect(cx - s * .8f, cy - s * .5f, cx + s * .8f, cy + s * .35f, s * .12f, s * .12f, P)
        c.drawLine(cx - s * .35f, cy + s * .7f, cx + s * .35f, cy + s * .7f, P)
    }

    /** icons.js ethernet: 24-unit box, 1 unit = s / 12. */
    fun ethernet(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Float) {
        val u = s / 12f
        stroke(color, alpha, 2f * u * .75f)
        c.drawRoundRect(cx + (5 - 12) * u, cy + (3 - 12) * u, cx + (19 - 12) * u, cy + (13 - 12) * u, 1.5f * u, 1.5f * u, P)
        c.drawLine(cx - 3 * u, cy + 1 * u, cx - 3 * u, cy + 4 * u, P)
        c.drawLine(cx + 3 * u, cy + 1 * u, cx + 3 * u, cy + 4 * u, P)
        c.drawLine(cx, cy + 4 * u, cx, cy + 9 * u, P)
        c.drawLine(cx - 4 * u, cy - 4 * u, cx - 2 * u, cy - 4 * u, P)
        c.drawLine(cx + 2 * u, cy - 4 * u, cx + 4 * u, cy - 4 * u, P)
    }

    private fun stroke(color: Int, alpha: Float, w: Float) {
        P.style = Paint.Style.STROKE; P.strokeCap = Paint.Cap.ROUND; P.strokeJoin = Paint.Join.ROUND
        P.strokeWidth = w; P.color = color; P.alpha = (Color.alpha(color) * alpha).toInt()
    }
}
