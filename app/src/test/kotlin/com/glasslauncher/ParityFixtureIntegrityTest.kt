package com.glasslauncher

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JavaScript that produced tools/parity/fixtures was removed in N6 and the Node capture scripts in N7, so the JSON
 * fixtures can no longer be regenerated. Their bytes are pinned here so an accidental edit fails CI; a deliberate
 * ranking change must update Rust, Kotlin, the fixture and these hashes together (tools/parity/fixtures/PROVENANCE.md).
 */
class ParityFixtureIntegrityTest {
    private fun file(rel: String): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            val f = File(d, rel)
            if (f.exists()) return f
            d = d.parentFile
        }
        error("$rel not found")
    }

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private val golden = "tools/parity/fixtures/ranking-golden.json"
    private val registry = "tools/parity/fixtures/registry-entries.json"

    @Test fun rankingGoldenBytesArePinned() {
        val f = file(golden)
        assertEquals(112551L, f.length())
        assertEquals("560e7b658cc52868509dfd94760bc60a8b6636d3858c2bfec9f0a56230cece47", sha256(f))
    }

    @Test fun registryEntriesBytesArePinned() {
        val f = file(registry)
        assertEquals(179658L, f.length())
        assertEquals("55fcf4a8f94d5030861b77ba60b83076c4b19cd540440f33b983a1436c3c7c2a", sha256(f))
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun rankingGoldenShapeAndProvenance() {
        val root = MiniJson.parse(file(golden).readText(Charsets.UTF_8)) as Map<String, Any?>
        assertTrue(root["generated_from"] as String, (root["generated_from"] as String).contains("queryJs()"))
        assertEquals(24, (root["max"] as Double).toInt())
        assertEquals(228, (root["entries"] as List<Any?>).size)
        val queries = (root["cases"] as List<List<Any?>>).map { it[0] as String }
        assertEquals(2522, queries.size)
        assertEquals("queries are unique", 2522, queries.toSet().size)
        for (q in queries) assertFalse("lone surrogate in <$q>", SearchCatalog.hasLoneSurrogate(q))
        assertTrue(file("tools/parity/fixtures/PROVENANCE.md").readText().contains("2,522 cases"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun registryEntriesCasesArePinned() {
        val root = MiniJson.parse(file(registry).readText(Charsets.UTF_8)) as Map<String, Any?>
        assertTrue((root["generated_from"] as String).contains("registry.entries()"))
        val cases = root["cases"] as List<Map<String, Any?>>
        assertEquals(listOf(228, 228, 226, 23, 14, 19, 19, 52), cases.map { (it["entries"] as List<Any?>).size })
        assertEquals("fixture box, storage page missing", cases[0]["name"])
    }
}
