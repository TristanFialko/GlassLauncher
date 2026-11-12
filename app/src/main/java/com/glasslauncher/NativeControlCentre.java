package com.glasslauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.KeyEvent;


/**
 * Control Centre panel for the native prototype (controlcentre.css geometry: right-hand panel 800 px wide with a
 * 32 px inset, 52 px radius, 4-column grid, slide-in over 420 ms). The prototype carries the six most used tiles;
 * state comes from SystemControls on NativeHomeActivity's worker thread, never from the draw pass.
 */
final class NativeControlCentre {
    static final float PW = 800, INSET = 32, PR = 52, PAD_X = 32, PAD_TOP = 30, GRID_GAP = 20, TILE_R = 36;

    /** id, column, row, column span, row height */
    static final String[] IDS = {"wifi", "bluetooth", "volume", "theme", "settings", "device"};
    static final int[][] GRID = {{0, 0, 2}, {2, 0, 2}, {0, 1, 4}, {0, 2, 2}, {2, 2, 2}, {0, 3, 4}};
    static final float[] ROW_H = {152, 152, 160, 140};

    final NativeHomeView home;
    boolean open;
    int sel;
    float selAmt = 1f;

    // live state (set from the worker thread, read on the UI thread)
    volatile String wifiTitle = "Wi-Fi", wifiSub = "…", btTitle = "Bluetooth", btSub = "…", volSub = "…", deviceSub = "…";
    volatile boolean wifiOn, btOn, muted;
    volatile int vol = -1, volMax = 15;
    Bitmap sun, moon;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), scrim = new Paint(), bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Typeface semi = Typeface.create("sans-serif-medium", Typeface.NORMAL), reg = NativeHomeView.REGULAR;
    private final LinearGradient panelSheen, tileSheen;
    private final Matrix lm = new Matrix();
    private final RectF r = new RectF();

    private android.animation.ValueAnimator aSel;

    NativeControlCentre(NativeHomeView home) {
        this.home = home;
        stroke.setStyle(Paint.Style.STROKE);
        scrim.setShader(new LinearGradient(0, 0, NativeHomeView.W, 0, new int[]{0x00060810, 0x00060810, 0x57060810}, new float[]{0f, .38f, 1f}, Shader.TileMode.CLAMP));
        panelSheen = new LinearGradient(0, 0, PW * .82f, 1016 * .57f, 0x21FFFFFF, 0x0EFFFFFF, Shader.TileMode.CLAMP);
        tileSheen = new LinearGradient(0, 0, 300, 210, 0x25FFFFFF, 0x11FFFFFF, Shader.TileMode.CLAMP);
    }

    void open() { open = true; sel = 0; selAmt = 1f; home.host.controlCentreAction("refresh", 0); }

    boolean onKey(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK: home.closeControlCentre(); return true;
            case KeyEvent.KEYCODE_DPAD_UP: return move(nearest(sel, -1));
            case KeyEvent.KEYCODE_DPAD_DOWN: return move(nearest(sel, 1));
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (IDS[sel].equals("volume")) { home.host.controlCentreAction("volume", -1); return true; }
                return move(GRID[sel][0] > 0 ? sel - 1 : -1);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (IDS[sel].equals("volume")) { home.host.controlCentreAction("volume", 1); return true; }
                return move(GRID[sel][0] + GRID[sel][2] < 4 && sel + 1 < IDS.length && GRID[sel + 1][1] == GRID[sel][1] ? sel + 1 : -1);
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() > 0) return true;
                home.host.playClick();
                home.host.controlCentreAction(IDS[sel], 0);
                return true;
            default: return true;     // the panel is modal: nothing reaches the home screen behind it
        }
    }

    /** Spatial up/down: the tile in the next grid row whose column range overlaps the current one, left-most first. */
    private int nearest(int from, int dir) {
        int row = GRID[from][1] + dir, c0 = GRID[from][0], c1 = c0 + GRID[from][2];
        for (int i = 0; i < IDS.length; i++) {
            if (GRID[i][1] != row) continue;
            int a0 = GRID[i][0], a1 = a0 + GRID[i][2];
            if (a0 < c1 && a1 > c0) return i;
        }
        return -1;
    }

    private boolean move(int to) {
        if (to < 0 || to == sel) return true;
        sel = to;
        home.host.playNav();
        aSel = home.run(aSel, 0f, 1f, 180, NativeHomeView.EASE, v -> selAmt = v);
        return true;
    }

    void draw(Canvas c, float mix) {
        scrim.setAlpha((int) (255 * Math.min(1f, mix * 1.2f)));
        c.drawRect(0, 0, NativeHomeView.W, NativeHomeView.H, scrim);
        float x = NativeHomeView.W - INSET - PW + (1f - mix) * (PW + INSET + 60), y = INSET, h = NativeHomeView.H - 2 * INSET;
        c.save();
        c.translate(x, y);
        r.set(0, 0, PW, h);
        home.drawBlurBackdrop(c, r, PR, x, y, mix);   // controlcentre.css backdrop-filter: blur(1.5rem) saturate(1.5)
        fill.setShader(null); fill.setColor(Color.argb((int) (168 * mix), 25, 29, 40));
        c.drawRoundRect(r, PR, PR, fill);
        fill.setShader(panelSheen); fill.setAlpha((int) (255 * mix));
        c.drawRoundRect(r, PR, PR, fill);
        fill.setShader(null);
        stroke.setStrokeWidth(1); stroke.setColor(Color.argb((int) (48 * mix), 255, 255, 255));
        c.drawRoundRect(r, PR, PR, stroke);


        text.setTextAlign(Paint.Align.RIGHT); text.setTypeface(semi); text.setColor(Color.WHITE); text.setAlpha((int) (255 * mix));
        text.setTextSize(72);
        c.drawText(home.ccTime, PW - PAD_X - 18, PAD_TOP + 64, text);
        text.setTypeface(reg); text.setTextSize(23); text.setColor(0xADFFFFFF); text.setAlpha((int) (173 * mix));
        c.drawText(home.date, PW - PAD_X - 18, PAD_TOP + 64 + 36, text);

        float gridTop = PAD_TOP + 64 + 36 + 34, innerW = PW - 2 * PAD_X, colW = (innerW - 3 * GRID_GAP) / 4f;
        for (int i = 0; i < IDS.length; i++) {
            float ty = gridTop;
            for (int rr = 0; rr < GRID[i][1]; rr++) ty += ROW_H[rr] + GRID_GAP;
            float tx = PAD_X + GRID[i][0] * (colW + GRID_GAP), tw = GRID[i][2] * colW + (GRID[i][2] - 1) * GRID_GAP, th = ROW_H[GRID[i][1]];
            drawTile(c, i, tx, ty, tw, th, open && i == sel ? selAmt : 0f, mix);
        }
        c.restore();
    }

    private void drawTile(Canvas c, int i, float x, float y, float w, float h, float f, float mix) {
        float s = 1f + .018f * f;
        c.save();
        c.scale(s, s, x + w / 2f, y + h / 2f);
        r.set(x, y, x + w, y + h);
        lm.setTranslate(x, y); tileSheen.setLocalMatrix(lm);
        fill.setShader(tileSheen); fill.setAlpha((int) (255 * mix * (1f - f)));
        c.drawRoundRect(r, TILE_R, TILE_R, fill);
        fill.setShader(null);
        if (f > 0f) { fill.setColor(Color.argb((int) (245 * f * mix), 243, 245, 250)); c.drawRoundRect(r, TILE_R, TILE_R, fill); }
        stroke.setStrokeWidth(1); stroke.setColor(Color.argb((int) (38 * mix), 255, 255, 255));
        c.drawRoundRect(r, TILE_R, TILE_R, stroke);

        int ink = NativeHomeView.blend(0xFFFFFFFF, 0xFF14161C, f);
        float chipX = x + 26 + 35, chipY = y + h / 2f;
        fill.setColor(NativeHomeView.blend(0x2BFFFFFF, 0x1F14161C, f)); fill.setAlpha((int) (Color.alpha(fill.getColor()) * mix));
        c.drawCircle(chipX, chipY, 35, fill);
        String id = IDS[i], t1, t2;
        switch (id) {
            case "wifi": NativeHomeView.NativeIcons.wifi(c, chipX, chipY, 18, ink, mix, wifiOn); t1 = wifiTitle; t2 = wifiSub; break;
            case "bluetooth": NativeHomeView.NativeIcons.bluetooth(c, chipX, chipY, 18, ink, mix, btOn); t1 = btTitle; t2 = btSub; break;
            case "volume": NativeHomeView.NativeIcons.speaker(c, chipX, chipY, 18, ink, mix, muted); t1 = "Volume"; t2 = volSub; break;
            case "theme": {
                Bitmap b = home.dark ? moon : sun;
                if (b != null) { bmp.setColorFilter(NativeHomeView.tint(ink)); bmp.setAlpha((int) (255 * mix)); r.set(chipX - 18, chipY - 18, chipX + 18, chipY + 18); c.drawBitmap(b, null, r, bmp); }
                t1 = "Appearance"; t2 = home.dark ? "Dark" : "Light"; break;
            }
            case "settings": NativeHomeView.NativeIcons.gear(c, chipX, chipY, 18, ink, mix); t1 = "Settings"; t2 = "Android TV Settings"; break;
            default: NativeHomeView.NativeIcons.device(c, chipX, chipY, 18, ink, mix); t1 = "About this box"; t2 = deviceSub; break;
        }
        text.setTextAlign(Paint.Align.LEFT); text.setTypeface(semi); text.setTextSize(25); text.setColor(ink); text.setAlpha((int) (Color.alpha(ink) * mix));
        float tx = chipX + 35 + 22;
        c.drawText(t1, tx, chipY - 4, text);
        text.setTypeface(reg); text.setTextSize(20); text.setColor(NativeHomeView.blend(0xADFFFFFF, 0xB314161C, f)); text.setAlpha((int) (Color.alpha(text.getColor()) * mix));
        c.drawText(ellipsize(t2, x + w - tx - 20), tx, chipY + 24, text);
        if (id.equals("volume") && vol >= 0) {
            float bx = x + w - 330, by = chipY - 4, bw = 290;
            fill.setColor(NativeHomeView.blend(0x40FFFFFF, 0x3314161C, f)); c.drawRoundRect(bx, by - 5, bx + bw, by + 5, 5, 5, fill);
            fill.setColor(ink); c.drawRoundRect(bx, by - 5, bx + bw * (muted ? 0 : vol / (float) Math.max(1, volMax)), by + 5, 5, 5, fill);
        }
        c.restore();
    }

    private String ellipsize(String s, float max) {
        if (s == null) return "";
        if (text.measureText(s) <= max) return s;
        String e = s;
        while (e.length() > 1 && text.measureText(e + "…") > max) e = e.substring(0, e.length() - 1);
        return e + "…";
    }
}
