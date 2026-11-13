package com.glasslauncher

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.Executor

/** UI-thread scheduling used for the debounce and for posting results back. */
interface UiScheduler {
    fun post(r: Runnable)
    fun postDelayed(r: Runnable, delayMs: Long)
    fun cancel(r: Runnable)
}

class MainLooperScheduler : UiScheduler {
    private val h = Handler(Looper.getMainLooper())
    override fun post(r: Runnable) { h.post(r) }
    override fun postDelayed(r: Runnable, delayMs: Long) { h.postDelayed(r, delayMs) }
    override fun cancel(r: Runnable) { h.removeCallbacks(r) }
}

/**
 * The native ranking calls; RustCore in production, a fake in tests. The backend holds ONE index (RustCore's is
 * process-wide), so [prepare] replaces whatever an earlier caller prepared. Implementations may throw; the engine
 * treats a throw like a null result.
 */
interface RankBackend {
    val available: Boolean
    fun prepare(wire: String): String?
    fun search(query: String, max: Int): String?
}

object RustBackend : RankBackend {
    override val available get() = RustCore.isAvailable()
    override fun prepare(wire: String): String? = RustCore.prepareSearch(wire)
    override fun search(query: String, max: Int): String? = RustCore.search(query, max)
}

/** Why Search ranks with the Kotlin port instead of Rust ([SearchEngine.fallback]; null while Rust ranks). */
enum class RankFallback {
    /** libglass missing, ABI mismatch or failed self-test (`RustCore.isAvailable()` false), or the check threw. */
    NATIVE_UNAVAILABLE,
    /** The catalog cannot cross JNI: a control character in a package id or a lone surrogate (registry.js stayed on JS too). */
    CATALOG_NOT_WIRE_SAFE,
    /** `prepareSearch` returned null, threw, or returned malformed/out-of-range references. Retried on the next session. */
    PREPARE_FAILED,
    /** `search` returned null or threw. Kotlin for the rest of this engine's life (registry.js `core.on=false`). */
    SEARCH_FAILED,
    /** `search` returned an unparsable, out-of-range, duplicate or over-limit index. Kotlin for the rest of this engine's life. */
    INVALID_RESULT,
}

/**
 * Which implementation produced the last delivered ranking (observability and tests). [FAILED]: neither could answer
 * (catalog preparation or the Kotlin fallback itself failed); the listener got [SearchEngine.Listener.onFailed].
 */
enum class Ranker { RUST, KOTLIN, NONE, FAILED }

/** Live facts for a Search session. Called only on the engine's background executor (PackageManager access is fine here). */
interface SearchFactsSource {
    fun installedApps(): List<InstalledApp>
    /** package id -> extra search words (window.SEARCH_ALIASES); shortcuts.js' default Play Store words are added by the engine. */
    fun aliases(): Map<String, List<String>>
    /** SettingsLauncher.has(kind); asked once per kind and cached for the process, like registry.hasSettings. */
    fun hasSettings(kind: String): Boolean
    /** Other registry providers' entries; none in production. */
    fun extraEntries(): List<CatalogEntry> = emptyList()
}

/** What the Search controller needs from an engine (lets tests substitute a synchronous fake). */
interface QuerySource {
    val hasPending: Boolean
    fun beginSession()
    fun endSession()
    fun request(query: String)
    fun flush()
}

