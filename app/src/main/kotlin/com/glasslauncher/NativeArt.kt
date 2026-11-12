package com.glasslauncher

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Bitmap preparation for the native UI prototype. Everything here runs once per image on a worker thread so the
 * draw pass only blits bitmaps that already have their display size and rounded corners.
 */
object NativeArt {
    /** Decodes an asset at the smallest power-of-two sample that stays >= the target, or null. */
    @JvmStatic
    fun decode(am: AssetManager, path: String, reqW: Int, reqH: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        am.open(path).use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / (s * 2) >= reqW && o.outHeight / (s * 2) >= reqH) s *= 2
        val d = BitmapFactory.Options().apply { inSampleSize = s }
        am.open(path).use { BitmapFactory.decodeStream(it, null, d) }
    } catch (t: Throwable) {
        null
    }

    /**
     * Card artwork at exactly w x h with rounded corners, like `.tile img{object-fit:cover;border-radius:inherit}`.
     * The 800x480 sources are decoded at 400x240 and scaled once, instead of drawing a 2.9x oversampled image per frame.
     */
    @JvmStatic
    fun card(am: AssetManager, path: String, w: Int, h: Int, radius: Float, bg: Int = 0xFF222222.toInt(), forceSquare: Boolean = false): Bitmap? {
        val src = decode(am, path, w, h) ?: return null
        return fit(src, w, h, radius, bg, forceSquare)
    }

    /** Decodes encoded image bytes (e.g. AppDiscovery.iconPng) at the smallest sample that stays >= the target, or null. */
    @JvmStatic
    fun decodeBytes(b: ByteArray, reqW: Int, reqH: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= reqW && o.outHeight / (s * 2) >= reqH) s *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (t: Throwable) {
        null
    }

    /**
     * home.js `.tile` / recent.css `#rthumb`: [src] cover-cropped to w x h with rounded corners; when [forceSquare] or the
     * image is narrower than 1.3:1 (`.sq`) it is instead contained at 74% of the height on [bg]. Recycles [src].
     */
    @JvmStatic
    fun fit(src: Bitmap, w: Int, h: Int, radius: Float, bg: Int, forceSquare: Boolean): Bitmap {
        val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fc = Canvas(flat)
        val sp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (HomeArt.squareFit(src.width, src.height, forceSquare)) {
            fc.drawColor(bg)
            val dh = h * 0.74f
            val dw = dh * src.width / src.height
            fc.drawBitmap(src, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), sp)
        } else {
            val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)  // cover
            val dw = src.width * scale
            val dh = src.height * scale
            fc.drawBitmap(src, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), sp)
        }
        src.recycle()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = BitmapShader(flat, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        Canvas(out).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, p)
        flat.recycle()
        return out
    }

    /** `#wpd.fallback{filter:brightness(.4) saturate(.8)}`: one filtered copy, made at load time (never per frame). */
    @JvmStatic
    fun dimmedFallback(src: Bitmap): Bitmap {
        val m = ColorMatrix().apply { setSaturation(.8f) }
        m.preConcat(ColorMatrix().apply { setScale(.4f, .4f, .4f, 1f) })   // brightness first, then saturate, as in the CSS
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }

    /** Small blurred copy of the wallpaper, drawn scaled up behind glass panels (backdrop-filter is unavailable on API 30). */
    @JvmStatic
    fun blurred(wall: Bitmap, w: Int, h: Int, radius: Int): Bitmap {
        val small = Bitmap.createScaledBitmap(wall, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { boxH(px, w, h, radius); boxV(px, w, h, radius) }
        val out = if (small.isMutable) small else small.copy(Bitmap.Config.ARGB_8888, true)
        if (out !== small) small.recycle()
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxH(px: IntArray, w: Int, h: Int, r: Int) {
        val row = IntArray(w)
        for (y in 0 until h) {
            val o = y * w
            System.arraycopy(px, o, row, 0, w)
            for (x in 0 until w) px[o + x] = avg(row, maxOf(0, x - r), minOf(w - 1, x + r))
        }
    }

    private fun boxV(px: IntArray, w: Int, h: Int, r: Int) {
        val col = IntArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) col[y] = px[y * w + x]
            for (y in 0 until h) px[y * w + x] = avg(col, maxOf(0, y - r), minOf(h - 1, y + r))
        }
    }

    private fun avg(a: IntArray, from: Int, to: Int): Int {
        var r = 0
        var g = 0
        var b = 0
        for (i in from..to) {
            val c = a[i]
            r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
        }
        val n = to - from + 1
        return 0xff000000.toInt() or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }

    /** Pre-rendered soft drop shadow for a rounded rect (BlurMaskFilter is software-only, so it is baked once). */
    @JvmStatic
    fun shadow(w: Int, h: Int, radius: Float, blur: Float, argb: Int): Bitmap {
        val pad = Math.ceil((blur * 2).toDouble()).toInt()
        val out = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ALPHA_8)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = argb
        p.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat()), radius, radius, p)
        return out
    }
}
