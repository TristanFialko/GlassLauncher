package com.glasslauncher

import android.content.Context
import android.util.Log
import java.io.File

/**
 * N6 crash-loop recovery for the native HOME. GlassLauncher 2.0 has exactly one UI, so there is nothing to hand HOME
 * back to (the pre-N6 WebView fallback is gone). Instead a crash loop puts the native UI into SAFE MODE: it keeps
 * running as HOME but skips the optional Rust core, so Search ranks with the Kotlin port (the same code path that
 * already serves a missing libglass.so). Everything else - Home, Control Centre, launching apps, Settings - is plain
 * Android framework code and stays available, so a bad native library can never leave the box without a working HOME.
 *
 * Two kinds of crash count towards the budget ([CRASH_LIMIT] within [CRASH_WINDOW_MS]):
 *  - JVM exceptions, recorded by the uncaught-exception handler ([recordCrash]);
 *  - unclean starts: deaths that handler never sees (a signal in libglass.so, the system killing a hung process, an
 *    abort). Every HOME start writes a marker file ([recordStart]); the UI deletes it once it has run for
 *    [HEALTHY_AFTER_MS] ([markHealthy]). A marker still present at the next start counts as a crash at the time that
 *    earlier start began.
 *
 * Safe mode lasts [SAFE_MODE_MS] from the crash that triggered it; the next start after that tries the full native path
 * again with a fresh budget. State is a tiny properties file (not SharedPreferences) because the crash handler writes it
 * from a dying process.
 */
object NativeRecovery {
    const val CRASH_LIMIT = 2
    const val CRASH_WINDOW_MS = 10 * 60_000L
    /** How long a HOME start must survive before it is no longer suspected of crashing. */
    const val HEALTHY_AFTER_MS = 5_000L
    /** How long safe mode lasts once a crash loop triggers it. */
    const val SAFE_MODE_MS = 30 * 60_000L
    /** Reason label for a start that never confirmed itself healthy. */
    const val UNCLEAN_EXIT = "UncleanExit"
    private const val TAG = "GlassRecovery"
    private const val FILE = "native-recovery.properties"
    private const val PENDING_FILE = "native-start.pending"
    private val lock = Any()

    data class State(val crashes: List<Long> = emptyList(), val safeUntil: Long = 0L, val reason: String = "") {
        fun safeAt(now: Long): Boolean = now < safeUntil
    }

    // ---- Pure rules (unit-tested in NativeRecoveryTest) ----

    /** Keep crashes inside the window; at the limit, enter safe mode for [SAFE_MODE_MS] from [now]. */
    fun afterCrash(s: State, now: Long, what: String): State {
        val recent = s.crashes.filter { now - it in 0 until CRASH_WINDOW_MS } + now
        return if (recent.size >= CRASH_LIMIT)
            State(recent, maxOf(s.safeUntil, now + SAFE_MODE_MS), "native UI crashed ${recent.size}x within 10 min ($what)")
        else s.copy(crashes = recent)
    }

    /** A previous start that never confirmed itself healthy is a crash at the time it began. */
    fun afterStart(s: State, unconfirmedSince: Long?): State =
        if (unconfirmedSince == null) s else afterCrash(s, unconfirmedSince, UNCLEAN_EXIT)

    /** Safe mode that has run out is dropped together with its crash history: the full path gets a fresh budget. */
    fun expire(s: State, now: Long): State = if (s.safeUntil != 0L && !s.safeAt(now)) State() else s

    fun parsePending(text: String): Long? = text.trim().toLongOrNull()?.takeIf { it > 0 }

    fun parse(text: String): State {
        val m = text.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
        val crashes = m["crashes"].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
        return State(crashes, m["safeUntil"]?.toLongOrNull() ?: 0L, m["reason"].orEmpty())
    }

    fun format(s: State): String =
        "crashes=${s.crashes.joinToString(",")}\nsafeUntil=${s.safeUntil}\nreason=${s.reason.replace('\n', ' ')}\n"

    // ---- Context entry points ----

    /** Called when the UI starts as HOME, before it builds anything. Returns true when this start must run in safe mode. */
    fun recordStart(ctx: Context, now: Long): Boolean = recordStartIn(ctx.filesDir, now)
    fun markHealthy(ctx: Context) = markHealthyIn(ctx.filesDir)
    fun recordCrash(ctx: Context, now: Long, error: Throwable) = recordCrashIn(ctx.filesDir, now, error.javaClass.simpleName)

    // ---- File-level operations on a directory (Context.filesDir in the app; a temporary folder in JVM tests) ----

    fun stateIn(dir: File): State = read(dir)

    fun recordStartIn(dir: File, now: Long): Boolean {
        val next = synchronized(lock) {
            val b = read(dir)
            val n = expire(afterStart(b, readPending(dir)), now)
            if (n != b) write(dir, n)
            writePending(dir, now)
            n
        }
        val safe = next.safeAt(now)
        if (safe) Log.e(TAG, "safe mode until ${next.safeUntil}: ${next.reason}; Search uses the Kotlin ranking")
        return safe
    }

    fun recordCrashIn(dir: File, now: Long, what: String) {
        val next = synchronized(lock) {
            val n = afterCrash(read(dir), now, what)
            write(dir, n)
            clearPending(dir)   // this death is recorded; the next start must not count it again
            n
        }
        if (next.safeAt(now)) Log.e(TAG, "native UI crashed $CRASH_LIMIT times; next start runs in safe mode")
    }

    fun markHealthyIn(dir: File) { synchronized(lock) { clearPending(dir) } }

    fun hasUnconfirmedStartIn(dir: File): Boolean = readPending(dir) != null

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

/** Safe-mode Search backend: reports the Rust core unavailable, so [SearchEngine] ranks with the Kotlin port. */
object SafeModeBackend : RankBackend {
    override val available get() = false
    override fun prepare(wire: String): String? = null
    override fun search(query: String, max: Int): String? = null
}
