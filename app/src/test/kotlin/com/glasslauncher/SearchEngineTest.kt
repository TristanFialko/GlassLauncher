package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {
    private class Facts(val apps: List<InstalledApp>, val settings: Set<String> = setOf("wifi", "bluetooth")) : SearchFactsSource {
        var calls = 0
        override fun installedApps(): List<InstalledApp> { calls++; return apps }
        override fun aliases() = mapOf("com.netflix.ninja" to listOf("films"))
        override fun hasSettings(kind: String) = kind in settings
    }

    private class NoRust : RankBackend {
        override val available = false
        override fun prepare(wire: String): String? = error("unused")
        override fun search(query: String, max: Int): String? = error("unused")
    }

    private val apps = listOf(InstalledApp("com.netflix.ninja", "Netflix"), InstalledApp("com.google.android.youtube.tv", "YouTube"))
    private val got = ArrayList<Pair<String, List<String>>>()
    private val exec = TestSupport.ManualExecutor()
    private val ui = TestSupport.ManualUi()

    private fun engine(backend: RankBackend = NoRust(), facts: Facts = Facts(apps)) =
        SearchEngine(exec, ui, facts, backend).also { e -> e.listener = SearchEngine.Listener { q, r -> got.add(q to r.map { it.title }) } }

    @Test fun debouncesTrailingAndDeliversOnUiThread() {
        val e = engine()
        e.beginSession(); exec.runAll()
        e.request("n"); ui.advance(50); e.request("netf"); ui.advance(109)
        assertEquals(0, exec.q.size)
        ui.advance(1)
        assertEquals(1, exec.q.size)
        exec.runAll()
        assertTrue(got.isEmpty())          // results are posted to the UI thread, not delivered from the worker
        ui.drain()
        assertEquals(listOf("netf" to listOf("Netflix")), got)
    }

    @Test fun staleResultsAreDropped() {
        val e = engine()
        e.beginSession(); exec.runAll()
        e.request("net"); ui.advance(110)            // q1 queued on the worker
        e.request("you"); e.flush()                  // q2 supersedes it before q1's result reaches the UI
        exec.runAll(); ui.drain()
        assertEquals(listOf("you" to listOf("YouTube")), got)
    }

    @Test fun endSessionDropsInFlightAndPending() {
        val e = engine()
        e.beginSession(); exec.runAll()
        e.request("net"); e.flush(); exec.runAll()
        e.endSession(); ui.drain()
        assertTrue(got.isEmpty())
        e.request("net"); e.endSession(); ui.advance(500)
        assertEquals(0, ui.pendingTimers); assertFalse(e.hasPending)
    }

    @Test fun flushWithNothingPendingDoesNothing() {
        val e = engine()
        e.flush()
        assertEquals(0, exec.q.size)
    }

    @Test fun blankQueryHasNoResultsAndAliasesAndShortcutsAreSearchable() {
        val e = engine()
        e.beginSession(); exec.runAll()
        e.request("  !! "); e.flush(); exec.runAll(); ui.drain()
        e.request("films"); e.flush(); exec.runAll(); ui.drain()
        e.request("wi-fi"); e.flush(); exec.runAll(); ui.drain()
        e.request("storage"); e.flush(); exec.runAll(); ui.drain()      // unavailable on this box
        assertEquals(listOf("  !! " to emptyList(), "films" to listOf("Netflix"), "wi-fi" to listOf("Wi-Fi"), "storage" to emptyList()), got)
    }

    @Test fun backgroundWorkOnlyRunsOnTheExecutor() {
        val facts = Facts(apps)
        val e = engine(facts = facts)
        e.beginSession()
        assertEquals(0, facts.calls)
        exec.runAll()
        assertEquals(1, facts.calls)
    }

    @Test fun activationActionsCarryTheRightTargets() {
        val e = engine(facts = Facts(apps, setOf("wifi")))
        val results = ArrayList<SearchResult>()
        e.listener = SearchEngine.Listener { _, r -> results.addAll(r) }
        e.beginSession(); exec.runAll()
        for (q in listOf("netflix", "wifi", "volume", "appearance", "control centre", "settings")) { e.request(q); e.flush(); exec.runAll(); ui.drain() }
        val actions = results.map { it.action }
        assertTrue(actions.contains(SearchAction.LaunchApp("com.netflix.ninja", "Netflix")))
        assertTrue(actions.contains(SearchAction.OpenSettings("wifi")))
        assertTrue(actions.contains(SearchAction.OpenControlCentre(true)))
        assertTrue(actions.contains(SearchAction.OpenControlCentre(false)))
        assertTrue(actions.contains(SearchAction.ToggleTheme))
        assertTrue(actions.contains(SearchAction.OpenSettings("main")))
    }

    @Test fun nativePathAndFallbackAgreeThroughTheEngine() {
        TestSupport.requireNative()
        val c = TestSupport.cases().first()
        val f = TestSupport.factsOf(c)
        val facts = object : SearchFactsSource {
            override fun installedApps() = f.installed
            override fun aliases() = f.aliases
            override fun hasSettings(kind: String) = kind in f.settings
        }
        val a = ArrayList<List<String>>(); val b = ArrayList<List<String>>()
        for ((backend, sink) in listOf<Pair<RankBackend, ArrayList<List<String>>>>(RustBackend to a, NoRust() to b)) {
            val ex = TestSupport.ManualExecutor(); val u = TestSupport.ManualUi()
            val eng = SearchEngine(ex, u, facts, backend)
            eng.listener = SearchEngine.Listener { _, r -> sink.add(r.map { it.title + "/" + it.tag }) }
            eng.beginSession(); ex.runAll()
            assertEquals(backend === RustBackend, eng.usingRust)
            for (q in listOf("set", "wi", "play", "you", "net", "vol", "x", "settings", "ntfx", "dark")) { eng.request(q); eng.flush(); ex.runAll(); u.drain() }
        }
        assertEquals(a, b)
        assertTrue(a.any { it.isNotEmpty() })
    }
}
