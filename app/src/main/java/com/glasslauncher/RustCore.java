package com.glasslauncher;

import android.util.Log;
import java.nio.charset.StandardCharsets;

/**
 * Loads the Rust core (libglass.so) once per process and wraps its JNI entry points.
 * Every method degrades to a "not available" result instead of throwing, so a missing, mismatched or broken native
 * library leaves the launcher on its original JavaScript paths rather than crashing or crash-looping.
 * All calls are expected on the WebView's JavaBridge thread (never the UI thread); native state is a single
 * process-wide index that is simply replaced, so activity recreation needs no cleanup.
 */
final class RustCore {
    /** Must equal glass_core::ABI_VERSION; a mismatch disables the native path. */
    static final int EXPECTED_ABI = 1;
    private static final String TAG = "GlassRust";

    private static volatile boolean available;
    private static volatile String version = "";
    private static volatile String status = "not loaded";

    static {
        try {
            System.loadLibrary("glass");
            String v = new String(nativeVersion(), StandardCharsets.UTF_8);
            String[] parts = v.split(";");
            if (parts.length != 2 || Integer.parseInt(parts[1]) != EXPECTED_ABI) {
                status = "ABI mismatch: " + v;
            } else if (!nativeSelfTest()) {
                status = "self-test failed";
            } else {
                version = parts[0];
                available = true;
                status = "ok";
            }
        } catch (Throwable t) {
            status = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        try { Log.i(TAG, "Rust core " + (available ? "v" + version : "unavailable") + " (" + status + ")"); } catch (Throwable ignored) {}
    }

    private RustCore() {}

    static boolean isAvailable() { return available; }
    static String version() { return version; }
    static String status() { return status; }

    /** Replaces the search index. Returns the entry count, or a negative value on any failure. */
    static int setSearchIndex(String wire) {
        if (!available || wire == null) return -1;
        try { return nativeSetSearchIndex(wire.getBytes(StandardCharsets.UTF_8)); }
        catch (Throwable t) { return -1; }
    }

    /** Comma-separated indices into the last index, best first ("" for no hits); null on any failure. */
    static String search(String query, int max) {
        if (!available || query == null) return null;
        try {
            byte[] r = nativeSearch(query.getBytes(StandardCharsets.UTF_8), max);
            return r == null ? null : new String(r, StandardCharsets.UTF_8);
        } catch (Throwable t) { return null; }
    }

    private static native byte[] nativeVersion();
    private static native boolean nativeSelfTest();
    private static native int nativeSetSearchIndex(byte[] wireUtf8);
    private static native byte[] nativeSearch(byte[] queryUtf8, int max);
}
