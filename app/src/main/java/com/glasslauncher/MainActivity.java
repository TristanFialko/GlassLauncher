package com.glasslauncher;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Hosts the Glass web UI (assets/index.html). All logic lives in GlassBridge + assets/js. */
public class MainActivity extends Activity {
    private WebView web;
    private AppDiscovery apps;
    private FrameLatencyProbe probe;   // null unless started with --ez glass.latency true (benchmark only)
    private static final String BACK_JS = "(function(){var e=new KeyboardEvent('keydown',{bubbles:true});Object.defineProperty(e,'keyCode',{get:function(){return 10009;}});document.dispatchEvent(e);})();";

    WebView webView() { return web; }
    AppDiscovery appDiscovery() { return apps; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894 | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        apps = new AppDiscovery(this);
        web = new WebView(this);
        web.setBackgroundColor(0xff0b0b14);
        // Phase 5: without this, Chromium builds a full accessibility node tree for every DOM
        // element so screen readers can read the page. Android's own AccessibilityManager then
        // tracks "accessibility focus" across that tree independently of our own JS .f navigation —
        // that's the yellow ring, and the auto-scroll-into-view that fights our own scrolling.
        // Glass has no need for screen-reader support, so this turns that tree off entirely,
        // which also removes a real amount of work Chromium was doing on every DOM change
        // (e.g. every Search keystroke rebuilding the result list).
        web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // That flag alone isn't fully suppressing it on this box's WebView build — the yellow
        // ring came back. This is the stronger, more direct version of the same fix: it stops
        // the WebView from ever dispatching an accessibility event in the first place, so there's
        // nothing left for Android's AccessibilityManager to track or draw a focus rectangle around.
        web.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void sendAccessibilityEvent(View host, int eventType) { /* swallow */ }
        });
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true); s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true);
        // These two, together with index.html's <meta name="viewport" content="width=1920"> (no
        // initial-scale), are what make Glass render at the correct size at ANY screen density —
        // this is the actual fix for the 1920x1080/320dpi issue. setUseWideViewPort makes the
        // WebView honor the page's declared width exactly, in CSS pixels, regardless of density;
        // setLoadWithOverviewMode then auto-zooms that fixed-width layout to fill the real screen.
        // Do not add "initial-scale=1" back to that meta tag: it pins the CSS viewport to the
        // device's devicePixelRatio instead, which only produces 1920 CSS px at 160dpi (dpr=1) —
        // at 320dpi (dpr=2) it silently renders everything at a 960px-wide layout instead, which
        // is exactly why this project previously needed Android forced to 160dpi to look right.
        web.addJavascriptInterface(new GlassBridge(this), "AndroidBridge");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            // Glass 2.0: app icons are served from here (WebView loader threads) instead of a blocking bridge call.
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                WebResourceResponse r = apps.iconResponse(req.getUrl().toString());
                return r != null ? r : super.shouldInterceptRequest(v, req);
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        probe = FrameLatencyProbe.attachIfRequested(this, "webview");
    }

    @Override protected void onPause() {
        // Glass 2.0: while another app is in front, stop the hidden launcher's JS timers (clock tick, and the
        // Control Centre's 2 s Wi-Fi/Bluetooth/volume poll if it was left open) so it costs no CPU in the background.
        if (web != null) { web.onPause(); web.pauseTimers(); }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) { web.resumeTimers(); web.onResume(); }
        // Lets the Control Centre refresh after returning from a system settings screen.
        if (web != null) web.postDelayed(() -> web.evaluateJavascript("window.glassResume&&glassResume()", null), 400);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();
        if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN || kc == KeyEvent.KEYCODE_VOLUME_MUTE) {
            boolean r = super.dispatchKeyEvent(event);
            // Keep the Control Centre slider in step with the physical remote volume keys.
            if (event.getAction() == KeyEvent.ACTION_DOWN && web != null)
                web.postDelayed(() -> web.evaluateJavascript("window.glassVolumeSync&&glassVolumeSync()", null), 250);
            return r;
        }
        if (kc == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) { if (web != null) web.evaluateJavascript(BACK_JS, null); return true; }
        boolean handled = super.dispatchKeyEvent(event);
        if (probe != null) probe.onKeyDispatched(event);
        return handled;
    }

    @Override public void onBackPressed() {
        if (web != null) web.evaluateJavascript(BACK_JS, null); else super.onBackPressed();
    }
}
