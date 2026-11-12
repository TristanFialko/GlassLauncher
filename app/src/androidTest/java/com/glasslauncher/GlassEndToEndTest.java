package com.glasslauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * End-to-end checks on a real Android WebView/PackageManager (CI: API 30 emulator). Timings are logged with tag
 * "GlassBench" — they are emulator numbers, not VIP7802 numbers.
 */
@RunWith(AndroidJUnit4.class)
public class GlassEndToEndTest {
    private static final String TAG = "GlassBench";

    private static String eval(ActivityScenario<MainActivity> sc, String js) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> out = new AtomicReference<>();
        sc.onActivity(a -> a.webView().evaluateJavascript(js, v -> { out.set(v); done.countDown(); }));
        assertTrue("JS timed out: " + js, done.await(60, TimeUnit.SECONDS));
        Object v = new JSONTokener(out.get()).nextValue();
        return v == JSONObject.NULL ? null : String.valueOf(v);
    }

    private static void waitForGlass(ActivityScenario<MainActivity> sc) throws Exception {
        long end = SystemClock.uptimeMillis() + 30000;
        while (SystemClock.uptimeMillis() < end) {
            if ("true".equals(eval(sc, "!!(window.Glass&&Glass.registry&&Glass.home&&document.readyState==='complete')"))) return;
            Thread.sleep(250);
        }
        throw new AssertionError("Glass UI did not boot");
    }

    /** Search through the real WebView -> AndroidBridge -> JNI -> Rust path (snapshot preparation + ranking) must list and rank
     *  exactly like registry.js. */
    @Test public void searchParityThroughWebViewBridge() throws Exception {
        try (ActivityScenario<MainActivity> sc = ActivityScenario.launch(MainActivity.class)) {
            waitForGlass(sc);
            String r = eval(sc, "(function(){var G=Glass;"
                + "var w=['Smart','Home','Remote','Cast','Media','Player','Service','System','Update','Weather','News','Sports','Music','Radio','Photo','Video','Live','Game','Fitness','Kids'];"
                + "var seed=1234567;function rnd(){seed=(seed*1103515245+12345)&0x7fffffff;return seed/0x7fffffff;}"
                + "var fake=[];for(var i=0;i<200;i++){var n=1+Math.floor(rnd()*3),t=[];for(var k=0;k<n;k++)t.push(w[Math.floor(rnd()*w.length)]);"
                + "fake.push({id:'bench:'+i,kind:'app',title:t.join(' ')+(i%3?'':' '+i),keywords:[],run:function(){}});}"
                + "G.registry.register({entries:function(){return fake;}});G.registry.invalidate();"
                + "var ents=G.registry.entries(),qs=[];ents.forEach(function(e){var s=String(e.title||'');for(var n=1;n<=Math.min(8,s.length);n++)qs.push(s.slice(0,n));});"
                + "var sid=function(e){return e.id;},se=G.registry.snapshotEntries(),snapshotMatches=!!se&&se.map(sid).join(',')===ents.map(sid).join(',');"
                + "var mism=0,first=null;qs.forEach(function(q){var a=G.registry.query(q,24).map(function(e){return e.id;}).join(','),"
                + "b=G.registry.queryJs(q,24).map(function(e){return e.id;}).join(',');if(a!==b){mism++;if(!first)first=[q,a,b];}});"
                + "var active=G.registry.coreActive();"
                + "function time(fn){var s=[];for(var r=0;r<9;r++){var t=performance.now();for(var j=0;j<qs.length;j++)fn(qs[j],24);s.push((performance.now()-t)*1000/qs.length);}"
                + "s.sort(function(a,b){return a-b;});return {median:+s[4].toFixed(1),max:+s[8].toFixed(1)};}"
                + "var tj=time(G.registry.queryJs),tr=time(G.registry.query);"
                // session preparation through the bridge, uncached: before = entries()+wire()+index; after = snapshot+prepare (last, so
                // the native index ends up holding the current snapshot again)
                + "function once(fn){var s=[];for(var r=0;r<9;r++){var t=performance.now();fn();s.push((performance.now()-t)*1000);}s.sort(function(a,b){return a-b;});return {median:+s[4].toFixed(0),max:+s[8].toFixed(0)};}"
                + "var pb=once(function(){AndroidBridge.coreSearchIndex(G.registry.wire(G.registry.entries()));}),"
                + "pa=once(function(){AndroidBridge.coreSearchSnapshot(G.registry.snapshotWire());});"
                + "return JSON.stringify({entries:ents.length,snapshotMatches:snapshotMatches,queries:qs.length,mismatches:mism,first:first,coreActive:active,"
                + "core:JSON.parse(AndroidBridge.coreInfo()),jsUsPerQuery:tj,rustViaBridgeUsPerQuery:tr,sessionPrepUsBefore:pb,sessionPrepUsAfter:pa});})()");
            Log.i(TAG, "search " + r);
            JSONObject o = new JSONObject(r);
            assertTrue("Rust core inactive: " + r, o.getBoolean("coreActive"));
            assertTrue("Rust snapshot entries differ from registry.js entries(): " + r, o.getBoolean("snapshotMatches"));
            assertEquals("ranking differs: " + o.opt("first"), 0, o.getInt("mismatches"));
            assertTrue(o.getInt("queries") > 500);
        }
    }

    /** Glass 2.0 app discovery (3 package-wide queries) must return exactly what the 1.5 per-package algorithm returned. */
    @Test public void appDiscoveryMatchesLegacyAlgorithm() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppDiscovery d = new AppDiscovery(ctx);
        String legacy = null, current = null;
        long[] tl = new long[7], tc = new long[7];
        for (int i = 0; i < 7; i++) {
            long t = SystemClock.elapsedRealtimeNanos(); legacy = legacyInstalledAppsJson(ctx); tl[i] = SystemClock.elapsedRealtimeNanos() - t;
            t = SystemClock.elapsedRealtimeNanos(); current = d.installedAppsJson(); tc[i] = SystemClock.elapsedRealtimeNanos() - t;
        }
        Arrays.sort(tl); Arrays.sort(tc);
        int pkgs = ctx.getPackageManager().getInstalledPackages(0).size();
        Log.i(TAG, "discovery " + new JSONObject().put("installedPackages", pkgs).put("launchable", new JSONArray(current).length())
                .put("legacyMsMedian", tl[3] / 1e6).put("legacyMsMax", tl[6] / 1e6).put("currentMsMedian", tc[3] / 1e6).put("currentMsMax", tc[6] / 1e6));
        assertEquals(legacy, current);
        assertTrue(new JSONArray(current).length() > 0);
    }

    /** Measurement only: cost of one uncached getAppIcon (128 px bitmap -> PNG -> base64), which blocks the JS thread. */
    @Test public void iconEncodingCost() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONArray apps = new JSONArray(new AppDiscovery(ctx).installedAppsJson());
        AppDiscovery d = new AppDiscovery(ctx);
        long[] t = new long[apps.length()];
        long chars = 0, total = 0;
        for (int i = 0; i < apps.length(); i++) {
            long s = SystemClock.elapsedRealtimeNanos();
            String uri = d.iconDataUri(apps.getJSONObject(i).getString("id"));
            t[i] = SystemClock.elapsedRealtimeNanos() - s;
            chars += uri.length();
            total += t[i];
        }
        Arrays.sort(t);
        Log.i(TAG, "icons " + new JSONObject().put("icons", t.length).put("msMedian", t[t.length / 2] / 1e6).put("msMax", t[t.length - 1] / 1e6)
                .put("msTotal", total / 1e6).put("avgDataUriChars", chars / Math.max(1, t.length)));
        assertTrue(chars > 0);
    }

    /**
     * Icons through the WebView (Glass 2.0) vs the 1.5 synchronous bridge call. Each path runs in a fresh activity
     * (fresh caches); the new path runs first, so the old path benefits from any warm system caches (conservative).
     */
    @Test public void iconsLoadOffTheJsThread() throws Exception {
        String pkgs = "Glass.apps.installed.map(function(x){return x.id;})";
        JSONObject fresh;
        try (ActivityScenario<MainActivity> sc = ActivityScenario.launch(MainActivity.class)) {
            waitForGlass(sc);
            eval(sc, "(function(){var ids=" + pkgs + ",n=0,ok=0,sizes=true,t0=performance.now();Glass.icons._cache={};"
                + "window.__ic=null;ids.forEach(function(id){Glass.icons.packageIcon(id,function(u){n++;if(u){ok++;"
                + "var im=new Image();im.src=u;}if(n===ids.length)window.__ic={n:n,ok:ok,wallMs:performance.now()-t0,blockMs:blockMs,"
                + "allUrl:ids.every(function(i){var c=Glass.icons._cache[i];return c===null||c.indexOf('https://')===0;})};});});"
                + "var blockMs=performance.now()-t0;return 'started';})()");
            long end = SystemClock.uptimeMillis() + 30000;
            String r;
            while ((r = eval(sc, "window.__ic&&JSON.stringify(window.__ic)")) == null || r.equals("false")) {
                assertTrue("icons did not finish", SystemClock.uptimeMillis() < end);
                Thread.sleep(50);
            }
            fresh = new JSONObject(r);
            eval(sc, "(function(){window.__sz=null;var u=Glass.icons._cache[Glass.apps.installed.filter(function(x){return Glass.icons._cache[x.id];})[0].id];"
                + "var i=new Image();i.onload=function(){window.__sz=i.naturalWidth+'x'+i.naturalHeight;};i.onerror=function(){window.__sz='error';};i.src=u;return 1;})()");
            String sz;
            while ((sz = eval(sc, "window.__sz")) == null) { assertTrue("size check timed out", SystemClock.uptimeMillis() < end); Thread.sleep(50); }
            fresh.put("decodedSize", sz);
        }
        JSONObject old;
        try (ActivityScenario<MainActivity> sc = ActivityScenario.launch(MainActivity.class)) {
            waitForGlass(sc);
            old = new JSONObject(eval(sc, "(function(){var ids=" + pkgs + ",ok=0,t0=performance.now();"
                + "ids.forEach(function(id){var u=AndroidBridge.getAppIcon(id);if(typeof u==='string'&&u.indexOf('data:')===0)ok++;});"
                + "var ms=performance.now()-t0;return JSON.stringify({n:ids.length,ok:ok,wallMs:ms,blockMs:ms});})()"));
        }
        Log.i(TAG, "iconpaths " + new JSONObject().put("webviewServed", fresh).put("legacyBridge", old));
        assertTrue("new path did not use URLs: " + fresh, fresh.getBoolean("allUrl"));
        assertEquals("same icons available", old.getInt("ok"), fresh.getInt("ok"));
        assertEquals("decoded icon size", "128x128", fresh.getString("decodedSize"));
        assertTrue(fresh.getInt("ok") > 0);
    }

    /** Verbatim 1.5 implementation (baseline AppDiscovery.installedAppsJson). */
    private static String legacyInstalledAppsJson(Context ctx) throws Exception {
        JSONArray a = new JSONArray();
        PackageManager pm = ctx.getPackageManager();
        for (PackageInfo p : pm.getInstalledPackages(PackageManager.GET_META_DATA)) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(p.packageName);
            if (i == null) i = pm.getLaunchIntentForPackage(p.packageName);
            if (i == null) continue;
            JSONObject o = new JSONObject();
            o.put("id", p.packageName);
            o.put("name", p.applicationInfo.loadLabel(pm).toString());
            o.put("show", true);
            a.put(o);
        }
        return a.toString();
    }

    /** With the Control Centre open, its 2 s poll must run in the foreground and stop while Glass is in the background. */
    @Test public void controlCentrePollStopsInBackground() throws Exception {
        try (ActivityScenario<MainActivity> sc = ActivityScenario.launch(MainActivity.class)) {
            waitForGlass(sc);
            eval(sc, "Glass.store.set('locasked',true);window.__n=0;var o=Glass.bridge.call;"
                + "Glass.bridge.call=function(f,a){if(f==='getWifiInfo')window.__n++;return o.apply(this,arguments);};Glass.cc.open({});'ok'");
            int start = Integer.parseInt(eval(sc, "window.__n"));
            Thread.sleep(10500);
            int fg = Integer.parseInt(eval(sc, "window.__n")) - start;
            sc.moveToState(Lifecycle.State.CREATED);          // onPause + onStop, like another app coming to the front
            int beforeBg = Integer.parseInt(eval(sc, "window.__n"));
            Thread.sleep(10500);
            int bg = Integer.parseInt(eval(sc, "window.__n")) - beforeBg;
            sc.moveToState(Lifecycle.State.RESUMED);
            Log.i(TAG, "ccpoll " + new JSONObject().put("foregroundCallsIn10s", fg).put("backgroundCallsIn10s", bg));
            assertTrue("poll not running in foreground: " + fg, fg >= 4);
            assertTrue("poll kept running in background: " + bg, bg <= 1);
        }
    }
}
