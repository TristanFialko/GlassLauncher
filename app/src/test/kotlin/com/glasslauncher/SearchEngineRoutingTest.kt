package com.glasslauncher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2: Rust is the production ranker; the Kotlin port is an explicit, observable fallback. These tests pin which
 * implementation ranks, why the engine falls back, that a native failure never turns into empty or reordered results,
 * and that a refreshed (or replaced) catalog is re-sent to the native index before Rust ranks again.
 */
class SearchEngineRoutingTest {
    private class Facts(var apps: List<InstalledApp>) : SearchFactsSource {
        override fun installedApps() = apps
        override fun aliases() = mapOf("com.netflix.ninja" to listOf("films"))
        override fun hasSettings(kind: String) = kind == "wifi" || kind == "bluetooth"
    }

    /** Records every native call. Refs keep every record (like Rust for a fully visible catalog); search answers [answer]. */
    private class FakeRust : RankBackend {
        var isAvailable = true
        var availableThrows = false
        var prepareMode = "ok"             // ok | null | throw | garbage
        var answer: (String) -> String? = { "0" }
        val prepared = ArrayList<String>()
        val searched = ArrayList<String>()
        override val available: Boolean get() { if (availableThrows) throw UnsatisfiedLinkError("no libglass"); return isAvailable }
        override fun prepare(wire: String): String? {
            prepared.add(wire)
            return when (prepareMode) {
                "null" -> null
                "throw" -> throw IllegalStateException("prepare failed")
                "garbage" -> "0,x"
                else -> (0 until wire.split(SearchCatalog.RS).size - 1).joinToString(",")
            }
        }
        override fun search(query: String, max: Int): String? { searched.add(query); return answer(query) }
    }

    private val apps = listOf(InstalledApp("com.netflix.ninja", "Netflix"), InstalledApp("com.google.android.youtube.tv", "YouTube"))
    private val exec = TestSupport.ManualExecutor()
    private val ui = TestSupport.ManualUi()
    private val got = ArrayList<Pair<String, List<String>>>()
    private val logs = ArrayList<String>()

    private fun engine(backend: RankBackend, facts: SearchFactsSource = Facts(apps)) =
        SearchEngine(exec, ui, facts, backend) { logs.add(it) }.also { e -> e.listener = SearchEngine.Listener { q, r -> got.add(q to r.map { it.title }) } }

    private fun SearchEngine.ask(q: String): List<String> {
        got.clear(); request(q); flush(); exec.runAll(); ui.drain()
        assertEquals("one delivery for <$q>", 1, got.size)
        return got[0].second
    }

    /** What the Kotlin port alone returns for [q] on the default catalog. */
    private fun kotlinOnly(q: String): List<String> {
        val none = FakeRust().apply { isAvailable = false }
        val ex = TestSupport.ManualExecutor()
        val u = TestSupport.ManualUi()
        val out = ArrayList<List<String>>()
        val k = SearchEngine(ex, u, Facts(apps), none)
        k.listener = SearchEngine.Listener { _, r -> out.add(r.map { it.title }) }
        k.beginSession(); ex.runAll(); k.request(q); k.flush(); ex.runAll(); u.drain()
        return out.single()
    }

    @Test fun productionDefaultBackendIsRust() {
        val e = SearchEngine(exec, ui, Facts(apps))
        val f = SearchEngine::class.java.getDeclaredField("backend").apply { isAccessible = true }
        assertTrue(f.get(e) === RustBackend)
    }

    @Test fun queriesAreRankedByRust() {
        val rust = FakeRust().apply { answer = { "1,0" } }
        val e = engine(rust)
        e.beginSession(); exec.runAll()
        assertTrue(e.usingRust); assertNull(e.fallback)
        val r = e.ask("qqqq")                    // Kotlin would find nothing: two results prove Rust ranked
        assertEquals(2, r.size)
        assertEquals(listOf("qqqq"), rust.searched)
        assertEquals(Ranker.RUST, e.lastRanker)
        assertEquals(listOf("search ranking: Rust"), logs)
    }

    @Test fun nativeUnavailableFallsBackToKotlinAndSaysWhy() {
        for (throws in listOf(false, true)) {
            logs.clear()
            val rust = FakeRust().apply { isAvailable = false; availableThrows = throws }
            val e = engine(rust)
            e.beginSession(); exec.runAll()
            assertFalse(e.usingRust); assertEquals(RankFallback.NATIVE_UNAVAILABLE, e.fallback)
            assertEquals(kotlinOnly("net"), e.ask("net"))
            assertEquals(Ranker.KOTLIN, e.lastRanker)
            assertTrue(rust.prepared.isEmpty() && rust.searched.isEmpty())
            assertEquals(1, logs.size); assertTrue(logs[0], logs[0].contains("NATIVE_UNAVAILABLE"))
        }
    }

