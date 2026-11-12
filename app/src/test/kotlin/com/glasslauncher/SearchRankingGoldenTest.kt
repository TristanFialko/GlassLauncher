package com.glasslauncher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * tools/parity/fixtures/ranking-golden.json is registry.js queryJs() frozen on the parity fixture box
 * (`node tools/parity/ranking-golden.js --write`; CI checks the JavaScript still reproduces it). It keeps the original
 * JavaScript ranking as the reference for the Kotlin fallback and the Rust core after the WebView assets are removed
 * (Stage 4c), when search-parity.js loses queryJs(). Unlike SearchRankingParityTest the Kotlin half needs no native library.
 */
class SearchRankingGoldenTest {
    class Entry(val id: String, val kind: String, val title: String, val keywords: List<String>)
    class Golden(val max: Int, val snapshot: String, val entries: List<Entry>, val cases: List<Pair<String, IntArray>>)

    companion object {
        private fun file(rel: String): File {
            var d: File? = File("").absoluteFile
            while (d != null) {
                val f = File(d, rel)
                if (f.exists()) return f
                d = d.parentFile
            }
            error("$rel not found")
        }

        private fun ints(s: String): IntArray = if (s.isEmpty()) IntArray(0) else s.split(',').map { it.toInt() }.toIntArray()

        @Suppress("UNCHECKED_CAST")
        private val golden: Golden by lazy {
            val root = MiniJson.parse(file("tools/parity/fixtures/ranking-golden.json").readText(Charsets.UTF_8)) as Map<String, Any?>
            Golden(
                (root["max"] as Double).toInt(),
                root["snapshot"] as String,
                (root["entries"] as List<Map<String, Any?>>).map { e ->
                    Entry(e["id"] as String, e["kind"] as String, e["title"] as String, (e["keywords"] as List<Any?>).map { it as String })
                },
                (root["cases"] as List<List<Any?>>).map { c -> (c[0] as String) to ints(c[1] as String) },
            )
        }

        private fun show(a: IntArray) = a.joinToString(",")

        /** Collects every mismatch so one CI run shows the whole picture, then fails with the first few. */
        private fun verify(name: String, rank: (String, Int) -> IntArray) {
            val g = golden
            val bad = ArrayList<String>()
            for ((q, want) in g.cases) {
                val got = rank(q, g.max)
                if (!want.contentEquals(got)) bad.add("max=${g.max} query <$q>: expected [${show(want)}] but was [${show(got)}]")
                val five = rank(q, 5)
                val want5 = want.copyOf(minOf(5, want.size))
                if (!want5.contentEquals(five)) bad.add("max=5 query <$q>: expected [${show(want5)}] but was [${show(five)}]")
            }
            if (bad.isNotEmpty()) fail("$name: ${bad.size} of ${g.cases.size * 2} rankings differ from registry.js; first:\n" + bad.take(8).joinToString("\n"))
        }
    }

    @Test
    fun goldenFileIsUsable() {
        val g = golden
        assertEquals(SearchRanking.DEFAULT_MAX, g.max)
        assertEquals("entry ids are unique", g.entries.size, g.entries.map { it.id }.toSet().size)
        // a golden file that ranks nothing would make every check below vacuous
        if (g.cases.size < 1000 || g.cases.count { it.second.isNotEmpty() } < 1000) fail("golden file too small: ${g.cases.size} cases")
        for ((q, want) in g.cases) for (i in want) if (i < 0 || i >= g.entries.size) fail("query <$q>: index $i outside ${g.entries.size} entries")
    }

    @Test
    fun kotlinFallbackRanksExactlyLikeRegistryJs() {
        val candidates = golden.entries.map { SearchRanking.Candidate(it.kind == "app", it.title, it.keywords) }
        verify("Kotlin SearchRanking") { q, max -> SearchRanking.query(candidates, q, max) }
    }

    @Test
    fun nativeCatalogProducesTheGoldenEntries() {
        // registry-entries.json case 0 is the same fixture box, so SearchCatalog must turn its facts into exactly the
        // entries (order included) that registry.js ranked when the golden file was written.
        val case0 = TestSupport.cases().first()
        assertEquals("fixture box, storage page missing", case0.name)
        val catalog = TestSupport.snapshotOf(TestSupport.factsOf(case0)).fallbackEntries().second
        assertEquals(golden.entries.size, catalog.size)
        assertSameList("catalog vs golden",
            golden.entries.map { "${it.kind == "app"}|${it.title}|${it.keywords.joinToString("\u0000")}" },
            catalog.map { "${it.isApp}|${it.title}|${it.keywords.joinToString("\u0000")}" })
        // and the whole Kotlin path (catalog + ranking) reproduces the JavaScript results
        verify("SearchCatalog + SearchRanking") { q, max -> SearchRanking.query(catalog, q, max) }
    }

    @Test
    fun rustRanksExactlyLikeRegistryJs() {
        TestSupport.requireNative()
        val refs = RustCore.prepareSearch(golden.snapshot)
        assertNotNull("Rust rejected the golden snapshot", refs)
        assertEquals("entries kept by Rust", golden.entries.size, ints(refs!!).size)
        verify("Rust core") { q, max -> ints(RustCore.search(q, max) ?: error("Rust search failed for <$q>")) }
    }
}
