package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 failure contract: an internal failure is delivered as [SearchEngine.Listener.onFailed], never as an empty result
 * list; a legitimate no-match is still an empty list; stale/closed requests get nothing; failures are logged once per
 * stage and class, without query text or exception messages.
 */
class SearchEngineFailureTest {
    /** A list whose size/iteration throws: SearchCatalog.build fails after the facts call returned normally. */
    private class ExplodingList : AbstractList<InstalledApp>() {
        override val size: Int get() = throw IllegalStateException("boom secret")
        override fun get(index: Int): InstalledApp = throw IllegalStateException("boom secret")
    }

    private class Facts(var apps: List<InstalledApp>) : SearchFactsSource {
        var explode = false
        override fun installedApps(): List<InstalledApp> = if (explode) ExplodingList() else apps
        override fun aliases() = mapOf("com.netflix.ninja" to listOf("films"))
        override fun hasSettings(kind: String) = kind == "wifi" || kind == "bluetooth"
    }

    private class FakeRust : RankBackend {
        var isAvailable = true
        var answer: (String) -> String? = { "0" }
        val prepared = ArrayList<String>()
        val searched = ArrayList<String>()
        override val available: Boolean get() = isAvailable
        override fun prepare(wire: String): String? {
            prepared.add(wire)
            return (0 until wire.split(SearchCatalog.RS).size - 1).joinToString(",")
        }
        override fun search(query: String, max: Int): String? { searched.add(query); return answer(query) }
    }

    private val apps = listOf(InstalledApp("com.netflix.ninja", "Netflix"), InstalledApp("com.google.android.youtube.tv", "YouTube"))
    private val exec = TestSupport.ManualExecutor()
    private val ui = TestSupport.ManualUi()
    private val got = ArrayList<String>()
    private val logs = ArrayList<String>()
    private val throwingKotlin: (List<SearchRanking.Candidate>, String, Int) -> IntArray = { _, _, _ -> throw IllegalStateException("kotlin secret") }

