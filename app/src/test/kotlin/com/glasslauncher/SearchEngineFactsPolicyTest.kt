package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 follow-up: the fact policy of SearchEngine preparation (docs/nativeui/SEARCH.md "Fact policy"). The installed list is essential:
 * a throw is an explicit failure (onFailed), retried on the next open. Aliases, extra entries and per-kind settings availability are
 * optional: a throw leaves that fact out of this session only, is reported once without query text, and is not a request failure.
 */
class SearchEngineFactsPolicyTest {
    private class Facts : SearchFactsSource {
        var apps = listOf(InstalledApp("com.netflix.ninja", "Netflix"), InstalledApp("org.xbmc.kodi", "Kodi"))
        var appsThrow = false
        var aliasesThrow = false
        var extraThrow = false
        var settingsThrow = setOf<String>()
        val settingsAsked = ArrayList<String>()
        override fun installedApps(): List<InstalledApp> = if (appsThrow) throw IllegalStateException("pm secret") else apps
        override fun aliases(): Map<String, List<String>> =
            if (aliasesThrow) throw IllegalArgumentException("aliases secret") else mapOf("com.netflix.ninja" to listOf("films"))
        override fun hasSettings(kind: String): Boolean {
            settingsAsked.add(kind)
            if (kind in settingsThrow) throw SecurityException("settings secret")
            return kind == "wifi"
        }
        override fun extraEntries(): List<CatalogEntry> =
            if (extraThrow) throw UnsupportedOperationException("extra secret")
            else listOf(CatalogEntry("x:demo", "shortcut", "Zebra Demo", "", listOf("zebra"), "Glass", "settings", action = SearchAction.ToggleTheme))
    }

    private class FakeRust : RankBackend {
        var isAvailable = true
        val prepared = ArrayList<String>()
        val searched = ArrayList<String>()
        override val available: Boolean get() = isAvailable
        override fun prepare(wire: String): String? {
            prepared.add(wire)
            return (0 until wire.split(SearchCatalog.RS).size - 1).joinToString(",")
        }
        override fun search(query: String, max: Int): String? { searched.add(query); return "0" }
    }

    private val exec = TestSupport.ManualExecutor()
    private val ui = TestSupport.ManualUi()
    private val got = ArrayList<String>()
    private val logs = ArrayList<String>()

    private fun engine(backend: RankBackend, facts: SearchFactsSource): SearchEngine =
        SearchEngine(exec, ui, facts, backend, SearchRanking::query) { logs.add(it) }.also { e ->
            e.listener = object : SearchEngine.Listener {
                override fun onResults(query: String, results: List<SearchResult>) { got.add("ok:$query:" + results.joinToString("|") { it.title }) }
                override fun onFailed(query: String) { got.add("fail:$query") }
            }
        }

    private fun kotlinOnly(facts: SearchFactsSource) = engine(FakeRust().apply { isAvailable = false }, facts)

    private fun SearchEngine.open() { beginSession(); exec.runAll() }
    private fun SearchEngine.reopen() { endSession(); beginSession(); exec.runAll() }

    private fun SearchEngine.ask(q: String): String {
        got.clear(); request(q); flush(); exec.runAll(); ui.drain()
        assertEquals("exactly one delivery for <$q>: $got", 1, got.size)
        return got[0]
    }

    @Test fun essentialInstalledAppsFailureIsExplicitAndRetriedOnNextOpen() {
        val f = Facts().apply { appsThrow = true }
        val rust = FakeRust()
        val e = engine(rust, f)
        e.open()
        assertEquals(RankFallback.PREPARE_FAILED, e.fallback)
        assertEquals("not an empty catalog", "fail:kodi", e.ask("kodi"))
        assertEquals(Ranker.FAILED, e.lastRanker)
        assertTrue("nothing reached the native index", rust.prepared.isEmpty() && rust.searched.isEmpty())
        assertTrue(e.omittedFacts.isEmpty())

        f.appsThrow = false
        e.reopen()
        assertTrue(e.usingRust)
        assertNull(e.fallback)
        e.ask("kodi")
        assertEquals(Ranker.RUST, e.lastRanker)
        assertEquals(1, e.failureCount)
    }

    @Test fun emptyInstalledListIsAValidCatalogNotAFailure() {
        val f = Facts().apply { apps = emptyList() }
        val k = kotlinOnly(f)
        k.open()
        val r = k.ask("kodi")
        assertTrue(r, r.startsWith("ok:kodi:") && !r.contains("Kodi"))
        assertEquals(Ranker.KOTLIN, k.lastRanker)
        assertEquals(0, k.failureCount)
        assertTrue(k.omittedFacts.isEmpty())
    }

