package com.glasslauncher

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end checks on a real Android PackageManager (CI: API 30 emulator). Timings are logged with tag "GlassBench";
 * they are emulator numbers, not VIP7802 numbers.
 *
 * N6: migrated from GlassEndToEndTest.java. The WebView-only cases (search through the JavaScript bridge, icon data URIs,
 * the JS Control Centre poll) were removed with the WebView UI; native Search parity is covered by
 * SearchRankingParityTest / SearchRankingGoldenTest (JVM) and RustCoreInstrumentedTest (device JNI).
 */
@RunWith(AndroidJUnit4::class)
class GlassEndToEndTest {
    private companion object {
        const val TAG = "GlassBench"

        /** Verbatim 1.5 algorithm (baseline AppDiscovery.installedAppsJson), ported to Kotlin. */
        @Suppress("DEPRECATION")
        fun legacyInstalledAppsJson(ctx: Context): String {
            val a = JSONArray()
            val pm = ctx.packageManager
            for (p in pm.getInstalledPackages(PackageManager.GET_META_DATA)) {
                pm.getLeanbackLaunchIntentForPackage(p.packageName) ?: pm.getLaunchIntentForPackage(p.packageName) ?: continue
                val o = JSONObject()
                o.put("id", p.packageName)
                o.put("name", p.applicationInfo!!.loadLabel(pm).toString())
                o.put("show", true)
                a.put(o)
            }
            return a.toString()
        }
    }

    /** Glass 2.0 app discovery (3 package-wide queries) must return exactly what the 1.5 per-package algorithm returned. */
    @Test fun appDiscoveryMatchesLegacyAlgorithm() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val d = AppDiscovery(ctx)
        var legacy = ""
        var current = ""
        val tl = LongArray(7)
        val tc = LongArray(7)
        for (i in 0 until 7) {
            var t = SystemClock.elapsedRealtimeNanos(); legacy = legacyInstalledAppsJson(ctx); tl[i] = SystemClock.elapsedRealtimeNanos() - t
            t = SystemClock.elapsedRealtimeNanos(); current = d.installedAppsJson(); tc[i] = SystemClock.elapsedRealtimeNanos() - t
        }
        tl.sort(); tc.sort()
        @Suppress("DEPRECATION")
        val pkgs = ctx.packageManager.getInstalledPackages(0).size
        Log.i(TAG, "discovery " + JSONObject().put("installedPackages", pkgs).put("launchable", JSONArray(current).length())
            .put("legacyMsMedian", tl[3] / 1e6).put("legacyMsMax", tl[6] / 1e6).put("currentMsMedian", tc[3] / 1e6).put("currentMsMax", tc[6] / 1e6))
        assertEquals(legacy, current)
        assertTrue(JSONArray(current).length() > 0)
    }

    /** Native icon path: every launchable app's icon encodes to PNG off the UI thread. Measurement plus a sanity check. */
    @Test fun nativeIconsEncode() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val d = AppDiscovery(ctx)
        val apps = JSONArray(d.installedAppsJson())
        val t = LongArray(apps.length())
        var ok = 0
        for (i in 0 until apps.length()) {
            val s = SystemClock.elapsedRealtimeNanos()
            val png = d.iconPng(apps.getJSONObject(i).getString("id"))
            t[i] = SystemClock.elapsedRealtimeNanos() - s
            if (png != null && png.isNotEmpty()) ok++
        }
        t.sort()
        Log.i(TAG, "icons " + JSONObject().put("icons", t.size).put("ok", ok)
            .put("msMedian", if (t.isEmpty()) 0.0 else t[t.size / 2] / 1e6).put("msMax", if (t.isEmpty()) 0.0 else t[t.size - 1] / 1e6))
        assertTrue("no icon encoded", ok > 0)
    }
}
