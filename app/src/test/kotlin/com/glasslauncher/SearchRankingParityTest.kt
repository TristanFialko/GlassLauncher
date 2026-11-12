package com.glasslauncher

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin fallback ranking must order exactly like the Rust core (itself parity-tested against registry.js). */
class SearchRankingParityTest {
    private val fixed = listOf("", " ", "   ", "a", "A", "wi-fi", "WI FI", "wi fi", "play store", "settings", "set", "ntfx", "yt",
        "σίσυφος", "ΣΊΣΥΦΟΣ", "istanbul", "i̇stanbul", "café", "cafe", "テレビ", "🎮", "𝔉𝔯", "éclair", "é", "ｆｕｌｌ",
        "media center", "mediacenter", "4k", "2024", "!!!", "sep", "separator", "xyzzy", "tab name", "ideo space", "storage", "volume", "dark mode")

    private fun queries(entries: List<SearchRanking.Candidate>): List<String> {
        val set = LinkedHashSet<String>(fixed)
        val rnd = Random(1234567)
        for (e in entries) for (s in listOf(e.title) + e.keywords) {
            for (n in 1..minOf(12, s.length)) { val p = s.substring(0, n); set.add(p); set.add(p.uppercase()) }
            set.add(s); set.add(s.replace(" ", ""))
        }
        val pool = "abcdefghijklmnopqrstuvwxyz0123456789    -+&!.ÉéüΣσςİı日本テレビ 　\t".toCharArray()
        repeat(600) { set.add(String(CharArray(1 + rnd.nextInt(7)) { pool[rnd.nextInt(pool.size)] })) }
        return set.toList()
    }

    private fun ints(s: String) = if (s.isEmpty()) IntArray(0) else s.split(',').map(String::toInt).toIntArray()

    @Test fun fallbackMatchesRustOnEveryFixtureCase() {
        TestSupport.requireNative()
        var compared = 0
        for (c in TestSupport.cases()) {
            val s = TestSupport.snapshotOf(TestSupport.factsOf(c))
            val refs = RustCore.prepareSearch(s.wire!!)!!
            val cand = s.fallbackEntries().second
            assertEquals(c.name, ints(refs).size, cand.size)      // both sides keep the same entries in the same order
            for (q in queries(cand)) {
                if (SearchCatalog.hasLoneSurrogate(q)) continue     // the engine never sends these to Rust (registry.js LONE)
                assertArrayEquals("${c.name}: query ${q.map { it.code }}", ints(RustCore.search(q, 24)!!), SearchRanking.query(cand, q, 24))
                compared++
            }
        }
        assertTrue("compared $compared", compared > 3000)
    }

    @Test fun maxAndDefaultLimit() {
        TestSupport.requireNative()
        val s = TestSupport.snapshotOf(TestSupport.factsOf(TestSupport.cases().first()))
        RustCore.prepareSearch(s.wire!!)
        val cand = s.fallbackEntries().second
        for (q in listOf("e", "a", "o")) for (max in listOf(0, 1, 5, 24, 100))
            assertArrayEquals("$q/$max", ints(RustCore.search(q, max)!!), SearchRanking.query(cand, q, max))
    }

    @Test fun normMatchesRegistryJs() {
        assertEquals("wifi", SearchRanking.norm("  Wi-Fi  "))
        assertEquals("date time", SearchRanking.norm("Date & time"))
        assertEquals("disney", SearchRanking.norm("Disney+"))
        assertEquals("ab", SearchRanking.norm("A\tB"))
        assertEquals("a b", SearchRanking.norm("A\u3000\u3000B"))
        assertEquals("ab", SearchRanking.norm("A\u00a0B"))
        assertEquals("ünïcödé", SearchRanking.norm("Ünïcödé"))
        assertEquals("🎮 games", SearchRanking.norm("🎮 Games"))
        assertEquals("x", SearchRanking.norm("\ufeffx\ufeff"))
        assertEquals("", SearchRanking.norm(""))
    }

    @Test fun knownRankings() {
        val c = listOf(
            SearchRanking.Candidate(true, "YouTube", emptyList()),
            SearchRanking.Candidate(false, "Wi-Fi", listOf("wifi", "wireless", "network")),
            SearchRanking.Candidate(true, "Netflix", emptyList()),
            SearchRanking.Candidate(false, "Settings", listOf("android settings", "system")),
            SearchRanking.Candidate(true, "Settings", emptyList()),
            SearchRanking.Candidate(true, "Google Play Store", listOf("play store", "store")),
        )
        assertArrayEquals(intArrayOf(0), SearchRanking.query(c, "youtube", 24))
        assertArrayEquals(intArrayOf(2, 1), SearchRanking.query(c, "net", 24))
        assertArrayEquals(intArrayOf(4, 3), SearchRanking.query(c, "settings", 24))
        assertArrayEquals(intArrayOf(2), SearchRanking.query(c, "ntfx", 24))
        assertArrayEquals(intArrayOf(), SearchRanking.query(c, "ntf", 24))
        assertArrayEquals(intArrayOf(), SearchRanking.query(c, "wrls", 24))
        assertArrayEquals(intArrayOf(), SearchRanking.query(c, "   ", 24))
    }

    @Test fun hiddenPrefixIsAsciiCaseInsensitiveOnly() {
        assertTrue(SearchRanking.isHidden("ORG.TIZEN.a")); assertTrue(SearchRanking.isHidden("du.tv.proda"))
        assertTrue(!SearchRanking.isHidden("xcom.samsung")); assertTrue(!SearchRanking.isHidden("org.tizeı"))
    }
}
