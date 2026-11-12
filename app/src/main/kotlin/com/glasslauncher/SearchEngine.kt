package com.glasslauncher

import android.os.Handler
import android.os.Looper
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

/** The native ranking calls; RustCore in production, a fake in tests. */
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
 */
class SearchEngine(
    private val background: Executor,
    private val ui: UiScheduler,
    private val facts: SearchFactsSource,
    private val backend: RankBackend = RustBackend,
) : QuerySource {
    fun interface Listener { fun onResults(query: String, results: List<SearchResult>) }

    companion object {
        const val DEBOUNCE_MS = 110L
        const val MAX_RESULTS = 24
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
    private var fallback: Pair<List<SearchCatalog.Source>, List<SearchRanking.Candidate>>? = null
    private var lastSent: String? = null
    private var rustBroken = false
    private val settingsCache = HashMap<String, Boolean>()
    @Volatile var usingRust = false
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
            val r = try { rank(q) } catch (t: Throwable) { emptyList() }
            ui.post { if (mySeq == seq && mySession == session) listener?.onResults(q, r) }
        }
    }

    private fun cancelPending() { ui.cancel(timer); pendingQuery = null }

    // ---- background ----
    private fun prepare() {
        val installed = try { facts.installedApps() } catch (t: Throwable) { emptyList() }
        val aliases = SearchCatalog.mergeAliases(try { facts.aliases() } catch (t: Throwable) { emptyMap() })
        val shortcuts = SearchCatalog.shortcuts { k -> settingsCache.getOrPut(k) { try { facts.hasSettings(k) } catch (t: Throwable) { true } } }
        val extra = try { facts.extraEntries() } catch (t: Throwable) { emptyList() }
        val snap = SearchCatalog.build(installed, aliases, extra, shortcuts)
        snapshot = snap
        fallback = null
        refs = null
        val wire = snap.wire
        if (!rustBroken && backend.available && wire != null) {
            val r = if (wire == lastSent) lastRefs else backend.prepare(wire)?.also { lastSent = wire; lastRefs = it }
            refs = r?.let { parseRefs(it, snap.sources.size) }
        }
        usingRust = refs != null
    }
    private var lastRefs: String = ""

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

    private fun rank(q: String): List<SearchResult> {
        if (snapshot == null) prepare()
        val snap = snapshot ?: return emptyList()
        if (SearchRanking.norm(q).isEmpty()) return emptyList()
        val rr = refs
        if (rr != null && !rustBroken && !SearchCatalog.hasLoneSurrogate(q)) {
            val r = backend.search(q, MAX_RESULTS)
            if (r != null) {
                if (r.isEmpty()) return emptyList()
                val out = ArrayList<SearchResult>(MAX_RESULTS)
                for (p in r.split(',')) {
                    val i = p.toIntOrNull()
                    if (i == null || i < 0 || i >= rr.size) { rustBroken = true; refs = null; usingRust = false; return rank(q) }
                    out.add(snap.resultFor(snap.sources[rr[i]]))
                }
                return out
            }
            rustBroken = true; refs = null; usingRust = false
        }
        val (src, cand) = fallback ?: snap.fallbackEntries().also { fallback = it }
        val order = SearchRanking.query(cand, q, MAX_RESULTS)
        return order.map { snap.resultFor(src[it]) }
    }
}
