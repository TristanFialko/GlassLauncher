package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the SettingsLauncher.has caveat: native Search used `has`, which turns a PackageManager error into `false`; SearchEngine
 * then cached that as a real "unavailable" answer for the engine's lifetime, so the documented "not cached, asked again on the next
 * open" policy never applied in production. NativeSearchFacts + SettingsLauncher's throwing rule must keep the error visible.
 */
class NativeSearchFactsTest {
    private class KotlinOnly : RankBackend {
        override val available: Boolean get() = false
        override fun prepare(wire: String): String? = null
        override fun search(query: String, max: Int): String? = null
    }

    private val exec = TestSupport.ManualExecutor()
    private val ui = TestSupport.ManualUi()
    private val got = ArrayList<String>()

    private var broken = true
    private var asked = 0
    /** Stands in for PackageManager: throws while [broken], then only the Wi-Fi page exists. */
    private val packageManager: (String) -> Boolean = { action ->
        asked++
        if (broken) throw SecurityException("pm secret")
        action == SettingsLauncher.actionFor("wifi")
    }

    private fun engine(facts: SearchFactsSource): SearchEngine =
        SearchEngine(exec, ui, facts, KotlinOnly(), SearchRanking::query) { }.also { e ->
            e.listener = object : SearchEngine.Listener {
                override fun onResults(query: String, results: List<SearchResult>) { got.add("ok:$query:" + results.joinToString("|") { it.title }) }
                override fun onFailed(query: String) { got.add("fail:$query") }
            }
        }

    private fun SearchEngine.open() { beginSession(); exec.runAll() }
    private fun SearchEngine.reopen() { endSession(); beginSession(); exec.runAll() }
    private fun SearchEngine.ask(q: String): String {
        got.clear(); request(q); flush(); exec.runAll(); ui.drain()
        assertEquals("exactly one delivery for <$q>: $got", 1, got.size)
        return got[0]
    }

    @Test fun settingsErrorIsUnavailableForOneSessionAndAskedAgain() {
        val e = engine(NativeSearchFacts({ "[]" }) { kind -> SettingsLauncher.available(kind, packageManager) })
        e.open()
        assertTrue(e.omittedFacts.toString(), "settings:wifi" in e.omittedFacts)
        assertFalse("no possibly dead Wi-Fi shortcut", e.ask("wireless").contains("Wi-Fi"))

        broken = false
        e.reopen()
        assertTrue(e.omittedFacts.toString(), e.omittedFacts.isEmpty())
        assertTrue("the error was not cached as unavailable", e.ask("wireless").contains("Wi-Fi"))
        val n = asked
        e.reopen()
        assertEquals("real answers stay cached for the engine", n, asked)
        assertEquals(0, e.failureCount)
    }

    @Test fun theOldSwallowingWiringCachedTheErrorAsUnavailable() {
        // Documents the defect fixed in M4: with the lenient rule the engine never sees the error.
        val e = engine(NativeSearchFacts({ "[]" }) { kind -> SettingsLauncher.availableOrFalse(kind, packageManager) })
        e.open()
        assertTrue(e.omittedFacts.isEmpty())
        broken = false
        e.reopen()
        assertFalse("hidden until the engine is recreated", e.ask("wireless").contains("Wi-Fi"))
    }

    @Test fun installedAppsFailureStillFailsTheSessionExplicitly() {
        val e = engine(NativeSearchFacts({ throw IllegalStateException("pm secret") }) { false })
        e.open()
        assertEquals(RankFallback.PREPARE_FAILED, e.fallback)
        assertEquals("fail:kodi", e.ask("kodi"))
    }
}
