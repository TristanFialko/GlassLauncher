package com.glasslauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Trace;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Native prototype of the Glass home screen: one custom View that draws the whole screen (wallpaper, clock, two glass
 * docks of cards, header buttons, Control Centre panel, toast). Geometry is the WebView layout at 1920x1080 in device
 * pixels (docs/nativeui/PARITY_CHECKLIST.md); other sizes scale uniformly. Nothing here touches PackageManager, disk or
 * JNI: artwork, installed apps and Control Centre state arrive from NativeHomeActivity's worker thread.
 */
final class NativeHomeView extends View {
    interface Host {
        void launch(NativeHomeConfig.Card card);
        void openSettings(String kind);
        void controlCentreAction(String id, int delta);
        void playNav();
        void playClick();
        void exitPrototype();
    }

    // ---- geometry (px at 1920x1080) ----
    static final float W = 1920f, H = 1080f;
    static final float TILE_W = 280, TILE_H = 168, TILE_R = 22, GAP = 32, PAD_X = 72, PAD_Y = 32;
    static final float DOCK_W = 1674, DOCK_H = 234, DOCK_R = 48, ROW_GAP = 48, ROW0_TOP = 461;
    static final float LABEL_SIZE = 23, LABEL_BLOCK = 42;          // .rowLabel 1.15rem + .7rem margin
    static final float ROWS_SHIFT = 303, HEAD_LIFT = 85, HEAD_SCALE = 0.6f;
    static final float BTN_R = 34, BTN_Y = 94, BTN_SEARCH_X = 1726, BTN_CC_X = 1814;
    static final PathInterpolator EASE = new PathInterpolator(.2f, .8f, .2f, 1f);   // --ease

    final NativeHomeConfig cfg;
    final Host host;
    final NativeControlCentre cc;

    // ---- state ----
    boolean dark;
    int zone;            // 0 rows, 1 header
    int vpos, hdrIdx;
    final int[] col;
    Bitmap wallLight, wallDark, blurLight, blurDark;
    final Bitmap[][] artLight, artDark;
    Bitmap dockShadow, tileShadow;
    String clock = "", date = "";
    String toast; long toastUntil;

    // ---- animated values ----
    float darkMix, scroll, focusAmt = 1f, shine = 1f, btnAmt = 1f, ccMix, toastMix;
    int focusRow = 0, focusCol = 0, prevRow = -1, prevCol = -1;
    private ValueAnimator aDark, aScroll, aFocus, aShine, aBtn, aCc, aToast;

