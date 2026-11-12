package com.glasslauncher;

import android.content.Context;
import android.content.Intent;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;

/** The only object exposed to the web UI (as "AndroidBridge"). Thin: each method delegates to a focused class. */
public class GlassBridge {
    private final MainActivity act;
    private final AppDiscovery apps;
    private final SystemControls sys;
    private final SettingsLauncher settings;

    GlassBridge(MainActivity act) {
        this.act = act;
        this.apps = new AppDiscovery(act);
        this.sys = new SystemControls(act);
        this.settings = new SettingsLauncher(act);
    }

    // ---------- Apps (Phase 1 names unchanged) ----------
    @JavascriptInterface public boolean launchPackage(String pkg) { return apps.launch(pkg); }
    @JavascriptInterface public String getInstalledApps() { return apps.installedAppsJson(); }
    @JavascriptInterface public void goHome() {
        act.runOnUiThread(() -> { Intent i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_HOME); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); act.startActivity(i); });
    }
    // ---------- Apps (Phase 3) ----------
    @JavascriptInterface public boolean isInstalled(String pkg) { return apps.isInstalled(pkg); }
    @JavascriptInterface public String getAppIcon(String pkg) { return apps.iconDataUri(pkg); }

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