/**
 * Prepares the search snapshot and ranks queries off the UI thread. Use a single-thread [background] executor: requests are
 * ordered by it. The debounce (110 ms, trailing, search.js setQuery), sequence numbers and delivery happen on the UI thread.
 * Results of superseded requests and of closed sessions are dropped.
 *
 * Rust ([backend]) is the authoritative ranker. The Kotlin port ([SearchRanking]) is used only for the reasons in
 * [RankFallback] and for queries containing a lone surrogate (they cannot cross JNI). Every change of route is reported
 * once through [log] (never per keystroke) and is readable from [fallback] / [usingRust].
 *
 * Failure contract (M3): every flushed request that is still current gets exactly one delivery on the UI thread:
 * [Listener.onResults] (possibly an empty list = a legitimate "no match") or [Listener.onFailed] (internal failure: the
 * catalog could not be prepared, or the Kotlin fallback threw after Rust could not answer). An internal failure is never
 * delivered as an empty result list. Failures are logged at most once per stage and exception class per engine, without
 * query text or exception messages; [failureCount] counts them.
 *
 * [kotlinRank] is the Kotlin ranking function ([SearchRanking.query]); a parameter only so tests can make the fallback fail.
 */
class SearchEngine(
    private val background: Executor,
    private val ui: UiScheduler,
    private val facts: SearchFactsSource,
    private val backend: RankBackend = RustBackend,
    private val kotlinRank: (List<SearchRanking.Candidate>, String, Int) -> IntArray = SearchRanking::query,
    private val log: (String) -> Unit = {},
) : QuerySource {
    fun interface Listener {
        fun onResults(query: String, results: List<SearchResult>)
        /** [query] could not be ranked at all (not the same as no match). Default: ignored. */
        fun onFailed(query: String) {}
    }

    /** Where an internal failure happened (log key; never includes the query). */
    private enum class Stage { PREPARE, RUST, KOTLIN }

    companion object {
        const val DEBOUNCE_MS = 110L
        const val MAX_RESULTS = 24

        /**
         * The backend keeps a single index, so an engine may reuse its previous preparation (or rank at all) only while it is
         * still the last engine that prepared it. Guarded by [INDEX_LOCK] so the owner check and the native call are atomic.
         * Weak, so the process-wide owner does not keep a destroyed activity's engine (and its listener/view) alive.
         */
        private val INDEX_LOCK = Any()
        private var indexOwner: WeakReference<SearchEngine>? = null
        private const val MAX_FAILURE_LOG_LINES = 4
    }

    /** Receives ranked results on the UI thread; set once before the first session. */
    var listener: Listener? = null

    // UI-thread state
    private var seq = 0
    private var session = 0
    private var pendingQuery: String? = null
    private val timer = Runnable { flush() }

    // background-thread state
    private var snapshot: SearchCatalog.Snapshot? = null
    private var refs: IntArray? = null
    private var fallbackEntries: Pair<List<SearchCatalog.Source>, List<SearchRanking.Candidate>>? = null
    private var lastSent: String? = null
    private var lastRefs: String? = null
    private var broken: RankFallback? = null     // SEARCH_FAILED / INVALID_RESULT: sticky for this engine
    private var routeReported = false
    private var prepareFailed = false            // this session's preparation threw; retried by the next beginSession
    private val failuresLogged = HashSet<String>()
    private val settingsCache = HashMap<String, Boolean>()

    /** True while queries are ranked by Rust. */
    @Volatile var usingRust = false
        private set
    /** Why the Kotlin port is ranking, or null while Rust ranks (and before the first preparation). */
    @Volatile var fallback: RankFallback? = null
        private set
    /** Which implementation ranked the most recent query ([Ranker.NONE] for blank queries and before any query). */
    @Volatile var lastRanker: Ranker = Ranker.NONE
        private set
    /** Requests answered with [Listener.onFailed] (counted when ranked, whether or not still current at delivery). */
    @Volatile var failureCount = 0
        private set

    override val hasPending: Boolean get() = pendingQuery != null

    /** Search opened: re-gather the facts and prepare the index in the background (registry.invalidate()). */
    override fun beginSession() {
        cancelPending()
        session++
        seq++
        background.execute { prepare() }
    }

    /** Search closed: nothing pending or in flight may reach the listener any more. */
    override fun endSession() { cancelPending(); session++; seq++ }

    /** Debounced query (search.js setQuery: clearTimeout + setTimeout 110). */
    override fun request(query: String) {
        ui.cancel(timer)
        pendingQuery = query
        ui.postDelayed(timer, DEBOUNCE_MS)
    }

    /** Run the pending query now (search.js flushRender). */
    override fun flush() {
        val q = pendingQuery ?: return
        ui.cancel(timer)
        pendingQuery = null
        val mySeq = ++seq
        val mySession = session
        background.execute {
            // answer() does not throw; the catch only guarantees the single delivery if failure reporting itself throws.
            val r = try { answer(q) } catch (t: Throwable) { failureCount++; lastRanker = Ranker.FAILED; null }
            ui.post {
                if (mySeq == seq && mySession == session) {
                    val l = listener
                    if (l != null) { if (r != null) l.onResults(q, r) else l.onFailed(q) }
                }
            }
        }
    }

    private fun cancelPending() { ui.cancel(timer); pendingQuery = null }

    // ---- background ----
    private fun ownsIndex(): Boolean = indexOwner?.get() === this

    /** Logs an internal failure once per stage and exception class (bounded); never the query or the exception message. */
    private fun report(stage: Stage, t: Throwable) {
        val key = "$stage:${t.javaClass.name}"
        if (failuresLogged.size < MAX_FAILURE_LOG_LINES && failuresLogged.add(key)) log("search $stage failed: ${t.javaClass.simpleName}")
    }

    /** Never throws. On failure no catalog or references survive and the session's queries are answered with onFailed. */
    private fun prepare() {
        snapshot = null; refs = null; fallbackEntries = null; prepareFailed = false
        try { prepareUnchecked() } catch (t: Throwable) {
            // Nothing from a previous session may survive a failed preparation (no stale catalog, no stale references).
            snapshot = null; refs = null; fallbackEntries = null; prepareFailed = true
            try { report(Stage.PREPARE, t); route(broken ?: RankFallback.PREPARE_FAILED, t) } catch (_: Throwable) {}
        }
    }

    private fun prepareUnchecked() {
        val installed = try { facts.installedApps() } catch (t: Throwable) { emptyList() }
        val aliases = SearchCatalog.mergeAliases(try { facts.aliases() } catch (t: Throwable) { emptyMap() })
        val shortcuts = SearchCatalog.shortcuts { k -> settingsCache.getOrPut(k) { try { facts.hasSettings(k) } catch (t: Throwable) { true } } }
        val extra = try { facts.extraEntries() } catch (t: Throwable) { emptyList() }
        val snap = SearchCatalog.build(installed, aliases, extra, shortcuts)
        snapshot = snap
        fallbackEntries = null
        refs = null
        val wire = snap.wire
        var error: Throwable? = null
        val why: RankFallback? = broken ?: when {
            !nativeAvailable() -> RankFallback.NATIVE_UNAVAILABLE
            wire == null -> RankFallback.CATALOG_NOT_WIRE_SAFE
            else -> synchronized(INDEX_LOCK) {
                try { refs = prepareNative(wire, snap.sources.size) } catch (t: Throwable) { error = t }
                if (refs == null) RankFallback.PREPARE_FAILED else null
            }
        }
        route(why, error)
    }

    private fun nativeAvailable(): Boolean = try { backend.available } catch (t: Throwable) { false }

    /** Caller holds [INDEX_LOCK]. Returns parsed references, or null when the backend rejected the catalog. */
    private fun prepareNative(wire: String, n: Int): IntArray? {
        val cached = lastRefs
        if (ownsIndex() && wire == lastSent && cached != null) return parseRefs(cached, n)
        // From here on the shared index no longer holds whatever it held before, whatever the outcome.
        indexOwner = WeakReference(this)
        lastSent = null; lastRefs = null
        val raw = backend.prepare(wire) ?: return null
        val parsed = parseRefs(raw, n) ?: return null
        lastSent = wire; lastRefs = raw
        return parsed
    }

    /** Records the route; logs only when it changes, so a broken native library is reported once, not per session or key. */
    private fun route(why: RankFallback?, error: Throwable? = null) {
        val changed = !routeReported || why != fallback
        routeReported = true
        fallback = why
        usingRust = why == null
        // Exception class only: a native message could echo the query.
        if (changed) log(if (why == null) "search ranking: Rust" else "search ranking: Kotlin fallback ($why)" +
            (error?.let { " after ${it.javaClass.simpleName}" } ?: ""))
    }

    private fun parseRefs(s: String, n: Int): IntArray? {
        if (s.isEmpty()) return IntArray(0)
        val parts = s.split(',')
        val out = IntArray(parts.size)
        for (i in parts.indices) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v < 0 || v >= n) return null
            out[i] = v
        }
        return out
    }

    /**
     * Ranks [q]: Rust, else Kotlin. Null = internal failure (no catalog, or Kotlin threw too). Never throws.
     * Blank queries rank to nothing in both (same norm as Rust, golden-tested) and need no catalog or JNI round trip.
     */
    private fun answer(q: String): List<SearchResult>? {
        if (SearchRanking.norm(q).isEmpty()) { lastRanker = Ranker.NONE; return emptyList() }
        if (snapshot == null && !prepareFailed) prepare()      // a request without beginSession
        val snap = snapshot ?: return failed()
        if (refs != null && !SearchCatalog.hasLoneSurrogate(q)) {
            // Native failures are handled inside rankRust (route switch, null). A throw here is a bug on the Kotlin side of
            // the Rust path: this query is answered by Kotlin and the route is unchanged.
            val r = try { rankRust(q, snap) } catch (t: Throwable) { report(Stage.RUST, t); null }
            if (r != null) { lastRanker = Ranker.RUST; return r }
        }
        val k = try { rankKotlin(q, snap) } catch (t: Throwable) { report(Stage.KOTLIN, t); null }
        if (k != null) { lastRanker = Ranker.KOTLIN; return k }
        return failed()
    }

    private fun failed(): List<SearchResult>? { failureCount++; lastRanker = Ranker.FAILED; return null }

    private fun rankKotlin(q: String, snap: SearchCatalog.Snapshot): List<SearchResult> {
        val (src, cand) = fallbackEntries ?: snap.fallbackEntries().also { fallbackEntries = it }
        return kotlinRank(cand, q, MAX_RESULTS).map { snap.resultFor(src[it]) }
    }

    /** Null when Rust cannot answer; the route has then been switched to Kotlin and the caller ranks with Kotlin. */
    private fun rankRust(q: String, snap: SearchCatalog.Snapshot): List<SearchResult>? = synchronized(INDEX_LOCK) {
        var rr = refs ?: return null
        if (!ownsIndex()) {
            // Another engine replaced the shared index after this session was prepared: re-send this catalog first.
            var error: Throwable? = null
            val again = try { prepareNative(snap.wire ?: return null, snap.sources.size) } catch (t: Throwable) { error = t; null }
            if (again == null) { refs = null; route(RankFallback.PREPARE_FAILED, error); return null }
            rr = again; refs = again
        }
        var error: Throwable? = null
        val r = try { backend.search(q, MAX_RESULTS) } catch (t: Throwable) { error = t; null }
        if (r == null) return fail(RankFallback.SEARCH_FAILED, error)
        if (r.isEmpty()) return emptyList()
        val parts = r.split(',')
        if (parts.size > MAX_RESULTS) return fail(RankFallback.INVALID_RESULT)
        val seen = HashSet<Int>(parts.size * 2)
        val out = ArrayList<SearchResult>(parts.size)
        for (p in parts) {
            val i = p.toIntOrNull()
            if (i == null || i < 0 || i >= rr.size || !seen.add(i)) return fail(RankFallback.INVALID_RESULT)
            out.add(snap.resultFor(snap.sources[rr[i]]))
        }
        out
    }

    private fun fail(why: RankFallback, error: Throwable? = null): List<SearchResult>? {
        broken = why
        refs = null
        route(why, error)
        return null
    }
}
