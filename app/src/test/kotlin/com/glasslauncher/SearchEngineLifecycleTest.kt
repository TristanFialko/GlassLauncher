package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 lifecycle: the shared native index, catalog refreshes, failed preparations and session boundaries, driven
 * deterministically by the manual executor and UI scheduler.
 */
class SearchEngineLifecycleTest {
    private class Facts(var apps: List<InstalledApp>) : SearchFactsSource {
        var explode = false
        override fun installedApps(): List<InstalledApp> = if (explode) throw IllegalStateException("pm") else apps
        override fun aliases() = emptyMap<String, List<String>>()
        override fun hasSettings(kind: String) = kind == "wifi"
    }

    /** Tracks which catalog the (single, shared) native index currently holds when each search runs. */
    private class FakeRust : RankBackend {
        var isAvailable = true
        var prepareMode = "ok"             // ok | null | throw
        var answer: (String) -> String? = { "0" }
        var current: String? = null
        val prepared = ArrayList<String>()
        val searchedAgainst = ArrayList<String?>()
        override val available: Boolean get() = isAvailable
        override fun prepare(wire: String): String? {
            prepared.add(wire)
            current = null
            return when (prepareMode) {
                "null" -> null
                "throw" -> throw IllegalStateException("prepare")
                else -> { current = wire; (0 until wire.split(SearchCatalog.RS).size - 1).joinToString(",") }
            }
        }
        override fun search(query: String, max: Int): String? { searchedAgainst.add(current); return answer(query) }
    }

    private val exec = TestSupport.ManualExecutor()
    private val ui = TestSupport.ManualUi()
    private val got = ArrayList<String>()
    private val logs = ArrayList<String>()

    private fun engine(backend: RankBackend, facts: SearchFactsSource): SearchEngine =
        SearchEngine(exec, ui, facts, backend) { logs.add(it) }.also { e ->
            e.listener = object : SearchEngine.Listener {
                override fun onResults(query: String, results: List<SearchResult>) { got.add("ok:$query:" + results.joinToString("|") { it.title }) }
                override fun onFailed(query: String) { got.add("fail:$query") }
            }
        }

    private fun SearchEngine.ask(q: String): String {
        got.clear(); request(q); flush(); exec.runAll(); ui.drain()
        assertEquals("exactly one delivery for <$q>: $got", 1, got.size)
        return got[0]
    }

    private fun SearchEngine.open() { beginSession(); exec.runAll() }

    @Test fun twoEnginesNeverRankAgainstEachOthersIndex() {
        val rust = FakeRust()
        val a = engine(rust, Facts(listOf(InstalledApp("a.pkg", "Alpha"))))
        val b = engine(rust, Facts(listOf(InstalledApp("b.pkg", "Beta"))))
        a.open()
        val wireA = rust.prepared.last()
        b.open()
        val wireB = rust.prepared.last()
        assertFalse(wireA == wireB)
        for (round in 0 until 3) {
            assertEquals("ok:x:Alpha", a.ask("x"))
            assertEquals(wireA, rust.searchedAgainst.last())
            assertEquals("ok:x:Beta", b.ask("x"))
            assertEquals(wireB, rust.searchedAgainst.last())
        }
        assertEquals("each switch re-sends the asking engine's catalog", 2 + 6, rust.prepared.size)
        assertTrue(a.usingRust && b.usingRust)
    }

    @Test fun unchangedCatalogReusesReferencesAcrossSessions() {
        val rust = FakeRust()
        val e = engine(rust, Facts(listOf(InstalledApp("a.pkg", "Alpha"))))
        for (i in 0 until 5) { e.open(); assertEquals("ok:x:Alpha", e.ask("x")); e.endSession() }
        assertEquals(1, rust.prepared.size)
    }

    @Test fun newAppsAppearOnTheNextOpen() {
        val rust = FakeRust().apply { answer = { "0,1" } }
        val facts = Facts(listOf(InstalledApp("a.pkg", "Alpha")))
        val e = engine(rust, facts)
        e.open()
        val first = e.ask("x")
        assertTrue(first, first.startsWith("ok:x:Alpha") && !first.contains("Kodi"))
        e.endSession()
        facts.apps = listOf(InstalledApp("a.pkg", "Alpha"), InstalledApp("k.pkg", "Kodi"))
        e.open()
        assertEquals(2, rust.prepared.size)
        assertEquals("installed apps lead the catalog, so refs 0,1 are Alpha, Kodi", "ok:x:Alpha|Kodi", e.ask("x"))
        assertEquals(rust.prepared.last(), rust.searchedAgainst.last())
    }

