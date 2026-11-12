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
 */
object UiRouter {
    const val EXTRA = "glass.ui"
    const val WEBVIEW = "webview"
    const val NATIVE = "native"
    const val CRASH_LIMIT = 2
    const val CRASH_WINDOW_MS = 10 * 60_000L
    private const val TAG = "GlassUiRouter"
    private const val FILE = "ui-mode.properties"

    data class State(val mode: String = WEBVIEW, val crashes: List<Long> = emptyList(), val fallbackReason: String = "")

    @JvmStatic fun mode(ctx: Context): String = read(ctx).mode

    /** Persists an explicit choice from an intent extra; clears crash history so a fresh opt-in gets a fresh budget. */
    @JvmStatic fun applyExtra(ctx: Context, value: String?): Boolean {
        if (value != WEBVIEW && value != NATIVE) return false
        write(ctx, State(mode = value))
        Log.i(TAG, "UI mode set to $value")
        return true
    }

    /** Called by the native process's uncaught-exception handler. */
    @JvmStatic fun recordNativeCrash(ctx: Context, now: Long, error: Throwable) {
        val next = afterCrash(read(ctx), now, error.javaClass.simpleName)
        write(ctx, next)
        if (next.mode == WEBVIEW) Log.e(TAG, "native UI crashed $CRASH_LIMIT times; HOME falls back to the WebView UI")
    }

    /** Pure rule, unit-tested: keep crashes inside the window; at the limit, fall back to the WebView UI. */
    @JvmStatic fun afterCrash(s: State, now: Long, what: String): State {
        val recent = s.crashes.filter { now - it in 0 until CRASH_WINDOW_MS } + now
        return if (s.mode == NATIVE && recent.size >= CRASH_LIMIT)
            State(WEBVIEW, recent, "native UI crashed ${recent.size}x within 10 min ($what)")
        else s.copy(crashes = recent)
    }

    @JvmStatic fun parse(text: String): State {
        val m = text.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
        val mode = if (m["mode"] == NATIVE) NATIVE else WEBVIEW
        val crashes = m["crashes"].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
        return State(mode, crashes, m["fallback"].orEmpty())
    }

    @JvmStatic fun format(s: State): String =
        "mode=${s.mode}\ncrashes=${s.crashes.joinToString(",")}\nfallback=${s.fallbackReason.replace('\n', ' ')}\n"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun read(ctx: Context): State = try {
        val f = file(ctx)
        if (f.exists()) parse(f.readText()) else State()
    } catch (t: Throwable) { Log.w(TAG, "read", t); State() }

    private fun write(ctx: Context, s: State) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(format(s))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (t: Throwable) { Log.w(TAG, "write", t) }
    }
}