    @Test fun catalogThatCannotCrossJniFallsBack() {
        val rust = FakeRust()
        val e = engine(rust, Facts(apps + InstalledApp("com.bad\u001fpkg", "Bad")))
        e.beginSession(); exec.runAll()
        assertEquals(RankFallback.CATALOG_NOT_WIRE_SAFE, e.fallback)
        assertEquals(kotlinOnly("net"), e.ask("net"))
        assertTrue(rust.prepared.isEmpty() && rust.searched.isEmpty())
    }

    @Test fun failedPreparationFallsBackAndIsRetriedNextSession() {
        for (mode in listOf("null", "throw", "garbage")) {
            logs.clear()
            val rust = FakeRust().apply { prepareMode = mode }
            val e = engine(rust)
            e.beginSession(); exec.runAll()
            assertEquals(mode, RankFallback.PREPARE_FAILED, e.fallback)
            assertEquals(kotlinOnly("net"), e.ask("net"))
            assertTrue(rust.searched.isEmpty())
            rust.prepareMode = "ok"
            e.endSession(); e.beginSession(); exec.runAll()
            assertEquals(mode, 2, rust.prepared.size)
            assertTrue(e.usingRust)
            assertEquals(mode, 2, logs.size)            // one line per change of route
        }
    }

    @Test fun failedSearchNeverDeliversEmptyOrDifferentResults() {
        val failures = listOf<(String) -> String?>({ null }, { throw RuntimeException("jni") })
        for (fail in failures) {
            logs.clear()
            val rust = FakeRust().apply { answer = fail }
            val e = engine(rust)
            e.beginSession(); exec.runAll()
            assertEquals(kotlinOnly("net"), e.ask("net"))          // the failing query itself is answered by Kotlin
            assertTrue(kotlinOnly("net").contains("Netflix"))   // "net" also matches the Wi-Fi shortcut ("network")
            assertEquals(RankFallback.SEARCH_FAILED, e.fallback); assertFalse(e.usingRust)
            assertEquals(listOf("YouTube"), e.ask("you"))
            e.endSession(); e.beginSession(); exec.runAll()
            assertEquals(listOf("Netflix"), e.ask("netf"))
            assertEquals("Rust is not asked again by this engine", 1, rust.searched.size)
            assertEquals(listOf("search ranking: Rust"), logs.take(1))
            assertEquals("no per-keystroke or per-session log lines", 2, logs.size)
        }
    }

    @Test fun invalidResultIndicesFallBackToKotlin() {
        val bad = listOf("x", "-1", "99999", "0,0", "0,,1", "1,", (0 until 25).joinToString(","))
        for (answer in bad) {
            val rust = FakeRust().apply { this.answer = { answer } }
            val e = engine(rust)
            e.beginSession(); exec.runAll()
            assertEquals("answer <$answer>", kotlinOnly("net"), e.ask("net"))
            assertEquals("answer <$answer>", RankFallback.INVALID_RESULT, e.fallback)
            assertEquals(Ranker.KOTLIN, e.lastRanker)
        }
    }

    @Test fun emptyRustAnswerIsAValidEmptyRanking() {
        val rust = FakeRust().apply { answer = { "" } }
        val e = engine(rust)
        e.beginSession(); exec.runAll()
        assertEquals(emptyList<String>(), e.ask("net"))
        assertTrue(e.usingRust); assertEquals(Ranker.RUST, e.lastRanker)
    }

    @Test fun blankQueriesNeverReachRust() {
        val rust = FakeRust()
        val e = engine(rust)
        e.beginSession(); exec.runAll()
        for (q in listOf("", " ", "   ", "\t", "!!", " - ")) {
            assertEquals(q, emptyList<String>(), e.ask(q))
            assertEquals(Ranker.NONE, e.lastRanker)
        }
        assertTrue(rust.searched.isEmpty())
    }

    @Test fun loneSurrogateQueriesUseKotlinWithoutChangingTheRoute() {
        val rust = FakeRust()
        val e = engine(rust)
        e.beginSession(); exec.runAll()
        e.ask("a\ud83c")
        assertEquals(Ranker.KOTLIN, e.lastRanker)
        assertTrue(rust.searched.isEmpty())
        assertTrue(e.usingRust); assertNull(e.fallback)
        e.ask("net")
        assertEquals(Ranker.RUST, e.lastRanker)
        assertEquals(listOf("net"), rust.searched)
    }

