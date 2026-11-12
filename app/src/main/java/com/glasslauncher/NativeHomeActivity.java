package com.glasslauncher;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Trace;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Glass 2.0 native UI prototype (docs/nativeui/PROTOTYPE.md): the home screen drawn with a custom View instead of the
 * WebView. Not a HOME activity and runs in its own process (:native) so it can be measured without the WebView in the
 * same process. Start it with: adb shell am start -n com.glasslauncher/.NativeHomeActivity
 *
 * Threading: the UI thread only handles keys and draws. Config, artwork decoding/scaling, wallpaper blur, the
 * installed-app list (AppDiscovery) and Control Centre state (SystemControls) are loaded on one worker thread and
 * posted back.
 */
public class NativeHomeActivity extends Activity implements NativeHomeView.Host {
    private static final String TAG = "GlassNative";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private NativeHomeView view;
    private NativeHomeConfig cfg;
    private AppDiscovery apps;
    private SystemControls sys;
    private SettingsLauncher settings;
    private final Map<String, String> installed = new LinkedHashMap<>();   // id -> label, Android's order
    private volatile boolean installedLoaded;
    private SoundPool sounds;
    private int sNav, sClick;
    private FrameLatencyProbe probe;
    private final Runnable ticker = new Runnable() { @Override public void run() { if (view != null) view.tick(); ui.postDelayed(this, 10_000); } };

    @Override protected void onCreate(Bundle b) {
        Trace.beginSection("Glass:nativeCreate");
        try {
            super.onCreate(b);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            apps = new AppDiscovery(this);
            sys = new SystemControls(this);
            settings = new SettingsLauncher(this);
            try { cfg = NativeHomeConfig.load(getAssets()); }
            catch (Exception e) { Log.e(TAG, "home.json", e); finish(); return; }
            view = new NativeHomeView(this, cfg, this);
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            setContentView(view);
            view.requestFocus();
            probe = FrameLatencyProbe.attachIfRequested(this, "native");
            initSounds();
            worker.execute(this::loadAssets);
            worker.execute(this::loadInstalled);
        } finally { Trace.endSection(); }
    }

    /** Wallpapers (both themes), their blurred backdrops, baked shadows, then card art: current theme first. */
    private void loadAssets() {
        Trace.beginSection("Glass:nativeLoadAssets");
        try {
            final boolean dark = view.dark;
            Bitmap wl = NativeArt.decode(getAssets(), dark ? cfg.wallpaperDark : cfg.wallpaperLight, 1920, 1080);
            Bitmap bl = wl == null ? null : NativeArt.blurred(wl, 240, 135, 3);
            Bitmap ds = NativeArt.shadow((int) NativeHomeView.DOCK_W, (int) NativeHomeView.DOCK_H, NativeHomeView.DOCK_R, 44, 0xFF000000);
            Bitmap ts = NativeArt.shadow((int) NativeHomeView.TILE_W, (int) NativeHomeView.TILE_H, NativeHomeView.TILE_R, 14, 0xFF000000);
            ui.post(() -> {
                if (dark) { view.wallDark = wl; view.blurDark = bl; } else { view.wallLight = wl; view.blurLight = bl; }
                view.dockShadow = ds; view.tileShadow = ts; view.invalidate();
            });
            loadArt(dark);
            Bitmap w2 = NativeArt.decode(getAssets(), dark ? cfg.wallpaperLight : cfg.wallpaperDark, 1920, 1080);
            Bitmap b2 = w2 == null ? null : NativeArt.blurred(w2, 240, 135, 3);
            ui.post(() -> { if (dark) { view.wallLight = w2; view.blurLight = b2; } else { view.wallDark = w2; view.blurDark = b2; } view.invalidate(); });
            loadArt(!dark);   // the other theme's art is warmed so switching never flashes (home.js:21)
            Bitmap sun = NativeArt.decode(getAssets(), "moonsun/sun-filled.png", 48, 48), moon = NativeArt.decode(getAssets(), "moonsun/moon-filled.png", 48, 48);
            ui.post(() -> { view.cc.sun = sun; view.cc.moon = moon; });
            Log.i(TAG, "assets ready");
        } catch (Throwable t) { Log.e(TAG, "assets", t); }
        finally { Trace.endSection(); }
    }

    private void loadArt(boolean dark) {
        for (int ri = 0; ri < cfg.rows.size(); ri++) {
            for (int ci = 0; ci < cfg.rows.get(ri).cards.size(); ci++) {
                NativeHomeConfig.Card k = cfg.rows.get(ri).cards.get(ci);
                String path = dark ? (k.dark.isEmpty() ? k.icon : k.dark) : (k.icon.isEmpty() ? k.dark : k.icon);
                if (path.isEmpty()) continue;
                Bitmap bm = NativeArt.card(getAssets(), path, (int) NativeHomeView.TILE_W, (int) NativeHomeView.TILE_H, NativeHomeView.TILE_R);
                final int r = ri, c = ci;
                ui.post(() -> { (dark ? view.artDark : view.artLight)[r][c] = bm; view.invalidate(); });
            }
        }
    }

