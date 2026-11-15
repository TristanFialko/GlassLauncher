package com.glasslauncher

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * Native UI on the emulator, driven like the device benchmark: shell `am start` and `input keyevent` (the UI runs in
 * another process, :native, so ActivityScenario cannot host it). Produces screenshots and logs for the CI artifact;
 * asserts the UI renders, moves focus, opens the Control Centre and Search, and that every D-pad key produced a measured
 * frame. Emulator numbers are NOT device performance.
 *
 * N6: migrated from NativeUiPrototypeTest.java. The WebView comparison and the MainActivity glass.ui routing checks were
 * removed with the WebView UI; c_ checks the HOME / Leanback entry and Back-at-root semantics instead.
 *
 * Every shell command is time-bounded and each test has a JUnit timeout, so a stuck command fails fast.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class NativeUiPrototypeTest {
    private companion object {
        const val TAG = "GlassNativeTest"
        /** Per-test backstop; each test needs about 3 minutes on the CI emulator. */
        const val TEST_TIMEOUT_MS = 8 * 60_000L
        /** Default bound for one shell command. */
        const val SH_TIMEOUT_MS = 60_000L
        /** `am start -W` can block on API 30; bounded and non-fatal, readiness is asserted through logcat. */
        const val AM_START_TIMEOUT_MS = 20_000L
        const val DIR = "/data/local/tmp/glass-nativeui/"

        fun differs(a: Bitmap?, b: Bitmap?): Boolean {
            if (a == null || b == null || a.width != b.width) return true
            var diff = 0
            var y = 0
            while (y < a.height) {
                var x = 0
                while (x < a.width) { if (a.getPixel(x, y) != b.getPixel(x, y)) diff++; x += 8 }
                y += 8
            }
            return diff > 50
        }
    }

    private lateinit var ua: UiAutomation

    /** Runs a shell command and returns its output; never blocks longer than [timeoutMs]. */
    private fun sh(cmd: String, timeoutMs: Long = SH_TIMEOUT_MS): String {
        val pfd = ua.executeShellCommand(cmd)
        val sb = StringBuffer()
        val reader = Thread({
            try {
                BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(pfd), Charsets.UTF_8)).use { r ->
                    while (true) { val l = r.readLine() ?: break; sb.append(l).append('\n') }
                }
            } catch (e: IOException) { sb.append("[read failed: ").append(e).append("]\n") }
        }, "sh-reader")
        reader.isDaemon = true
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) {
            val msg = "[sh timed out after $timeoutMs ms: $cmd]"
            Log.w(TAG, msg)
            try { pfd.close() } catch (_: IOException) { }
            reader.interrupt()
            return sb.toString() + msg + "\n"
        }
        return sb.toString()
    }

    /** Text artifacts go to logcat (tag GlassNativeArtifact, one line per line); CI saves that log. */
    private fun save(name: String, text: String) {
        for (l in text.split("\n")) Log.i("GlassNativeArtifact", "[$name] $l")
    }

    private fun shot(name: String): Bitmap? {
        SystemClock.sleep(900)   // let 220-450 ms transitions settle
        val b = ua.takeScreenshot()
        sh("screencap -p $DIR$name")   // shell-owned copy for the CI artifact
        return b
    }

    private fun key(code: Int) { sh("input keyevent $code"); SystemClock.sleep(350) }

    private fun waitFor(tagFilter: String, needle: String, ms: Long): String {
        val end = SystemClock.uptimeMillis() + ms
        var log = ""
        while (SystemClock.uptimeMillis() < end) {
            log = sh("logcat -d -s $tagFilter")
            if (log.contains(needle)) return log
            SystemClock.sleep(250)
        }
        return log
    }

    private fun resumed(): String =
        sh("dumpsys activity activities").split("\n").firstOrNull { it.contains("mResumedActivity") || it.contains("ResumedActivity:") }?.trim().orEmpty()

    private fun startNative(extra: String): String {
        val start = sh("am start -W --activity-clear-task $extra", AM_START_TIMEOUT_MS)
        val ready = waitFor("GlassNative:I", "assets ready", 30_000)
        assertTrue("native UI never finished loading assets:\n$ready\nam start:\n$start\nresumed: ${resumed()}\n${sh("logcat -d -t 60 *:W")}", ready.contains("assets ready"))
        return start
    }

    @Test(timeout = TEST_TIMEOUT_MS) fun a_nativeFocusAndLatency() {
        ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        // the Control Centre asks for location on first open (S8); a system dialog would take the keys
        sh("pm grant com.glasslauncher android.permission.ACCESS_FINE_LOCATION")
        sh("rm -rf /data/local/tmp/glass-nativeui"); sh("mkdir -p /data/local/tmp/glass-nativeui")
        val report = StringBuilder()
        report.append(sh("wm size")).append(sh("wm density"))
        sh("logcat -c")
        report.append("--- native start\n").append(startNative("-n com.glasslauncher/.NativeHomeActivity --ez glass.latency true"))
        val nHome = shot("native-1-home.png")
        sh("dumpsys gfxinfo com.glasslauncher:native reset")
        key(22)
        val nFocus = shot("native-2-focus-second-card.png")
        key(22); key(20)
        val nRow2 = shot("native-3-entertainment-row.png")
        key(21); key(19); key(19)
        shot("native-4-header-search.png")
        key(22); key(23)
        val nCc = shot("native-5-control-centre.png")
        key(20); key(20)
        shot("native-6-control-centre-volume.png")
        key(4)
        val nBack = shot("native-7-after-back.png")
        val nGfx = sh("dumpsys gfxinfo com.glasslauncher:native")
        val nLat = sh("logcat -d -s GlassLatency:I")
        save("native-gfxinfo.txt", nGfx)
        save("native-latency.txt", nLat)
        report.append(sh("dumpsys meminfo com.glasslauncher:native")).append('\n')
        save("report.txt", report.toString())
        assertTrue("focus move did not change the screen", differs(nHome, nFocus))
        assertTrue("moving to the Entertainment row did not change the screen", differs(nFocus, nRow2))
        assertTrue("Control Centre did not open", differs(nRow2, nCc))
        assertTrue("Back did not close the Control Centre", differs(nCc, nBack))
        val nLines = nLat.split("ui=native key=").size - 1
        assertTrue("latency probe logged $nLines native key frames:\n$nLat", nLines >= 8)
        Log.i(TAG, "native latency lines=$nLines")
    }

    /**
     * Native Search, the full Control Centre, the theme toggle and toasts. Correctness only: screenshots go to the CI
     * artifact for visual review.
     */
    @Test(timeout = TEST_TIMEOUT_MS) fun b_nativeSurfaces() {
        ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        sh("pm grant com.glasslauncher android.permission.ACCESS_FINE_LOCATION")
        sh("mkdir -p /data/local/tmp/glass-nativeui")
        sh("logcat -c")
        startNative("-n com.glasslauncher/.NativeHomeActivity")
        val home = shot("surf-01-home.png")

        // OK on a card whose app is missing on this image -> toast
        key(23)
        val toast = shot("surf-02-toast-not-installed.png")
        assertTrue("launching a missing app showed no toast", differs(home, toast))
        SystemClock.sleep(3800)

        // Search: Up to the header (Search), OK; type with the hardware keyboard path, then the on-screen keyboard
        key(19); key(23)
        val search = shot("surf-03-search-open.png")
        assertTrue("Search did not open", differs(home, search))
        sh("input text sett")
        val typed = shot("surf-04-search-typed-sett.png")
        assertTrue("typing did not change Search", differs(search, typed))
        key(20); key(23)
        shot("surf-05-search-onscreen-key.png")
        key(4)
        val closed = shot("surf-06-search-closed.png")
        assertTrue("Back did not close Search", differs(typed, closed))

        // Control Centre: header Right, OK; walk the tiles; Appearance toggles the theme
        key(19); key(22); key(23)
        val cc = shot("surf-07-control-centre.png")
        assertTrue("Control Centre did not open", differs(closed, cc))
        key(20); shot("surf-08-cc-down-1.png")
        key(20); shot("surf-09-cc-down-2.png")
        key(20); shot("surf-10-cc-down-3.png")
        key(4); key(23); key(23)   // close, reopen (focus starts on Appearance), toggle
        SystemClock.sleep(800)     // 700 ms theme crossfade
        val themed = shot("surf-11-cc-theme-toggled.png")
        assertTrue("Appearance did not change the theme", differs(cc, themed))
        key(23); SystemClock.sleep(800)   // toggle back
        key(4)
        shot("surf-12-cc-closed.png")
    }

    /** N6: started through its HOME / Leanback intent the native UI is the launcher root, so Back there does nothing. */
    @Test(timeout = TEST_TIMEOUT_MS) fun c_homeEntryAndBackAtRoot() {
        ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        sh("logcat -c")
        startNative("-a android.intent.action.MAIN -c android.intent.category.HOME -n com.glasslauncher/.NativeHomeActivity")
        val r1 = resumed()
        assertTrue("HOME intent did not show the native UI: $r1", r1.contains("NativeHomeActivity"))
        key(4); SystemClock.sleep(1500)
        val r2 = resumed()
        save("routing.txt", "home: $r1\nafter back: $r2")
        assertTrue("Back at the HOME root left the native UI: $r2", r2.contains("NativeHomeActivity"))
        val homes = sh("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        save("home-activities.txt", homes)
        assertTrue("NativeHomeActivity is not a HOME activity:\n$homes", homes.contains("com.glasslauncher/.NativeHomeActivity"))
        assertTrue("a second Glass HOME activity exists:\n$homes", homes.lines().count { it.contains("com.glasslauncher/") } == 1)
        // leave a fresh (non-HOME) task for later runs
        sh("am start -W --activity-clear-task -n com.glasslauncher/.NativeHomeActivity", AM_START_TIMEOUT_MS)
    }
}