    @Test fun failedPrepareLeavesNoStaleReferences() {
        val rust = FakeRust()
        val facts = Facts(listOf(InstalledApp("a.pkg", "Alpha")))
        val e = engine(rust, facts)
        e.open()
        e.ask("x")
        val searches = rust.searchedAgainst.size
        e.endSession()

        // backend rejects the next catalog: Kotlin ranks, Rust is never asked with the old references
        facts.apps = listOf(InstalledApp("k.pkg", "Kodi"))
        rust.prepareMode = "null"
        e.open()
        assertEquals(RankFallback.PREPARE_FAILED, e.fallback)
        assertTrue(e.ask("kodi").startsWith("ok:kodi:Kodi"))
        assertEquals(searches, rust.searchedAgainst.size)
        e.endSession()

        // unexpected failure before the native call: onFailed, still no native search
        facts.explode = true
        e.open()
        assertEquals("fail:kodi", e.ask("kodi"))
        assertEquals(searches, rust.searchedAgainst.size)
        e.endSession()

        // everything healthy again: Rust, against the current catalog
        facts.explode = false
        rust.prepareMode = "ok"
        e.open()
        assertTrue(e.usingRust)
        assertEquals("ok:kodi:Kodi", e.ask("kodi"))
        assertEquals(rust.prepared.last(), rust.searchedAgainst.last())
    }

    @Test fun rePrepareAfterTheIndexWasReplacedCanFail() {
        val rust = FakeRust()
        val a = engine(rust, Facts(listOf(InstalledApp("a.pkg", "Alpha"))))
        val b = engine(rust, Facts(listOf(InstalledApp("b.pkg", "Beta"))))
        a.open(); b.open()
        rust.prepareMode = "throw"
        val searches = rust.searchedAgainst.size
        val r = a.ask("x")
        assertTrue(r, r.startsWith("ok:x:"))
        assertEquals("never ranked against b's index", searches, rust.searchedAgainst.size)
        assertEquals(RankFallback.PREPARE_FAILED, a.fallback)
        assertEquals(Ranker.KOTLIN, a.lastRanker)
    }

    @Test fun closingInvalidatesPendingAndInFlightDelivery() {
        val rust = FakeRust()
        val e = engine(rust, Facts(listOf(InstalledApp("a.pkg", "Alpha"))))
        e.open(); got.clear()

        e.request("a"); e.endSession()                       // debounce timer pending
        assertEquals(0, ui.pendingTimers)
        assertFalse(e.hasPending)
        ui.advance(500); exec.runAll(); ui.drain()

        e.open(); e.request("a"); e.flush(); e.endSession()  // queued on the executor
        exec.runAll(); ui.drain()

        e.open(); e.request("a"); e.flush(); exec.runAll()   // result already posted to the UI thread
        e.endSession(); ui.drain()
        assertTrue(got.toString(), got.isEmpty())
    }

    @Test fun noInFlightResultReachesALaterSessionEvenForTheSameQuery() {
        val rust = FakeRust()
        val e = engine(rust, Facts(listOf(InstalledApp("a.pkg", "Alpha"))))
        e.open(); got.clear()
        e.request("a"); e.flush()
        e.endSession(); e.beginSession()
        e.request("a"); e.flush()
        exec.runAll(); ui.drain()
        assertEquals(listOf("ok:a:Alpha"), got)
    }

    @Test fun repeatedOpenCloseLeavesNothingBehind() {
        val rust = FakeRust()
        val e = engine(rust, Facts(listOf(InstalledApp("a.pkg", "Alpha"))))
        logs.clear()
        for (i in 0 until 100) {
            e.beginSession(); e.request("a"); if (i % 2 == 0) e.flush()
            if (i % 3 == 0) exec.runAll()
            e.endSession()
            exec.runAll(); ui.drain()
        }
        assertEquals(0, ui.pendingTimers)
        assertTrue(ui.posted.isEmpty())
        assertTrue(exec.q.isEmpty())
        assertTrue(got.toString(), got.isEmpty())
        assertEquals("one native preparation for an unchanged catalog", 1, rust.prepared.size)
        assertEquals("one route line, not one per session: $logs", 1, logs.size)
    }

    @Test fun stickyFailuresLastForTheEngineAndPerSessionOnesDoNot() {
        val rust = FakeRust().apply { answer = { null } }
        val facts = Facts(listOf(InstalledApp("a.pkg", "Alpha")))
        val e = engine(rust, facts)
        e.open()
        assertTrue(e.ask("alpha").startsWith("ok:alpha:Alpha"))
        assertEquals(RankFallback.SEARCH_FAILED, e.fallback)
        rust.answer = { "0" }
        val searches = rust.searchedAgainst.size
        for (i in 0 until 3) { e.endSession(); e.open(); e.ask("alpha") }
        assertEquals("SEARCH_FAILED is sticky for this engine", searches, rust.searchedAgainst.size)
        assertEquals(RankFallback.SEARCH_FAILED, e.fallback)

        val fresh = engine(rust, facts)                      // activity recreated
        fresh.open()
        assertTrue(fresh.usingRust)
        assertEquals("ok:alpha:Alpha", fresh.ask("alpha"))
        assertEquals(Ranker.RUST, fresh.lastRanker)

        // NATIVE_UNAVAILABLE and PREPARE_FAILED are re-evaluated by every session
        rust.isAvailable = false
        fresh.endSession(); fresh.open()
        assertEquals(RankFallback.NATIVE_UNAVAILABLE, fresh.fallback)
        rust.isAvailable = true
        fresh.endSession(); fresh.open()
        assertTrue(fresh.usingRust)
    }
}