    private void loadInstalled() {
        Trace.beginSection("Glass:nativeInstalledApps");
        try {
            JSONArray a = new JSONArray(apps.installedAppsJson());
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optBoolean("show", true)) m.put(o.getString("id"), o.optString("name"));
            }
            ui.post(() -> { installed.clear(); installed.putAll(m); installedLoaded = true; });
        } catch (Throwable t) { Log.e(TAG, "installed apps", t); }
        finally { Trace.endSection(); }
    }

    private void initSounds() {
        if (!cfg.soundEnabled) return;
        sounds = new SoundPool.Builder().setMaxStreams(3)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build();
        try (AssetFileDescriptor n = getAssets().openFd("sounds/navigate.wav"); AssetFileDescriptor c = getAssets().openFd("sounds/click.wav")) {
            sNav = sounds.load(n, 1); sClick = sounds.load(c, 1);
        } catch (Exception e) { Log.w(TAG, "sounds", e); }
    }

    @Override public void playNav() { if (sounds != null && sNav != 0) sounds.play(sNav, cfg.soundVolume, cfg.soundVolume, 1, 0, 1f); }
    @Override public void playClick() { if (sounds != null && sClick != 0) sounds.play(sClick, cfg.soundVolume, cfg.soundVolume, 1, 0, 1f); }

    @Override public void launch(NativeHomeConfig.Card card) {
        if (!installedLoaded) { view.showToast("Still reading the app list…"); return; }
        String pkg = NativeHomeConfig.resolve(card, installed);
        if (pkg == null || !apps.launch(pkg)) view.showToast(card.name + " is not installed on this box.");
    }

    @Override public void openSettings(String kind) { if (!settings.open(kind)) view.showToast("This box has no such settings screen"); }

    @Override public void controlCentreAction(String id, int delta) {
        switch (id) {
            case "wifi": openSettings("wifi"); return;
            case "bluetooth": openSettings("bluetooth"); return;
            case "settings": openSettings("main"); return;
            case "device": openSettings("device"); return;
            case "theme": view.setDark(!view.dark); return;
            case "volume":
                worker.execute(() -> {
                    try {
                        JSONObject v = new JSONObject(sys.volumeInfo());
                        String r = delta == 0 ? sys.toggleMute() : sys.setVolume(Math.max(0, Math.min(v.getInt("max"), v.getInt("cur") + delta)));
                        applyVolume(new JSONObject(r));
                    } catch (Throwable t) { Log.w(TAG, "volume", t); }
                });
                if (delta != 0) playNav();
                return;
            default:   // "refresh": read all Control Centre state off the UI thread
                worker.execute(() -> {
                    try {
                        JSONObject w = new JSONObject(sys.wifiInfo());
                        boolean eth = w.optBoolean("ethernet"), con = w.optBoolean("wifiConnected");
                        view.cc.wifiOn = eth || con || w.optBoolean("wifiEnabled");
                        view.cc.wifiTitle = eth ? "Ethernet" : "Wi-Fi";
                        view.cc.wifiSub = eth ? "Connected" : con ? (w.optString("ssid", "").isEmpty() ? "Connected" : w.optString("ssid")) : (w.optBoolean("wifiEnabled") ? "Not connected" : "Off");
                    } catch (Throwable t) { view.cc.wifiSub = "Unavailable"; }
                    try {
                        JSONObject bt = new JSONObject(sys.bluetoothInfo());
                        view.cc.btOn = bt.optBoolean("enabled");
                        JSONArray conn = bt.optJSONArray("connected");
                        view.cc.btSub = !bt.optBoolean("supported") ? "Not supported" : !view.cc.btOn ? "Off"
                                : (conn != null && conn.length() > 0 ? conn.optString(0) : "On");
                    } catch (Throwable t) { view.cc.btSub = "Unavailable"; }
                    try { applyVolume(new JSONObject(sys.volumeInfo())); } catch (Throwable ignored) {}
                    try {
                        JSONObject d = new JSONObject(sys.deviceInfo());
                        view.cc.deviceSub = d.optString("model", "") + (d.has("android") ? " · Android " + d.optString("android") : "");
                    } catch (Throwable t) { view.cc.deviceSub = ""; }
                    ui.post(view::invalidate);
                });
        }
    }

    private void applyVolume(JSONObject v) {
        view.cc.vol = v.optInt("cur", -1); view.cc.volMax = v.optInt("max", 15); view.cc.muted = v.optBoolean("muted");
        view.cc.volSub = view.cc.muted ? "Muted" : (view.cc.vol + " of " + view.cc.volMax);
        ui.post(view::invalidate);
    }

    @Override public void exitPrototype() { finish(); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        boolean r = super.dispatchKeyEvent(e);
        if (probe != null) probe.onKeyDispatched(e);
        return r;
    }

    @Override protected void onResume() { super.onResume(); ui.post(ticker); }
    @Override protected void onPause() { ui.removeCallbacks(ticker); super.onPause(); }

    @Override protected void onDestroy() {
        if (probe != null) probe.detach();
        worker.shutdownNow();
        if (sounds != null) sounds.release();
        super.onDestroy();
    }

    /** For instrumented tests. */
    NativeHomeView homeView() { return view; }
    boolean installedLoaded() { return installedLoaded; }
}
