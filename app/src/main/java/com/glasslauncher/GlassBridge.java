package com.glasslauncher;

import android.content.Context;
import android.content.Intent;
import android.os.Trace;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;

/** The only object exposed to the web UI (as "AndroidBridge"). Thin: each method delegates to a focused class. */
public class GlassBridge {
    private final MainActivity act;
    private final AppDiscovery apps;
    private final SystemControls sys;
    private final SettingsLauncher settings;

    GlassBridge(MainActivity act) {
        this.act = act;
        this.apps = act.appDiscovery();
        this.sys = new SystemControls(act);
        this.settings = new SettingsLauncher(act);
    }

    // ---------- Apps (Phase 1 names unchanged) ----------
    @JavascriptInterface public boolean launchPackage(String pkg) { return apps.launch(pkg); }
    @JavascriptInterface public String getInstalledApps() {
        Trace.beginSection("Glass:getInstalledApps");
        try { return apps.installedAppsJson(); } finally { Trace.endSection(); }
    }
    @JavascriptInterface public void goHome() {
        act.runOnUiThread(() -> { Intent i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_HOME); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); act.startActivity(i); });
    }
    // ---------- Apps (Phase 3) ----------
    @JavascriptInterface public boolean isInstalled(String pkg) { return apps.isInstalled(pkg); }
    /** Glass 2.0: base URL the WebView loads app icons from (served by MainActivity, off the JS thread). */
    @JavascriptInterface public String iconBaseUrl() { return AppDiscovery.ICON_BASE; }
    @JavascriptInterface public String getAppIcon(String pkg) {
        Trace.beginSection("Glass:getAppIcon");
        try { return apps.iconDataUri(pkg); } finally { Trace.endSection(); }
    }

    // ---------- Rust core (Glass 2.0). Called on the JavaBridge thread, never the UI thread. ----------
    @JavascriptInterface public String coreInfo() {
        try {
            return new JSONObject().put("available", RustCore.isAvailable()).put("version", RustCore.version())
                    .put("status", RustCore.status()).toString();
        } catch (Exception e) { return "{\"available\":false}"; }
    }
    @JavascriptInterface public int coreSearchIndex(String wire) {
        Trace.beginSection("Glass:coreSearchIndex");
        try { return RustCore.setSearchIndex(wire); } finally { Trace.endSection(); }
    }
    @JavascriptInterface public String coreSearch(String query, int max) {
        Trace.beginSection("Glass:coreSearch");
        try { return RustCore.search(query, max); } finally { Trace.endSection(); }
    }

    // ---------- Control Centre (Phase 2 names unchanged) ----------
    @JavascriptInterface public String getVolumeInfo() { return sys.volumeInfo(); }
    @JavascriptInterface public String setVolume(int level) { return sys.setVolume(level); }
    @JavascriptInterface public String toggleMute() { return sys.toggleMute(); }
    @JavascriptInterface public String getAudioOutput() { return sys.audioOutput(); }
    @JavascriptInterface public String getWifiInfo() { return sys.wifiInfo(); }
    @JavascriptInterface public String getBluetoothInfo() { return sys.bluetoothInfo(); }
    @JavascriptInterface public String getDeviceInfo() { return sys.deviceInfo(); }
    @JavascriptInterface public boolean requestLocationPermission() { return sys.requestLocationPermission(); }
    @JavascriptInterface public boolean openSettings(String kind) { return settings.open(kind); }
    // ---------- Settings availability (Phase 3, used by Search) ----------
    @JavascriptInterface public boolean hasSettings(String kind) { return settings.has(kind); }

    // ---------- On-screen keyboard for Search (Phase 3) ----------
    @JavascriptInterface public void showKeyboard() {
        act.runOnUiThread(() -> {
            try {
                act.webView().requestFocus();
                InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(act.webView(), 0);
            } catch (Exception ignored) {}
        });
    }
    @JavascriptInterface public void hideKeyboard() {
        act.runOnUiThread(() -> {
            try {
                InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(act.webView().getWindowToken(), 0);
            } catch (Exception ignored) {}
        });
    }
}
