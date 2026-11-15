package com.glasslauncher

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Which UI the HOME entry point (MainActivity) shows: the WebView UI (default) or the native UI.
 *
 * The state is a tiny properties file rather than SharedPreferences because two processes write it: MainActivity
 * (main process) and the native UI's crash handler (`:native`), and SharedPreferences is not multi-process safe.
 *
 * Safety: native mode is opt-in only (`am start -n com.glasslauncher/.MainActivity --es glass.ui native`). If the native
 * UI crashes [CRASH_LIMIT] times within [CRASH_WINDOW_MS], it reverts itself to the WebView UI, so a bad build can never
 * leave the box without a working HOME.
 *
 * Two kinds of crash count towards that budget:
 *  - JVM exceptions, recorded by the `:native` uncaught-exception handler ([recordNativeCrash]);
 *  - unclean starts: deaths that handler never sees (a signal in libglass.so, the system killing a hung process, an
 *    abort). Every native HOME start writes a marker file ([recordNativeStart]); the native UI deletes it once it has run
 *    for [HEALTHY_AFTER_MS] ([markNativeHealthy]). A marker still present at the next start is counted as a crash at
 *    the time that earlier start began. The marker is a separate file so the healthy path never rewrites the mode file
 *    (no cross-process read-modify-write race with MainActivity).
 */
object UiRouter {
    const val EXTRA = "glass.ui"
    const val WEBVIEW = "webview"
    const val NATIVE = "native"
    const val CRASH_LIMIT = 2
    const val CRASH_WINDOW_MS = 10 * 60_000L
    /** How long a native HOME start must survive before it is no longer suspected of crashing. */
    const val HEALTHY_AFTER_MS = 5_000L
    /** Reason label for a start that never confirmed itself healthy. */
    const val UNCLEAN_EXIT = "UncleanExit"
    private const val TAG = "GlassUiRouter"
    private const val FILE = "ui-mode.properties"
    private const val PENDING_FILE = "native-start.pending"
    private val lock = Any()

    data class State(val mode: String = WEBVIEW, val crashes: List<Long> = emptyList(), val fallbackReason: String = "")

    @JvmStatic fun mode(ctx: Context): String = read(ctx.filesDir).mode

    /** Persists an explicit choice from an intent extra; clears crash history so a fresh opt-in gets a fresh budget. */
    @JvmStatic fun applyExtra(ctx: Context, value: String?): Boolean = applyExtraIn(ctx.filesDir, value)

    /** Called by the native process's uncaught-exception handler. */
    @JvmStatic fun recordNativeCrash(ctx: Context, now: Long, error: Throwable) =
        recordNativeCrashIn(ctx.filesDir, now, error.javaClass.simpleName)

    /**
     * Called by the native UI when it starts as HOME, before it builds anything. Counts an unconfirmed previous start as a
     * crash and returns the resulting mode; anything but [NATIVE] means the caller must hand HOME back to MainActivity.
     */
    @JvmStatic fun recordNativeStart(ctx: Context, now: Long): String = recordNativeStartIn(ctx.filesDir, now)

    /** Called by the native UI [HEALTHY_AFTER_MS] after a HOME start: that start is no longer suspected. */
    @JvmStatic fun markNativeHealthy(ctx: Context) = markNativeHealthyIn(ctx.filesDir)

    /** Pure rule, unit-tested: keep crashes inside the window; at the limit, fall back to the WebView UI. */
    @JvmStatic fun afterCrash(s: State, now: Long, what: String): State {
        val recent = s.crashes.filter { now - it in 0 until CRASH_WINDOW_MS } + now
        return if (s.mode == NATIVE && recent.size >= CRASH_LIMIT)
            State(WEBVIEW, recent, "native UI crashed ${recent.size}x within 10 min ($what)")
        else s.copy(crashes = recent)
    }

    /** Pure rule, unit-tested: a previous start that never confirmed itself healthy is a crash at the time it began. */
    @JvmStatic fun afterNativeStart(s: State, unconfirmedSince: Long?): State =
        if (unconfirmedSince == null) s else afterCrash(s, unconfirmedSince, UNCLEAN_EXIT)

    @JvmStatic fun parsePending(text: String): Long? = text.trim().toLongOrNull()?.takeIf { it > 0 }

    @JvmStatic fun parse(text: String): State {
        val m = text.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
        val mode = if (m["mode"] == NATIVE) NATIVE else WEBVIEW
        val crashes = m["crashes"].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
        return State(mode, crashes, m["fallback"].orEmpty())
    }

    @JvmStatic fun format(s: State): String =
        "mode=${s.mode}\ncrashes=${s.crashes.joinToString(",")}\nfallback=${s.fallbackReason.replace('\n', ' ')}\n"

    // ---- File-level operations on a directory (Context.filesDir in the app; a temporary folder in JVM tests) ----

    @JvmStatic fun modeIn(dir: File): String = read(dir).mode

    @JvmStatic fun applyExtraIn(dir: File, value: String?): Boolean {
        if (value != WEBVIEW && value != NATIVE) return false
        synchronized(lock) {
            write(dir, State(mode = value))
            clearPending(dir)
        }
        Log.i(TAG, "UI mode set to $value")
        return true
    }

    @JvmStatic fun recordNativeCrashIn(dir: File, now: Long, what: String) {
        val next = synchronized(lock) {
            val n = afterCrash(read(dir), now, what)
            write(dir, n)
            clearPending(dir)   // this death is recorded; the next start must not count it again
            n
        }
        if (next.mode == WEBVIEW) Log.e(TAG, "native UI crashed $CRASH_LIMIT times; HOME falls back to the WebView UI")
    }

    @JvmStatic fun recordNativeStartIn(dir: File, now: Long): String {
        val (before, next) = synchronized(lock) {
            val b = read(dir)
            val n = afterNativeStart(b, readPending(dir))
            if (n != b) write(dir, n)
            if (n.mode == NATIVE) writePending(dir, now) else clearPending(dir)
            b to n
        }
        if (before.mode == NATIVE && next.mode != NATIVE) Log.e(TAG, "native UI did not start cleanly; HOME falls back to the WebView UI")
        return next.mode
    }

    @JvmStatic fun markNativeHealthyIn(dir: File) { synchronized(lock) { clearPending(dir) } }

    @JvmStatic fun hasUnconfirmedStartIn(dir: File): Boolean = readPending(dir) != null

    private fun read(dir: File): State = try {
        val f = File(dir, FILE)
        if (f.exists()) parse(f.readText()) else State()
    } catch (t: Throwable) { Log.w(TAG, "read", t); State() }

    private fun write(dir: File, s: State) {
        try {
            val f = File(dir, FILE)
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(format(s))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (t: Throwable) { Log.w(TAG, "write", t) }
    }

    private fun readPending(dir: File): Long? = try {
        val f = File(dir, PENDING_FILE)
        if (f.exists()) parsePending(f.readText()) else null
    } catch (t: Throwable) { Log.w(TAG, "readPending", t); null }

    private fun writePending(dir: File, now: Long) {
        try { File(dir, PENDING_FILE).writeText(now.toString()) } catch (t: Throwable) { Log.w(TAG, "writePending", t) }
    }

    private fun clearPending(dir: File) {
        try { File(dir, PENDING_FILE).delete() } catch (t: Throwable) { Log.w(TAG, "clearPending", t) }
    }
}
