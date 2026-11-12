package com.glasslauncher;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import java.io.InputStream;

/**
 * Bitmap preparation for the native UI prototype. Everything here runs once per image on a worker thread so the
 * draw pass only blits bitmaps that already have their display size and rounded corners.
 */
final class NativeArt {
    private NativeArt() {}

    /** Decodes an asset at the smallest power-of-two sample that stays >= the target, or null. */
    static Bitmap decode(AssetManager am, String path, int reqW, int reqH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = am.open(path)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1;
            while (o.outWidth / (s * 2) >= reqW && o.outHeight / (s * 2) >= reqH) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            try (InputStream in = am.open(path)) { return BitmapFactory.decodeStream(in, null, d); }
        } catch (Throwable t) { return null; }
    }

    /**
     * Card artwork at exactly w x h with rounded corners, like `.tile img{object-fit:cover;border-radius:inherit}`.
     * The 800x480 sources are decoded at 400x240 and scaled once, instead of drawing a 2.9x oversampled image per frame.
     */
    static Bitmap card(AssetManager am, String path, int w, int h, float radius) {
        Bitmap src = decode(am, path, w, h);
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());  // cover
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader sh = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        m.postTranslate((w - src.getWidth() * scale) / 2f, (h - src.getHeight() * scale) / 2f);
        sh.setLocalMatrix(m);
        p.setShader(sh);
        c.drawRoundRect(new RectF(0, 0, w, h), radius, radius, p);
        src.recycle();
        return out;
    }

    /** Small blurred copy of the wallpaper, drawn scaled up behind glass panels (backdrop-filter is unavailable on API 30). */
    static Bitmap blurred(Bitmap wall, int w, int h, int radius) {
        Bitmap small = Bitmap.createScaledBitmap(wall, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) { boxH(px, w, h, radius); boxV(px, w, h, radius); }
        Bitmap out = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        if (out != small) small.recycle();
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static void boxH(int[] px, int w, int h, int r) {
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int o = y * w;
            System.arraycopy(px, o, row, 0, w);
            for (int x = 0; x < w; x++) px[o + x] = avg(row, Math.max(0, x - r), Math.min(w - 1, x + r), 1, 0);
        }
    }

    private static void boxV(int[] px, int w, int h, int r) {
        int[] col = new int[h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) col[y] = px[y * w + x];
            for (int y = 0; y < h; y++) px[y * w + x] = avg(col, Math.max(0, y - r), Math.min(h - 1, y + r), 1, 0);
        }
    }

    private static int avg(int[] a, int from, int to, int step, int base) {
        int r = 0, g = 0, b = 0, n = 0;
        for (int i = from; i <= to; i += step) { int c = a[base + i]; r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++; }
        return 0xff000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
    }

    /** Pre-rendered soft drop shadow for a rounded rect (BlurMaskFilter is software-only, so it is baked once). */
    static Bitmap shadow(int w, int h, float radius, float blur, int argb) {
        int pad = (int) Math.ceil(blur * 2);
        Bitmap out = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(argb);
        p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(new RectF(pad, pad, pad + w, pad + h), radius, radius, p);
        return out;
    }
}