    private fun engine(
        backend: RankBackend,
        facts: SearchFactsSource = Facts(apps),
        kotlin: (List<SearchRanking.Candidate>, String, Int) -> IntArray = SearchRanking::query,
    ): SearchEngine = SearchEngine(exec, ui, facts, backend, kotlin) { logs.add(it) }.also { e ->
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

    @Test fun unexpectedPrepareFailureIsDeliveredAsFailureAndRetriedNextSession() {
        val facts = Facts(apps).apply { explode = true }
        val rust = FakeRust()
        val e = engine(rust, facts)
        e.beginSession(); exec.runAll()          // must not throw out of the executor task
        assertEquals(RankFallback.PREPARE_FAILED, e.fallback)
        assertFalse(e.usingRust)
        assertEquals("fail:net", e.ask("net"))
        assertEquals(Ranker.FAILED, e.lastRanker)
        assertEquals("blank queries never fail", "ok: :", e.ask(" "))
        assertTrue(rust.prepared.isEmpty())
        assertTrue(rust.searched.isEmpty())
        assertEquals(1, e.failureCount)

        facts.explode = false
        e.endSession(); e.beginSession(); exec.runAll()
        assertTrue(e.usingRust)
        assertEquals("ok:net:Netflix", e.ask("net"))
        assertEquals(Ranker.RUST, e.lastRanker)
    }

    @Test fun prepareFailureAfterAGoodSessionLeavesNoStaleCatalog() {
        val facts = Facts(apps + InstalledApp("org.xbmc.kodi", "Kodi"))
        val rust = FakeRust().apply { isAvailable = false }
        val e = engine(rust, facts)
        e.beginSession(); exec.runAll()
        assertTrue(e.ask("kodi").startsWith("ok:kodi:Kodi"))
        facts.explode = true
        e.endSession(); e.beginSession(); exec.runAll()
        assertEquals("the previous session's catalog is not reused", "fail:kodi", e.ask("kodi"))
    }

    @Test fun kotlinFallbackFailureIsTerminal() {
        val e = engine(FakeRust().apply { isAvailable = false }, kotlin = throwingKotlin)
        e.beginSession(); exec.runAll()
        assertEquals(RankFallback.NATIVE_UNAVAILABLE, e.fallback)
        assertEquals("fail:net", e.ask("net"))
        assertEquals(Ranker.FAILED, e.lastRanker)
        assertEquals(1, e.failureCount)
    }

    @Test fun rustFailureThenKotlinFailureIsTerminal() {
        val rust = FakeRust().apply { answer = { null } }
        val e = engine(rust, kotlin = throwingKotlin)
        e.beginSession(); exec.runAll()
        assertEquals("fail:net", e.ask("net"))
        assertEquals(RankFallback.SEARCH_FAILED, e.fallback)
        assertEquals(Ranker.FAILED, e.lastRanker)
    }

    @Test fun rustFailsThenKotlinSucceeds() {
        val rust = FakeRust().apply { answer = { throw RuntimeException("native secret") } }
        val e = engine(rust)
        e.beginSession(); exec.runAll()
        val r = e.ask("net")
        assertTrue(r, r.startsWith("ok:net:") && r.contains("Netflix"))
        assertEquals(Ranker.KOTLIN, e.lastRanker)
        assertEquals(RankFallback.SEARCH_FAILED, e.fallback)
        assertEquals(0, e.failureCount)
        assertTrue("no exception message (could echo the query) in logs: $logs", logs.none { it.contains("secret") })
    }

    @Test fun legitimateNoMatchIsAnEmptyResultNotAFailure() {
        val rust = FakeRust().apply { answer = { "" } }
        val e = engine(rust)
        e.beginSession(); exec.runAll()
        assertEquals("ok:qqqq:", e.ask("qqqq"))
        assertEquals(Ranker.RUST, e.lastRanker)
        val k = engine(FakeRust().apply { isAvailable = false })
        k.beginSession(); exec.runAll()
        assertEquals("ok:qqqq:", k.ask("qqqq"))
        assertEquals(Ranker.KOTLIN, k.lastRanker)
        assertEquals(0, e.failureCount + k.failureCount)
    }

    @Test fun supersededOrClosedRequestsGetNoFailureDelivery() {
        val e = engine(FakeRust().apply { isAvailable = false }, kotlin = throwingKotlin)
        e.beginSession(); exec.runAll()
        got.clear()
        e.request("n"); e.flush(); e.request("ne"); e.flush(); exec.runAll(); ui.drain()
        assertEquals(listOf("fail:ne"), got)

        got.clear()
        e.request("net"); e.flush(); e.endSession(); exec.runAll(); ui.drain()
        assertTrue(got.toString(), got.isEmpty())

        e.beginSession(); e.request("net"); e.flush(); e.endSession(); e.beginSession(); exec.runAll(); ui.drain()
        assertTrue("closed and reopened while failing: nothing from the old session", got.isEmpty())
        assertEquals("fail:net", e.ask("net"))
    }

    @Test fun failuresAreReportedOnceAndNeverWithQueryText() {
        val e = engine(FakeRust().apply { isAvailable = false }, kotlin = throwingKotlin)
        e.beginSession(); exec.runAll()
        logs.clear()
        for (i in 0 until 20) assertEquals("fail:secret$i", e.ask("secret$i"))
        e.endSession(); e.beginSession(); exec.runAll()
        for (i in 0 until 5) e.ask("secret$i")
        assertEquals(25, e.failureCount)
        assertEquals(listOf("search KOTLIN failed: IllegalStateException"), logs)

        val facts = Facts(apps).apply { explode = true }
        val p = engine(FakeRust(), facts)
        logs.clear()
        for (i in 0 until 5) { p.endSession(); p.beginSession(); exec.runAll(); p.ask("secret$i") }
        assertEquals("one PREPARE line + one route line: $logs", 2, logs.size)
        assertTrue(logs.toString(), logs.none { it.contains("secret") || it.contains("boom") })
    }
}