    private final Paint pBmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGlass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint pShadow = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pShade = new Paint();
    private final Paint pSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pShine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Matrix m = new Matrix();
    private final ColorMatrixColorFilter saturate;
    private LinearGradient clockGrad;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE d MMMM", Locale.UK), ccTimeFmt = new SimpleDateFormat("H:mm", Locale.UK);
    String ccTime = "";
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL), BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    // shaders are built once and positioned with local matrices: nothing is allocated per frame
    private BitmapShader blurShLight, blurShDark;
    private final LinearGradient dockSheen = new LinearGradient(0, 0, DOCK_W * .707f, DOCK_W * .707f,
            new int[]{0x42FFFFFF, 0x17FFFFFF, 0x0AFFFFFF, 0x26FFFFFF}, new float[]{0f, .36f, .62f, 1f}, Shader.TileMode.CLAMP);
    private final LinearGradient shineGrad = new LinearGradient(0, 0, TILE_W * .906f, TILE_H * .423f,
            new int[]{0x00FFFFFF, 0x00FFFFFF, 0x59FFFFFF, 0x00FFFFFF, 0x00FFFFFF}, new float[]{0f, .35f, .5f, .65f, 1f}, Shader.TileMode.CLAMP);
    private final Matrix lm = new Matrix();

    NativeHomeView(Context ctx, NativeHomeConfig cfg, Host host) {
        super(ctx);
        this.cfg = cfg; this.host = host;
        int n = cfg.rows.size();
        col = new int[n];
        artLight = new Bitmap[n][]; artDark = new Bitmap[n][];
        for (int i = 0; i < n; i++) { artLight[i] = new Bitmap[cfg.rows.get(i).cards.size()]; artDark[i] = new Bitmap[cfg.rows.get(i).cards.size()]; }
        cc = new NativeControlCentre(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        ColorMatrix cm = new ColorMatrix(); cm.setSaturation(1.55f);
        saturate = new ColorMatrixColorFilter(cm);
        pStroke.setStyle(Paint.Style.STROKE);
        pText.setTypeface(REGULAR);
        pShade.setShader(new LinearGradient(0, 0, 0, H, new int[]{0x1F000000, 0x00000000, 0x80000000}, new float[]{0f, .35f, 1f}, Shader.TileMode.CLAMP));
        dark = isNight(Calendar.getInstance());
        darkMix = dark ? 1f : 0f;
        tick();
    }

    static boolean isNight(Calendar c) { int h = c.get(Calendar.HOUR_OF_DAY); return h >= 19 || h < 7; }   // theme.js

    /** main.js tick(): 12-hour h:mm without AM/PM, en-GB long date; theme flips only when day/night changes. */
    void tick() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR) == 0 ? 12 : c.get(Calendar.HOUR);
        clock = h + ":" + String.format(Locale.ROOT, "%02d", c.get(Calendar.MINUTE));
        date = dateFmt.format(c.getTime());
        ccTime = ccTimeFmt.format(c.getTime());
        boolean night = isNight(c);
        if (night != autoNight) { autoNight = night; setDark(night); }
        invalidate();
    }
    private boolean autoNight = isNight(Calendar.getInstance());

    void setDark(boolean d) {
        if (d == dark) return;
        dark = d;
        aDark = run(aDark, darkMix, d ? 1f : 0f, 700, null, v -> darkMix = v);
    }

    // ---------------- input ----------------
    @Override public boolean onKeyDown(int code, KeyEvent e) {
        Trace.beginSection("Glass:nativeKey");
        try {
            if (cc.open) return cc.onKey(code, e) || super.onKeyDown(code, e);
            if (code == KeyEvent.KEYCODE_BACK) { host.exitPrototype(); return true; }
            if (zone == 0) return rowsKey(code, e) || super.onKeyDown(code, e);
            return headerKey(code, e) || super.onKeyDown(code, e);
        } finally { Trace.endSection(); }
    }

    private boolean rowsKey(int code, KeyEvent e) {
        List<NativeHomeConfig.Card> cards = cfg.rows.get(vpos).cards;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (col[vpos] > 0) { col[vpos]--; moved(); } return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (col[vpos] < cards.size() - 1) { col[vpos]++; moved(); } return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (vpos < cfg.rows.size() - 1) { int c = col[vpos]; vpos++; col[vpos] = Math.min(c, cfg.rows.get(vpos).cards.size() - 1); moved(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (vpos > 0) { int c = col[vpos]; vpos--; col[vpos] = Math.min(c, cfg.rows.get(vpos).cards.size() - 1); moved(); }
                else { zone = 1; hdrIdx = 0; moved(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() > 0) return true;                  // main.js: held OK never repeats outside Search
                host.playClick(); host.launch(cards.get(col[vpos])); return true;
            default: return false;
        }
    }

    private boolean headerKey(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (hdrIdx > 0) { hdrIdx--; moved(); } return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (hdrIdx < 1) { hdrIdx++; moved(); } return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: zone = 0; vpos = 0; moved(); return true;
            case KeyEvent.KEYCODE_DPAD_UP: return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() > 0) return true;
                host.playClick();
                if (hdrIdx == 0) showToast("Search is not part of the native prototype yet");
                else openControlCentre();
                return true;
            default: return false;
        }
    }

    /** Focus changed: sound, focus/halo animation, shine sweep and the row scroll, all vsync-driven. */
    void moved() {
        host.playNav();
        if (zone == 0) {
            prevRow = focusRow; prevCol = focusCol; focusRow = vpos; focusCol = col[vpos];
            aFocus = run(aFocus, 0f, 1f, 220, EASE, v -> focusAmt = v);
            aShine = run(aShine, 0f, 1f, 700, null, v -> shine = v);
        }
        aBtn = run(aBtn, 0f, 1f, 200, EASE, v -> btnAmt = v);
        float target = (zone == 0 && vpos > 0) ? 1f : 0f;
        if (target != scroll && (aScroll == null || !aScroll.isRunning() || aScrollTarget != target)) {
            aScrollTarget = target;
            aScroll = run(aScroll, scroll, target, 450, EASE, v -> scroll = v);
        }
        invalidate();
    }
    private float aScrollTarget;

    void openControlCentre() { cc.open(); aCc = run(aCc, ccMix, 1f, 420, EASE, v -> ccMix = v); }
    void closeControlCentre() { cc.open = false; aCc = run(aCc, ccMix, 0f, 420, EASE, v -> ccMix = v); host.playNav(); }

    void showToast(String text) {
        toast = text; toastUntil = System.currentTimeMillis() + 3200;
        aToast = run(aToast, toastMix, 1f, 300, null, v -> toastMix = v);
        postDelayed(() -> { if (System.currentTimeMillis() >= toastUntil) aToast = run(aToast, toastMix, 0f, 300, null, v -> toastMix = v); }, 3200);
    }

    interface Setter { void set(float v); }
    ValueAnimator run(ValueAnimator old, float from, float to, long ms, android.animation.TimeInterpolator ip, Setter s) {
        if (old != null) old.cancel();
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(ms);
        if (ip != null) a.setInterpolator(ip);
        a.addUpdateListener(x -> { s.set((float) x.getAnimatedValue()); invalidate(); });
        a.start();
        return a;
    }

    // ---------------- drawing ----------------
    @Override protected void onDraw(Canvas c) {
        Trace.beginSection("Glass:nativeDraw");
        try {
            float k = getWidth() / W;
            c.save();
            c.scale(k, k);
            drawBackdrop(c);
            drawHead(c);
            drawButtons(c);
            drawRows(c);
            if (ccMix > 0f) cc.draw(c, ccMix);
            drawToast(c);
            c.restore();
        } finally { Trace.endSection(); }
    }

    private void drawBackdrop(Canvas c) {
        c.drawColor(0xFF0B0B14);
        if (wallLight != null && darkMix < 1f) { r.set(0, 0, W, H); c.drawBitmap(wallLight, null, r, pBmp); }
        if (wallDark != null && darkMix > 0f) {
            pBmp.setAlpha((int) (255 * darkMix)); r.set(0, 0, W, H); c.drawBitmap(wallDark, null, r, pBmp); pBmp.setAlpha(255);
        }
        c.drawRect(0, 0, W, H, pShade);
    }

    private void drawHead(Canvas c) {
        float s = 1f + (HEAD_SCALE - 1f) * scroll;
        float alpha = (1f - 0.45f * scroll) * (1f - 0.7f * ccMix);           // .scrolled .55, ccopen .3
        c.save();
        c.translate(0, -HEAD_LIFT * scroll);
        c.scale(s, s, W / 2f, 0);
        pText.setTextAlign(Paint.Align.CENTER);
        pText.setShader(null);
        pText.setColor(Color.WHITE);
        pText.setAlpha((int) (255 * .9f * alpha));
        pText.setTextSize(30); pText.setFakeBoldText(true);
        pText.setShadowLayer(12, 0, 2, 0x59000000);
        c.drawText(date, W / 2f, 54 + 30, pText);
        pText.clearShadowLayer();
        pText.setTextSize(240);
        pText.setTypeface(BOLD); pText.setFakeBoldText(false);
        pText.setLetterSpacing(-.02f);
        if (clockGrad == null) clockGrad = new LinearGradient(0, 90, 0, 90 + 264,
                new int[]{0xF5FFFFFF, 0xF5FFFFFF, 0x2EFFFFFF}, new float[]{0f, .10f, .95f}, Shader.TileMode.CLAMP);
        pText.setShader(clockGrad);
        pText.setAlpha((int) (255 * alpha));
        c.drawText(clock, W / 2f, 90 + 12 + 223, pText);
        pText.setShader(null); pText.setLetterSpacing(0); pText.setTypeface(REGULAR);
        c.restore();
    }

    private void drawButtons(Canvas c) {
        float vis = 1f - ccMix;
        if (vis <= 0f) return;
        for (int i = 0; i < 2; i++) {
            boolean f = zone == 1 && hdrIdx == i;
            float amt = f ? btnAmt : 0f;
            float cx = i == 0 ? BTN_SEARCH_X : BTN_CC_X, rad = BTN_R * (1f + .1f * amt);
            r.set(cx - rad, BTN_Y - rad, cx + rad, BTN_Y + rad);
            pFill.setShader(null);
            pFill.setColor(blend(0x8028283A, 0xF7F3F5FA, amt)); pFill.setAlpha((int) (Color.alpha(pFill.getColor()) * vis));
            c.drawOval(r, pFill);
            pStroke.setStrokeWidth(1); pStroke.setColor(blend(0x33FFFFFF, 0xFFFFFFFF, amt)); pStroke.setAlpha((int) (Color.alpha(pStroke.getColor()) * vis));
            c.drawOval(r, pStroke);
            int ink = blend(0xFFFFFFFF, 0xFF14161C, amt);
            if (i == 0) NativeIcons.search(c, cx, BTN_Y, 16 * (1f + .1f * amt), ink, vis);
            else NativeIcons.sliders(c, cx, BTN_Y, 16 * (1f + .1f * amt), ink, vis);
        }
    }

    private void drawRows(Canvas c) {
        float y = ROW0_TOP - ROWS_SHIFT * scroll;
        float dim = 1f - 0.0f * ccMix;
        Bitmap blur = darkMix >= .5f ? blurDark : blurLight;
        for (int ri = 0; ri < cfg.rows.size(); ri++) {
            NativeHomeConfig.Row row = cfg.rows.get(ri);
            if (row.title != null && !row.title.isEmpty() && ri > 0) {
                pText.setTextAlign(Paint.Align.LEFT); pText.setTextSize(LABEL_SIZE); pText.setFakeBoldText(true);
                pText.setColor(Color.WHITE); pText.setAlpha((int) (255 * (zone == 0 && vpos == ri ? 1f : .65f) * dim));
                pText.setShadowLayer(10, 0, 2, 0x66000000);
                c.drawText(row.title, (W - DOCK_W) / 2f + 32, y + LABEL_SIZE * .95f, pText);
                pText.clearShadowLayer(); pText.setFakeBoldText(false);
                y += LABEL_BLOCK;
            }
            float left = (W - DOCK_W) / 2f;
            drawGlass(c, left, y, DOCK_W, DOCK_H, DOCK_R, blur);
            int n = row.cards.size();
            float tilesW = n * TILE_W + (n - 1) * GAP;
            float tx = (W - tilesW) / 2f, ty = y + 1 + PAD_Y;
            // unfocused cards first, the focused one last so its scaled halo sits on top (z-index:3)
            for (int ci = 0; ci < n; ci++) if (!(zone == 0 && vpos == ri && col[ri] == ci)) drawCard(c, ri, ci, tx + ci * (TILE_W + GAP), ty, 0f);
            if (zone == 0 && vpos == ri) drawCard(c, ri, col[ri], tx + col[ri] * (TILE_W + GAP), ty, focusAmt);
            y += DOCK_H + ROW_GAP;
        }
    }

    /** .glass: blurred + saturated backdrop, tint, 135deg sheen, hairline edge, soft shadow (baked). */
    void drawGlass(Canvas c, float x, float y, float w, float h, float rad, Bitmap blur) {
        if (dockShadow != null && w == DOCK_W && h == DOCK_H) {
            pShadow.setColor(0x42000000);
            float pad = (dockShadow.getWidth() - w) / 2f;
            c.drawBitmap(dockShadow, x - pad, y - pad + 36, pShadow);
        }
        r.set(x, y, x + w, y + h);
        if (blur != null) {
            BitmapShader sh = blur == blurDark ? blurShDark : blurShLight;
            if (sh == null) {
                sh = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                lm.setScale(W / blur.getWidth(), H / blur.getHeight());
                sh.setLocalMatrix(lm);
                if (blur == blurDark) blurShDark = sh; else blurShLight = sh;
            }
            pGlass.setShader(sh); pGlass.setColorFilter(saturate);
            c.drawRoundRect(r, rad, rad, pGlass);
            pGlass.setShader(null); pGlass.setColorFilter(null);
        }
        pFill.setShader(null);
        pFill.setColor(darkMix >= .5f ? 0x700A0C12 : 0x4D181B24);
        c.drawRoundRect(r, rad, rad, pFill);
        lm.setTranslate(x, y); dockSheen.setLocalMatrix(lm);
        pSheen.setShader(dockSheen);
        c.drawRoundRect(r, rad, rad, pSheen);
        pStroke.setStrokeWidth(1); pStroke.setColor(0x33FFFFFF);
        r.inset(.5f, .5f);
        c.drawRoundRect(r, rad, rad, pStroke);
    }

    private void drawCard(Canvas c, int ri, int ci, float x, float y, float f) {
        float s = 1f + .06f * f;
        c.save();
        c.scale(s, s, x + TILE_W / 2f, y + TILE_H / 2f);
        if (f > 0f) {
            // .slot.f::before halo (lite: flat white .3, no blur) and the card's shadow
            r.set(x - 7, y - 7, x + TILE_W + 7, y + TILE_H + 7);
            pFill.setShader(null); pFill.setColor(Color.argb((int) (255 * .3f * f), 255, 255, 255));
            c.drawRoundRect(r, 29, 29, pFill);
            pStroke.setStrokeWidth(2.4f); pStroke.setColor(Color.argb((int) (255 * .85f * f), 255, 255, 255));
            c.drawRoundRect(r, 29, 29, pStroke);
            if (tileShadow != null) {
                pShadow.setColor(Color.argb((int) (255 * .45f * f), 0, 0, 0));
                float pad = (tileShadow.getWidth() - TILE_W) / 2f;
                c.drawBitmap(tileShadow, x - pad, y - pad + 16, pShadow);
            }
        }
        Bitmap art = (darkMix >= .5f ? artDark : artLight)[ri][ci];
        if (art == null) art = (darkMix >= .5f ? artLight : artDark)[ri][ci];
        if (art != null) c.drawBitmap(art, x, y, pBmp);
        else {
            r.set(x, y, x + TILE_W, y + TILE_H);
            pFill.setColor(0xFF222222); c.drawRoundRect(r, TILE_R, TILE_R, pFill);
            pText.setTextAlign(Paint.Align.CENTER); pText.setTextSize(32); pText.setFakeBoldText(true); pText.setColor(Color.WHITE);
            c.drawText(NativeHomeConfig.initials(cfg.rows.get(ri).cards.get(ci).name), x + TILE_W / 2f, y + TILE_H / 2f + 11, pText);
            pText.setFakeBoldText(false);
        }
        if (f > 0f && shine < 1f) {
            // .tile::after: a 115deg light band swept from -120% to +120% of the card width in 700 ms
            float off = (-1.2f + 2.4f * shine) * TILE_W;
            lm.setTranslate(x + off, y); shineGrad.setLocalMatrix(lm);
            pShine.setShader(shineGrad);
            r.set(x, y, x + TILE_W, y + TILE_H);
            c.drawRoundRect(r, TILE_R, TILE_R, pShine);
        }
        c.restore();
    }

    private void drawToast(Canvas c) {
        if (toast == null || toastMix <= 0f) return;
        pText.setTextAlign(Paint.Align.CENTER); pText.setTextSize(22); pText.setColor(Color.WHITE);
        float tw = pText.measureText(toast);
        r.set(W / 2f - tw / 2f - 36, H / 2f - 31, W / 2f + tw / 2f + 36, H / 2f + 31);
        pFill.setShader(null); pFill.setColor(Color.argb((int) (209 * toastMix), 24, 27, 36));
        c.drawRoundRect(r, 28, 28, pFill);
        pText.setAlpha((int) (255 * toastMix));
        c.drawText(toast, W / 2f, H / 2f + 8, pText);
    }

    static int blend(int a, int b, float t) {
        return Color.argb((int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t), (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t), (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** White silhouette of a black-on-transparent icon (UI_ICONS invert:true), for moonsun/*.png. */
    static PorterDuffColorFilter tint(int color) { return new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN); }

    /** Focused card index for tests: row, column (or -1, header index when the header has focus). */
    int[] focusForTest() { return zone == 0 ? new int[]{vpos, col[vpos]} : new int[]{-1, hdrIdx}; }

    static final class NativeIcons {
        private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
        private static final Path PATH = new Path();
        static void search(Canvas c, float cx, float cy, float s, int color, float alpha) {
            stroke(color, alpha, s * .2f);
            c.drawCircle(cx - s * .15f, cy - s * .15f, s * .55f, P);
            c.drawLine(cx + s * .25f, cy + s * .25f, cx + s * .75f, cy + s * .75f, P);
        }
        static void sliders(Canvas c, float cx, float cy, float s, int color, float alpha) {
            stroke(color, alpha, s * .16f);
            for (int i = -1; i <= 1; i++) c.drawLine(cx - s * .8f, cy + i * s * .55f, cx + s * .8f, cy + i * s * .55f, P);
            P.setStyle(Paint.Style.FILL);
            c.drawCircle(cx - s * .35f, cy - s * .55f, s * .2f, P); c.drawCircle(cx + s * .35f, cy, s * .2f, P); c.drawCircle(cx - s * .1f, cy + s * .55f, s * .2f, P);
        }
        static void wifi(Canvas c, float cx, float cy, float s, int color, float alpha, boolean on) {
            stroke(color, alpha * (on ? 1f : .45f), s * .16f);
            for (int i = 1; i <= 3; i++) { float rr = s * .33f * i; c.drawArc(cx - rr, cy + s * .55f - rr, cx + rr, cy + s * .55f + rr, 225, 90, false, P); }
            if (!on) c.drawLine(cx - s * .8f, cy - s * .7f, cx + s * .8f, cy + s * .7f, P);
        }
        static void bluetooth(Canvas c, float cx, float cy, float s, int color, float alpha, boolean on) {
            stroke(color, alpha * (on ? 1f : .45f), s * .14f);
            PATH.reset();
            PATH.moveTo(cx - s * .45f, cy - s * .4f); PATH.lineTo(cx + s * .4f, cy + s * .35f); PATH.lineTo(cx, cy + s * .75f);
            PATH.lineTo(cx, cy - s * .75f); PATH.lineTo(cx + s * .4f, cy - s * .35f); PATH.lineTo(cx - s * .45f, cy + s * .4f);
            c.drawPath(PATH, P);
        }
        static void speaker(Canvas c, float cx, float cy, float s, int color, float alpha, boolean muted) {
            P.setStyle(Paint.Style.FILL); P.setColor(color); P.setAlpha((int) (Color.alpha(color) * alpha));
            PATH.reset();
            PATH.moveTo(cx - s * .8f, cy - s * .25f); PATH.lineTo(cx - s * .4f, cy - s * .25f); PATH.lineTo(cx, cy - s * .65f);
            PATH.lineTo(cx, cy + s * .65f); PATH.lineTo(cx - s * .4f, cy + s * .25f); PATH.lineTo(cx - s * .8f, cy + s * .25f); PATH.close();
            c.drawPath(PATH, P);
            stroke(color, alpha, s * .13f);
            if (muted) { c.drawLine(cx + s * .25f, cy - s * .3f, cx + s * .8f, cy + s * .3f, P); c.drawLine(cx + s * .8f, cy - s * .3f, cx + s * .25f, cy + s * .3f, P); }
            else c.drawArc(cx - s * .2f, cy - s * .5f, cx + s * .6f, cy + s * .5f, -60, 120, false, P);
        }
        static void gear(Canvas c, float cx, float cy, float s, int color, float alpha) {
            stroke(color, alpha, s * .16f);
            c.drawCircle(cx, cy, s * .38f, P);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * i / 4;
                c.drawLine(cx + (float) Math.cos(a) * s * .5f, cy + (float) Math.sin(a) * s * .5f, cx + (float) Math.cos(a) * s * .75f, cy + (float) Math.sin(a) * s * .75f, P);
            }
        }
        static void device(Canvas c, float cx, float cy, float s, int color, float alpha) {
            stroke(color, alpha, s * .14f);
            c.drawRoundRect(cx - s * .8f, cy - s * .5f, cx + s * .8f, cy + s * .35f, s * .12f, s * .12f, P);
            c.drawLine(cx - s * .35f, cy + s * .7f, cx + s * .35f, cy + s * .7f, P);
        }
        private static void stroke(int color, float alpha, float w) {
            P.setStyle(Paint.Style.STROKE); P.setStrokeCap(Paint.Cap.ROUND); P.setStrokeJoin(Paint.Join.ROUND);
            P.setStrokeWidth(w); P.setColor(color); P.setAlpha((int) (Color.alpha(color) * alpha));
        }
    }

    /** Lists of values that tests and the latency probe can read without reaching into drawing state. */
    List<String> cardNames(int row) {
        List<String> out = new ArrayList<>();
        for (NativeHomeConfig.Card k : cfg.rows.get(row).cards) out.add(k.name);
        return out;
    }
}