    @Test fun optionalAliasesFailureKeepsAppsSearchableAndIsRetried() {
        val f = Facts().apply { aliasesThrow = true }
        val rust = FakeRust()
        val e = engine(rust, f)
        e.open()
        assertTrue("a partial catalog still goes to Rust", e.usingRust)
        assertNull(e.fallback)
        assertEquals(setOf("aliases"), e.omittedFacts)
        assertEquals(1, rust.prepared.size)

        val k = kotlinOnly(f)
        k.open()
        assertTrue("found by name", k.ask("netflix").contains("Netflix"))
        assertFalse("configured alias left out this session", k.ask("films").contains("Netflix"))
        assertEquals(0, k.failureCount)

        f.aliasesThrow = false
        k.reopen()
        assertTrue(k.omittedFacts.isEmpty())
        assertTrue("retried on the next open", k.ask("films").contains("Netflix"))
    }

    @Test fun optionalExtraEntriesFailureContributesNothingAndIsRetried() {
        val f = Facts().apply { extraThrow = true }
        val k = kotlinOnly(f)
        k.open()
        assertEquals(setOf("extraEntries"), k.omittedFacts)
        assertFalse(k.ask("zebra").contains("Zebra Demo"))
        assertTrue(k.ask("kodi").contains("Kodi"))
        assertEquals(0, k.failureCount)

        f.extraThrow = false
        k.reopen()
        assertTrue(k.omittedFacts.isEmpty())
        assertTrue(k.ask("zebra").contains("Zebra Demo"))
    }

    @Test fun settingsFailureMeansUnavailableForThisSessionAndIsAskedAgain() {
        val f = Facts().apply { settingsThrow = setOf("wifi") }
        val k = kotlinOnly(f)
        k.open()
        assertEquals(setOf("settings:wifi"), k.omittedFacts)
        assertFalse("no possibly dead Wi-Fi shortcut", k.ask("wireless").contains("Wi-Fi"))
        assertTrue("main Settings needs no answer", k.ask("preferences").contains("Settings"))
        assertFalse(f.settingsAsked.contains("main"))

        f.settingsThrow = emptySet()
        k.reopen()
        assertTrue(k.omittedFacts.isEmpty())
        assertTrue("the failure was not cached", k.ask("wireless").contains("Wi-Fi"))
        val asked = f.settingsAsked.count { it == "wifi" }
        k.reopen()
        assertEquals("a real answer is cached, like registry.hasSettings", asked, f.settingsAsked.count { it == "wifi" })
        assertEquals(0, k.failureCount)
    }

    @Test fun optionalFailuresAreReportedOnceWithoutQueryTextAndAreNotRequestFailures() {
        val f = Facts().apply { aliasesThrow = true; extraThrow = true; settingsThrow = setOf("bluetooth") }
        val k = kotlinOnly(f)
        logs.clear()
        for (i in 0 until 5) {
            k.reopen()
            val r = k.ask("secret$i")
            assertTrue(r, r.startsWith("ok:secret$i:"))
        }
        assertEquals(0, k.failureCount)
        assertEquals(setOf("aliases", "extraEntries", "settings:bluetooth"), k.omittedFacts)
        assertEquals(logs.toString(), listOf(
            "search FACTS failed: IllegalArgumentException",
            "search FACTS failed: SecurityException",
            "search FACTS failed: UnsupportedOperationException",
        ), logs.filter { it.startsWith("search FACTS") })
        assertTrue(logs.toString(), logs.none { it.contains("secret") })
    }

    @Test fun essentialFailureAfterAPartialSessionLeavesNoStaleCatalogOrOmissions() {
        val f = Facts().apply { aliasesThrow = true }
        val k = kotlinOnly(f)
        k.open()
        assertEquals(setOf("aliases"), k.omittedFacts)
        assertTrue(k.ask("kodi").contains("Kodi"))

        f.appsThrow = true
        k.reopen()
        assertTrue(k.omittedFacts.isEmpty())
        assertEquals("fail:kodi", k.ask("kodi"))

        f.appsThrow = false; f.aliasesThrow = false
        k.reopen()
        assertTrue(k.ask("films").contains("Netflix"))
    }

    @Test fun essentialFailureDeliversOneOutcomeOnlyToTheCurrentRequest() {
        val f = Facts().apply { appsThrow = true }
        val e = engine(FakeRust(), f)
        e.open()
        got.clear()
        e.request("k"); e.flush(); e.request("ko"); e.flush(); exec.runAll(); ui.drain()
        assertEquals(listOf("fail:ko"), got)

        got.clear()
        e.request("kodi"); e.flush(); e.endSession(); exec.runAll(); ui.drain()
        assertTrue("nothing for a closed session: $got", got.isEmpty())

        e.beginSession(); e.request("kodi"); e.flush(); e.endSession(); e.beginSession(); exec.runAll(); ui.drain()
        assertTrue("nothing for a superseded session: $got", got.isEmpty())
    }
}