    @Test fun refreshedCatalogIsSentToRustBeforeRanking() {
        val rust = FakeRust()
        val facts = Facts(apps)
        val e = engine(rust, facts)
        e.beginSession(); exec.runAll()
        e.endSession(); e.beginSession(); exec.runAll()
        assertEquals("unchanged catalog is not re-sent", 1, rust.prepared.size)
        facts.apps = apps + InstalledApp("com.example.kodi", "Kodi")
        e.endSession(); e.beginSession(); exec.runAll()
        assertEquals(2, rust.prepared.size)
        assertTrue(rust.prepared[1].contains("Kodi")); assertFalse(rust.prepared[0].contains("Kodi"))
        rust.answer = { q -> if (q == "kodi") "0" else "" }
        assertEquals(1, e.ask("kodi").size)
        // and a refresh whose preparation fails must not let Rust rank against the previous catalog
        facts.apps = apps
        rust.prepareMode = "null"
        e.endSession(); e.beginSession(); exec.runAll()
        assertEquals(RankFallback.PREPARE_FAILED, e.fallback)
        assertEquals(kotlinOnly("net"), e.ask("net"))
        assertEquals(listOf("kodi"), rust.searched)
    }

    @Test fun anotherEngineReplacingTheSharedIndexForcesARePrepare() {
        val rust = FakeRust()
        val a = engine(rust, Facts(apps))
        val b = engine(rust, Facts(listOf(InstalledApp("com.example.kodi", "Kodi"))))
        a.beginSession(); exec.runAll()
        b.beginSession(); exec.runAll()
        assertEquals(2, rust.prepared.size)
        a.ask("net")
        assertEquals("A re-sent its catalog before searching", 3, rust.prepared.size)
        assertEquals(rust.prepared[0], rust.prepared[2])
        assertTrue(a.usingRust)
        a.endSession(); a.beginSession(); exec.runAll()
        assertEquals("A owns the index again, so its unchanged catalog is reused", 3, rust.prepared.size)
    }

    @Test fun debounceSendsOnlyTheLastQueryToRust() {
        val rust = FakeRust()
        val e = engine(rust)
        e.beginSession(); exec.runAll()
        e.request("n"); ui.advance(50); e.request("ne"); ui.advance(50); e.request("net"); ui.advance(109)
        assertEquals(0, exec.q.size)
        ui.advance(1); exec.runAll(); ui.drain()
        assertEquals(listOf("net"), rust.searched)
        assertEquals(listOf("net"), got.map { it.first })
    }

    @Test fun rankingRunsOnTheBackgroundExecutorOnly() {
        val rust = FakeRust()
        val e = engine(rust)
        e.beginSession()
        assertTrue(rust.prepared.isEmpty())
        exec.runAll()
        e.request("net"); e.flush()
        assertTrue(rust.searched.isEmpty())
        exec.runAll()
        assertEquals(1, rust.searched.size)
    }

    // ---- full golden parity through the production engine ----

    private fun file(rel: String): File {
        var d: File? = File("").absoluteFile
        while (d != null) { val f = File(d, rel); if (f.exists()) return f; d = d.parentFile }
        error("$rel not found")
    }

    @Suppress("UNCHECKED_CAST")
    private fun goldenThroughEngine(backend: RankBackend, expectRust: Boolean) {
        val root = MiniJson.parse(file("tools/parity/fixtures/ranking-golden.json").readText(Charsets.UTF_8)) as Map<String, Any?>
        val titles = (root["entries"] as List<Map<String, Any?>>).map { it["title"] as String }
        val cases = (root["cases"] as List<List<Any?>>).map { c -> c[0] as String to (c[1] as String).let { s -> if (s.isEmpty()) emptyList() else s.split(',').map { it.toInt() } } }
        assertEquals(2522, cases.size)
        val f = TestSupport.factsOf(TestSupport.cases().first())
        val facts = object : SearchFactsSource {
            override fun installedApps() = f.installed
            override fun aliases() = f.aliases
            override fun hasSettings(kind: String) = kind in f.settings
            override fun extraEntries() = f.extra
        }
        val e = engine(backend, facts)
        e.beginSession(); exec.runAll()
        assertEquals(expectRust, e.usingRust)
        val bad = ArrayList<String>()
        var rustRanked = 0
        for ((q, want) in cases) {
            val r = e.ask(q)
            if (e.lastRanker == Ranker.RUST) rustRanked++
            if (r != want.map { titles[it] }) bad.add("<$q>: expected ${want.map { titles[it] }} but was $r")
        }
        assertTrue("${bad.size} of ${cases.size} differ; first:\n" + bad.take(8).joinToString("\n"), bad.isEmpty())
        assertEquals(expectRust, e.usingRust)
        if (expectRust) assertTrue("Rust ranked $rustRanked of ${cases.size}", rustRanked > 2000) else assertEquals(0, rustRanked)
    }

    @Test fun goldenQueriesThroughTheEngineWithRust() {
        TestSupport.requireNative()
        goldenThroughEngine(RustBackend, expectRust = true)
    }

    @Test fun goldenQueriesThroughTheEngineWithTheKotlinFallback() {
        goldenThroughEngine(FakeRust().apply { isAvailable = false }, expectRust = false)
    }
}
