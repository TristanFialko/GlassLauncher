package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** tools/parity/fixtures/registry-entries.json is the contract: registry.entries() output for several boxes. */
class SearchCatalogFixtureTest {
    private fun sig(kind: String, title: String, kw: List<String>) = "$kind|$title|${kw.joinToString("\u0000")}"
    private fun sig(s: SearchCatalog.Source) = when (s) {
        is SearchCatalog.Source.App -> sig("app", s.app.name, s.aliases)
        is SearchCatalog.Source.Entry -> sig(s.entry.kind, s.entry.title, s.entry.keywords)
    }
    private fun title(s: SearchCatalog.Source) = if (s is SearchCatalog.Source.App) s.app.name else (s as SearchCatalog.Source.Entry).entry.title
    private fun expected(c: TestSupport.FixtureCase) = c.entries.map { sig(it.kind, it.title, it.keywords) }

    @Test fun fallbackEntriesEqualRegistryEntriesForEveryFixtureCase() {
        for (c in TestSupport.cases()) {
            val s = TestSupport.snapshotOf(TestSupport.factsOf(c))
            assertSameList(c.name, expected(c), s.fallbackEntries().first.map(::sig))
        }
    }

    @Test fun rustKeepsExactlyTheFixtureEntriesInOrder() {
        TestSupport.requireNative()
        for (c in TestSupport.cases()) {
            val s = TestSupport.snapshotOf(TestSupport.factsOf(c))
            assertNotNull(c.name, s.wire)
            val refs = RustCore.prepareSearch(s.wire!!)
            assertNotNull(c.name, refs)
            val got = if (refs!!.isEmpty()) emptyList() else refs.split(',').map { sig(s.sources[it.toInt()]) }
            assertSameList(c.name, expected(c), got)
        }
    }

    private val hiddenApps = listOf(
        InstalledApp("com.glasslauncher", "Glass Launcher"), InstalledApp("com.hidden.show", "Shown False", show = false),
        InstalledApp("ORG.TIZEN.x", "Tizen Upper"), InstalledApp("Com.Samsung.tv", "Samsung Mixed"), InstalledApp("du.tv.proda.epg", "DU Lower"),
        InstalledApp("xorg.tizen", "Not A Prefix"),
    )
    private val hiddenExtras = listOf(
        CatalogEntry("x:off", "shortcut", "Unavailable Extra", "", emptyList(), "X", "settings", available = false, action = SearchAction.ToggleTheme),
        CatalogEntry("x:sup", "shortcut", "Superseded Extra", "", emptyList(), "X", "settings", supersededBy = listOf("com.samsung.hidden.installed"), action = SearchAction.ToggleTheme),
    )

    @Test fun visibilityAvailabilityAndSupersessionRulesMatchOnBothPaths() {
        val f = TestSupport.factsOf(TestSupport.cases().first())
        val s = TestSupport.snapshotOf(f, hiddenApps + InstalledApp("com.samsung.hidden.installed", "Hidden Installed"), hiddenExtras)
        val titles = s.fallbackEntries().first.map(::title)
        assertTrue(titles.contains("Not A Prefix"))
        for (t in listOf("Glass Launcher", "Shown False", "Tizen Upper", "Samsung Mixed", "DU Lower", "Hidden Installed", "Unavailable Extra", "Superseded Extra")) assertTrue(t, t !in titles)
        if (TestSupport.native) {
            val refs = RustCore.prepareSearch(s.wire!!)!!.split(',').map { s.sources[it.toInt()] }
            assertEquals(titles, refs.map(::title))
        }
    }

    @Test fun snapshotWireFormatMatchesRegistryJs() {
        val s = SearchCatalog.build(
            listOf(InstalledApp("a.b", "A\u001cB", true), InstalledApp("c.d", "C", false)),
            mapOf("a.b" to listOf("k\u001f1", "k2")),
            emptyList(),
            listOf(CatalogEntry("e", "shortcut", "T\u001e", "", listOf("w"), "t", "i", supersededBy = listOf("p.q", "bad\u001dx", "r.s"), available = false, action = SearchAction.ToggleTheme)))
        val f = "\u001f"; val r = "\u001e"
        assertEquals("glass-snapshot${f}1${r}i${f}a.b${f}AB${f}1${f}k1${f}k2${r}i${f}c.d${f}C${f}0${r}e${f}shortcut${f}0${f}p.q\u001dr.s${f}T${f}w", s.wire)
    }

    @Test fun controlCharacterInPackageIdOrLoneSurrogateKeepsTheFallback() {
        assertEquals(null, SearchCatalog.build(listOf(InstalledApp("a\u001fb", "X")), emptyMap(), emptyList(), emptyList()).wire)
        assertEquals(null, SearchCatalog.build(listOf(InstalledApp("a.b", "X\ud800")), emptyMap(), emptyList(), emptyList()).wire)
        assertNotNull(SearchCatalog.build(listOf(InstalledApp("a.b", "X\ud83c\udfae")), emptyMap(), emptyList(), emptyList()).wire)
    }

    @Test fun builtInShortcutsMatchShortcutsJs() {
        val all = SearchCatalog.shortcuts { true }
        assertEquals(listOf("set:wifi", "set:bluetooth", "set:display", "set:sound", "set:apps", "set:storage", "set:datetime", "set:keyboard", "set:accessibility", "set:device", "set:main",
            "glass:cc", "glass:volume", "glass:theme"), all.map { it.id })
        val none = SearchCatalog.shortcuts { false }
        assertEquals(listOf("set:main"), none.filter { it.id.startsWith("set:") && it.available }.map { it.id })
        assertEquals(SearchAction.OpenControlCentre(true), all.first { it.id == "glass:volume" }.action)
        assertEquals(SearchAction.OpenSettings("device"), all.first { it.id == "set:device" }.action)
        assertEquals(listOf("com.android.tv.settings", "com.android.settings"), all.first { it.id == "set:main" }.supersededBy)
    }

    @Test fun playStoreAliasOnlyWhenNotConfigured() {
        assertEquals(listOf("play store", "google play", "store", "app store", "download apps"), SearchCatalog.mergeAliases(emptyMap())["com.android.vending"])
        assertEquals(listOf("x"), SearchCatalog.mergeAliases(mapOf("com.android.vending" to listOf("x")))["com.android.vending"])
    }
}
