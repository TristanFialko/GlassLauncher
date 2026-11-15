package com.glasslauncher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An unpaired UTF-16 surrogate has no UTF-8 form, so it can never cross JNI into the Rust core. The original JavaScript
 * registry handled such text with its LONE check (ranked by queryJs instead of Rust). The native engine keeps that
 * contract: lone-surrogate queries are ranked by the Kotlin port and the Rust backend is never asked.
 */
class SearchLoneSurrogateTest {
    private class RecordingRust : RankBackend {
        val searched = ArrayList<String>()
        override val available = true
        override fun prepare(wire: String): String? = (0 until wire.split('\u001e').size - 1).joinToString(",")
        override fun search(query: String, max: Int): String? { searched.add(query); return "" }
    }

    private val apps = listOf(
        InstalledApp("com.example.games", "\ud83c\udfae Games"),
        InstalledApp("com.example.gallery", "Gallery"),
        InstalledApp("com.example.retro", "Retro \ud83c\udfae"),
    )

    private val facts = object : SearchFactsSource {
        override fun installedApps() = apps
        override fun aliases() = emptyMap<String, List<String>>()
        override fun hasSettings(kind: String) = true
    }

    @Test fun loneSurrogateDetection() {
        for (s in listOf("\ud83c", "\udfae", "a\ud800b", "\udfae\ud83c", "ok\ud83c")) assertTrue(s, SearchCatalog.hasLoneSurrogate(s))
        for (s in listOf("", "abc", "\ud83c\udfae", "a\ud83c\udfaeb", "\u00e9t\u00e9")) assertFalse(s, SearchCatalog.hasLoneSurrogate(s))
    }

    @Test fun normKeepsSurrogatesLikeTheJavaScriptRegex() {
        assertEquals("\ud83c", SearchRanking.norm("\ud83c"))
        assertEquals("x \udfae", SearchRanking.norm("X  \udfae!"))
        assertEquals("\ud83c\udfae games", SearchRanking.norm("\ud83c\udfae Games"))
    }

    @Test fun kotlinRankingOfLoneSurrogates() {
        val cand = apps.map { SearchRanking.Candidate(true, it.name, emptyList()) }
        // high surrogate: prefix of the first title (85), prefix of a word in the third (70)
        assertArrayEquals(intArrayOf(0, 2), SearchRanking.query(cand, "\ud83c", 24))
        // low surrogate: substring of both (50, equal length), so the normalised title order decides
        assertArrayEquals(intArrayOf(2, 0), SearchRanking.query(cand, "\udfae", 24))
        assertArrayEquals(intArrayOf(0), SearchRanking.query(cand, "\ud83c", 1))
    }

    @Test fun engineNeverSendsLoneSurrogatesToRust() {
        val rust = RecordingRust()
        val exec = TestSupport.ManualExecutor()
        val ui = TestSupport.ManualUi()
        val got = ArrayList<Pair<String, List<String>>>()
        val e = SearchEngine(exec, ui, facts, rust)
        e.listener = SearchEngine.Listener { q, r -> got.add(q to r.map { it.title }) }
        e.beginSession(); exec.runAll()
        assertTrue(e.usingRust)
        for (q in listOf("games", "\ud83c", "\udfae", "retro \ud83c")) { e.request(q); e.flush(); exec.runAll(); ui.drain() }
        assertEquals(listOf("games"), rust.searched)
        assertEquals(
            listOf(
                "games" to emptyList(),                                   // the recording backend answers "no results"
                "\ud83c" to listOf("\ud83c\udfae Games", "Retro \ud83c\udfae"),
                "\udfae" to listOf("Retro \ud83c\udfae", "\ud83c\udfae Games"),
                "retro \ud83c" to listOf("Retro \ud83c\udfae"),
            ),
            got,
        )
        assertTrue(e.usingRust)
    }

    @Test fun pairedSurrogatesStillUseTheNativeWire() {
        assertNotNull(SearchCatalog.build(apps, emptyMap(), emptyList(), SearchCatalog.shortcuts { true }).wire)
    }
}
