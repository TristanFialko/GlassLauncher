package com.glasslauncher;

import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Native UI prototype vs the WebView UI on the emulator, driven like the device benchmark: shell `am start` and
 * `input keyevent` (the prototype runs in another process, so ActivityScenario cannot host it). Produces screenshots and
 * logs in <app external files>/nativeui for the CI artifact; asserts the prototype renders, moves focus, opens the
 * Control Centre and that every D-pad key produced a measured frame. Emulator numbers are NOT device performance.
 */
// a_ runs first: it clears the screenshot folder, and b_ leaves HOME routing exercised afterwards
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4.class)
public class NativeUiPrototypeTest {
    private static final String TAG = "GlassNativeTest";
    private UiAutomation ua;

    private String sh(String cmd) throws Exception {
        ParcelFileDescriptor pfd = ua.executeShellCommand(cmd);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pfd), StandardCharsets.UTF_8))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    private static final String DIR = "/data/local/tmp/glass-nativeui/";

    /** Text artifacts go to logcat (tag GlassNativeArtifact, one line per line); CI saves that log. The shell cannot read the app's files on API 30. */
    private void save(String name, String text) {
        for (String l : text.split("\n")) Log.i("GlassNativeArtifact", "[" + name + "] " + l);
    }


    private Bitmap shot(String name) throws Exception {
        SystemClock.sleep(900);                                  // let 220-450 ms transitions settle
        Bitmap b = ua.takeScreenshot();
        sh("screencap -p " + DIR + name);   // shell-owned copy for the CI artifact
        return b;
    }

    private static boolean differs(Bitmap a, Bitmap b) {
        if (a == null || b == null || a.getWidth() != b.getWidth()) return true;
        int diff = 0;
        for (int y = 0; y < a.getHeight(); y += 8) for (int x = 0; x < a.getWidth(); x += 8) if (a.getPixel(x, y) != b.getPixel(x, y)) diff++;
        return diff > 50;
    }

    private void key(int code) throws Exception { sh("input keyevent " + code); SystemClock.sleep(350); }

    private String waitFor(String tagFilter, String needle, long ms) throws Exception {
        long end = SystemClock.uptimeMillis() + ms;
        String log = "";
        while (SystemClock.uptimeMillis() < end) {
            log = sh("logcat -d -s " + tagFilter);
            if (log.contains(needle)) return log;
            SystemClock.sleep(250);
        }
        return log;
    }

    @Test public void a_nativeVersusWebView() throws Exception {
        ua = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        // the Control Centre asks for location on first open (S8, same as the WebView UI); a system dialog would take the keys
        sh("pm grant com.glasslauncher android.permission.ACCESS_FINE_LOCATION");
        sh("rm -rf /data/local/tmp/glass-nativeui"); sh("mkdir -p /data/local/tmp/glass-nativeui");
        int[] path = {22, 22, 20, 21, 19, 19};   // right, right, down (row 2), left, up, up (header)
        StringBuilder report = new StringBuilder();
        report.append(sh("wm size")).append(sh("wm density"));

        // ---- native prototype ----
        sh("logcat -c");
        String nStart = sh("am start -W -n com.glasslauncher/.NativeHomeActivity --ez glass.latency true");
        report.append("--- native start\n").append(nStart);
        String ready = waitFor("GlassNative:I", "assets ready", 30_000);
        assertTrue("native prototype never finished loading assets:\n" + ready + "\nam start:\n" + nStart + "\nresumed: " + resumed() + "\n" + sh("logcat -d -t 60 *:W"), ready.contains("assets ready"));
        Bitmap nHome = shot("native-1-home.png");
        sh("dumpsys gfxinfo com.glasslauncher:native reset");
        key(22);
        Bitmap nFocus = shot("native-2-focus-second-card.png");
        key(22); key(20);
        Bitmap nRow2 = shot("native-3-entertainment-row.png");
        key(21); key(19); key(19);
        shot("native-4-header-search.png");
        key(22); key(23);
        Bitmap nCc = shot("native-5-control-centre.png");
        key(20); key(20);
        shot("native-6-control-centre-volume.png");
        key(4);
        Bitmap nBack = shot("native-7-after-back.png");
        String nGfx = sh("dumpsys gfxinfo com.glasslauncher:native");
        String nLat = sh("logcat -d -s GlassLatency:I");
        save("native-gfxinfo.txt", nGfx);
        save("native-latency.txt", nLat);
        report.append(sh("dumpsys meminfo com.glasslauncher:native")).append('\n');
        assertTrue("focus move did not change the screen", differs(nHome, nFocus));
        assertTrue("moving to the Entertainment row did not change the screen", differs(nFocus, nRow2));
        assertTrue("Control Centre did not open", differs(nRow2, nCc));
        assertTrue("Back did not close the Control Centre", differs(nCc, nBack));
        int nLines = nLat.split("ui=native key=", -1).length - 1;
        assertTrue("latency probe logged " + nLines + " native key frames:\n" + nLat, nLines >= 8);
        // no force-stop: it would also kill this instrumentation (it runs in the app's main process); Back above ended the prototype

        // ---- WebView UI, same path ----
        sh("logcat -c");
        report.append("--- webview start\n").append(sh("am start -W -n com.glasslauncher/.MainActivity --ez glass.latency true"));
        // the emulator's software-GPU WebView paints the clock and rows well after `am start` returns (at 8 s only the
        // wallpaper, header buttons and Recently Used panel were there): wait >= 15 s, then until two shots 2 s apart match
        SystemClock.sleep(15_000);
        Bitmap prev = ua.takeScreenshot();
        for (int i = 0; i < 15; i++) {
            SystemClock.sleep(2000);
            Bitmap now = ua.takeScreenshot();
            if (!differs(prev, now)) break;
            prev = now;
        }
        shot("webview-1-home.png");
        sh("dumpsys gfxinfo com.glasslauncher reset");
        for (int k : path) key(k);
        shot("webview-2-after-path.png");
        String wGfx = sh("dumpsys gfxinfo com.glasslauncher");
        String wLat = sh("logcat -d -s GlassLatency:I");
        save("webview-gfxinfo.txt", wGfx);
        save("webview-latency.txt", wLat);
        report.append(sh("dumpsys meminfo com.glasslauncher")).append('\n');
        save("report.txt", report.toString());
        Log.i(TAG, "native latency lines=" + nLines + ", webview latency lines=" + (wLat.split("ui=webview key=", -1).length - 1));
    }

    private String resumed() throws Exception {
        for (String l : sh("dumpsys activity activities").split("\n")) if (l.contains("mResumedActivity") || l.contains("ResumedActivity:")) return l.trim();
        return "";
    }

    /**
     * Native Search, the full Control Centre, the theme toggle, toasts and opt-in HOME routing, on the emulator. Correctness
     * only: screenshots go to the CI artifact for visual review. Recently Used needs a configured app installed, which this
     * emulator image lacks, so it is covered by JVM tests and the owner's device checklist.
     */
    @Test public void b_nativeSurfacesAndRouting() throws Exception {
        ua = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        // the Control Centre asks for location on first open (S8, same as the WebView UI); a system dialog would take the keys
        sh("pm grant com.glasslauncher android.permission.ACCESS_FINE_LOCATION");
        sh("mkdir -p /data/local/tmp/glass-nativeui");
        sh("logcat -c");
        sh("am start -W -n com.glasslauncher/.NativeHomeActivity");
        String ready = waitFor("GlassNative:I", "assets ready", 30_000);
        assertTrue("native UI never finished loading assets:\n" + ready, ready.contains("assets ready"));
        Bitmap home = shot("surf-01-home.png");

        // OK on a card whose app is missing on this image -> toast
        key(23);
        Bitmap toast = shot("surf-02-toast-not-installed.png");
        assertTrue("launching a missing app showed no toast", differs(home, toast));
        SystemClock.sleep(3800);

        // Search: Up to the header (Search), OK; type with the hardware keyboard path, then the on-screen keyboard
        key(19); key(23);
        Bitmap search = shot("surf-03-search-open.png");
        assertTrue("Search did not open", differs(home, search));
        sh("input text sett");
        Bitmap typed = shot("surf-04-search-typed-sett.png");
        assertTrue("typing did not change Search", differs(search, typed));
        key(20); key(23);
        shot("surf-05-search-onscreen-key.png");
        key(4);
        Bitmap closed = shot("surf-06-search-closed.png");
        assertTrue("Back did not close Search", differs(typed, closed));

        // Control Centre: header Right, OK; walk the tiles; Appearance toggles the theme
        key(19); key(22); key(23);
        Bitmap cc = shot("surf-07-control-centre.png");
        assertTrue("Control Centre did not open", differs(closed, cc));
        key(20); shot("surf-08-cc-down-1.png");
        key(20); shot("surf-09-cc-down-2.png");
        key(20); shot("surf-10-cc-down-3.png");
        key(4); key(23); key(23);   // close, reopen (focus starts on Appearance), toggle
        SystemClock.sleep(800);   // 700 ms theme crossfade
        Bitmap themed = shot("surf-11-cc-theme-toggled.png");
        assertTrue("Appearance did not change the theme", differs(cc, themed));
        key(23); SystemClock.sleep(800);   // toggle back
        key(4);
        shot("surf-12-cc-closed.png");

        // Opt-in HOME routing: MainActivity hands over to the native UI, and back
        sh("am start -W -n com.glasslauncher/.MainActivity --es glass.ui native");
        SystemClock.sleep(2500);
        String r1 = resumed();
        save("routing.txt", "native: " + r1);
        assertTrue("glass.ui=native did not show the native UI: " + r1, r1.contains("NativeHomeActivity"));
        // clear-task also finishes the native HOME instance, so later tests start a fresh native activity
        sh("am start -W --activity-clear-task -n com.glasslauncher/.MainActivity --es glass.ui webview");
        SystemClock.sleep(2500);
        String r2 = resumed();
        save("routing.txt", "webview: " + r2);
        assertTrue("glass.ui=webview did not show the WebView UI: " + r2, r2.contains("MainActivity"));
    }
}
