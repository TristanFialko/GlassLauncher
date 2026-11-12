package com.glasslauncher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {
    private fun card(name: String = "", id: String = "", icon: String = "", dark: String = "", ids: Array<String> = emptyArray()) =
        NativeHomeConfig.Card().also { it.name = name; it.id = id; it.icon = icon; it.dark = dark; it.ids.addAll(ids) }

    // ---- Recently Used (recent.js / glass.js) ----
    @Test fun recordIgnoresEmptyAndSelf() {
        assertNull(HomeRecents.toRecord(null, "x"))
        assertNull(HomeRecents.toRecord("", "x"))
        assertNull(HomeRecents.toRecord("com.glasslauncher", "Glass"))
    }

    @Test fun recordNameFallsBackToId() {
        assertEquals(RecentApp("a.b", "a.b"), HomeRecents.toRecord("a.b", ""))
        assertEquals(RecentApp("a.b", "A"), HomeRecents.toRecord("a.b", "A"))
    }

    @Test fun validWhenListUnavailableOrContainsId() {
        val r = RecentApp("a.b", "A")
        assertTrue(HomeRecents.isValid(r, emptyList()))
        assertTrue(HomeRecents.isValid(r, listOf("x", "a.b")))
        assertFalse(HomeRecents.isValid(r, listOf("x")))
        assertFalse(HomeRecents.isValid(null, emptyList()))
    }

    @Test fun displayNamePrefersConfiguredCardThatResolvesToTheId() {
        val cards = listOf(card("Moonlight", ids = arrayOf("com.limelight", "com.limelight.noroot")))
        val installed = mapOf("com.limelight.noroot" to "Moonlight Game Streaming")
        assertEquals("Moonlight", HomeRecents.displayName(RecentApp("com.limelight.noroot", "stored"), cards, installed))
        assertEquals("stored", HomeRecents.displayName(RecentApp("other.pkg", "stored"), cards, installed))
    }

    @Test fun encodeDecodeRoundTrip() {
        val r = RecentApp("a.b", "A \"quoted\"")
        assertEquals(r, HomeRecents.decode(HomeRecents.encode(r)))
        assertNull(HomeRecents.decode(null))
        assertNull(HomeRecents.decode("not json"))
        assertNull(HomeRecents.decode("""{"id":"com.glasslauncher","name":"x"}"""))
    }

    // ---- launch resolution fallbacks (home.js / appdata.js) ----
    @Test fun launchKeepsConfiguredIdEvenWhenNotListed() {
        assertEquals("a.b", NativeHomeConfig.resolveForLaunch(card(id = "a.b"), mapOf("c" to "C")))
    }

    @Test fun launchUsesFirstCandidateOnlyWhenNoAppListAtAll() {
        val k = card(ids = arrayOf("n.one", "n.two"))
        assertEquals("n.one", NativeHomeConfig.resolveForLaunch(k, emptyMap()))
        assertNull(NativeHomeConfig.resolveForLaunch(k, mapOf("c" to "C")))
        assertEquals("n.two", NativeHomeConfig.resolveForLaunch(k, mapOf("n.two" to "T")))
    }

    // ---- artwork selection (home.js setTileIcon / icons.js override) ----
    @Test fun artPathFollowsTheTheme() {
        val k = card(icon = "icons/a.png", dark = "darkicons/a.png")
        assertEquals("icons/a.png", HomeArt.artPath(k, false))
        assertEquals("darkicons/a.png", HomeArt.artPath(k, true))
        assertEquals("icons/a.png", HomeArt.artPath(card(icon = "icons/a.png"), true))
        assertEquals("darkicons/a.png", HomeArt.artPath(card(dark = "darkicons/a.png"), false))
        assertEquals("", HomeArt.artPath(card(), true))
    }

    @Test fun sourcePriorityIsArtThenOverrideThenPackageIcon() {
        val ov = mapOf("a.b" to "icons/overrides/a.png")
        assertEquals(HomeArt.Source.ART, HomeArt.source(card(id = "a.b", icon = "i.png"), ov, "a.b"))
        assertEquals(HomeArt.Source.OVERRIDE, HomeArt.source(card(id = "a.b"), ov, "a.b"))
        val u = card(id = "x.y").also { it.useAppIcon = true }
        assertEquals(HomeArt.Source.PACKAGE_ICON, HomeArt.source(u, ov, "x.y"))
        assertEquals(HomeArt.Source.NONE, HomeArt.source(card(id = "x.y"), ov, "x.y"))
        assertEquals(HomeArt.Source.NONE, HomeArt.source(card(id = "a.b").also { it.placeholder = true }, ov, "a.b"))
    }

    @Test fun overrideLookupIgnoresMissingAndEmpty() {
        assertNull(HomeArt.override(mapOf("a" to ""), "a"))
        assertNull(HomeArt.override(mapOf("a" to "p.png"), null))
        assertEquals("p.png", HomeArt.override(mapOf("a" to "p.png"), "a"))
    }

    @Test fun squareFitRule() {
        assertTrue(HomeArt.squareFit(128, 128, false))
        assertFalse(HomeArt.squareFit(800, 480, false))
        assertTrue(HomeArt.squareFit(800, 480, true))
        assertTrue(HomeArt.squareFit(129, 100, false))      // 1.29 < 1.3
    }

    @Test fun labelIsTextElseInitials() {
        assertEquals("PV", HomeArt.label(card("Prime Video")))
        assertEquals("+", HomeArt.label(card("Prime Video").also { it.text = "+" }))
        assertEquals("?", HomeArt.label(card("")))
    }

    @Test fun cssColors() {
        val d = 0x11223344
        assertEquals(0xFF112233.toInt(), NativeHomeConfig.parseCssColor("#112233", d))
        assertEquals(0xFFAABBCC.toInt(), NativeHomeConfig.parseCssColor("#ABC", d))
        assertEquals(0x80112233.toInt(), NativeHomeConfig.parseCssColor("#11223380", d))
        assertEquals(0x80010203.toInt(), NativeHomeConfig.parseCssColor("rgba(1, 2, 3, .5)", d))
        assertEquals(0xFF010203.toInt(), NativeHomeConfig.parseCssColor("rgb(1,2,3)", d))
        assertEquals(d, NativeHomeConfig.parseCssColor("red", d))
        assertEquals(d, NativeHomeConfig.parseCssColor("", d))
        assertEquals(d, NativeHomeConfig.parseCssColor(null, d))
    }

    @Test fun parsesCardExtrasAndOverrides() {
        val cfg = NativeHomeConfig.parse(
            """{"rows":[{"apps":[{"name":"P","placeholder":true},{"name":"A","bg":"#112233","fg":"#fff","size":2,"text":"AA","useAppIcon":true}]}],
               "overrides":{"a.b":"icons/overrides/x.png","c.d":""}}""")
        val (p, a) = cfg.rows[0].cards
        assertTrue(p.placeholder)
        assertEquals(0xFF112233.toInt(), a.bg)
        assertEquals(2f, a.size, 0f)
        assertEquals("AA", a.text)
        assertTrue(a.useAppIcon)
        assertEquals(mapOf("a.b" to "icons/overrides/x.png"), cfg.overrides)
    }

    // ---- header order and Tab (home.js hdrOrder / tab) ----
    @Test fun headerOrderHasRecentFirstOnlyWhenAvailable() {
        assertArrayEquals(intArrayOf(2, 0, 1), HomeNav.headerOrder(true))
        assertArrayEquals(intArrayOf(0, 1), HomeNav.headerOrder(false))
    }

    @Test fun headerStepClampsAndRecovers() {
        val o = HomeNav.headerOrder(true)
        assertEquals(HomeNav.SEARCH, HomeNav.headerStep(o, HomeNav.RECENT, 1))
        assertEquals(HomeNav.CC, HomeNav.headerStep(o, HomeNav.CC, 1))
        assertEquals(HomeNav.RECENT, HomeNav.headerStep(o, HomeNav.RECENT, -1))
        assertEquals(HomeNav.SEARCH, HomeNav.headerStep(HomeNav.headerOrder(false), HomeNav.SEARCH, -1))
        assertEquals(HomeNav.CC, HomeNav.headerStep(HomeNav.headerOrder(false), HomeNav.RECENT, 1))   // stale stop restarts at the first one
    }

    @Test fun tabWalksHeaderThenCardsAndWraps() {
        val o = HomeNav.headerOrder(true)
        val sizes = intArrayOf(5, 4)
        assertArrayEquals(intArrayOf(-1, HomeNav.SEARCH), HomeNav.tab(o, sizes, true, HomeNav.RECENT, 0, 0, 1))
        assertArrayEquals(intArrayOf(0, 0), HomeNav.tab(o, sizes, true, HomeNav.CC, 0, 0, 1))
        assertArrayEquals(intArrayOf(1, 0), HomeNav.tab(o, sizes, false, 0, 0, 4, 1))
        assertArrayEquals(intArrayOf(-1, HomeNav.RECENT), HomeNav.tab(o, sizes, false, 0, 1, 3, 1))     // wraps to the first stop
        assertArrayEquals(intArrayOf(1, 3), HomeNav.tab(o, sizes, true, HomeNav.RECENT, 0, 0, -1))     // Shift+Tab wraps to the last card
        assertArrayEquals(intArrayOf(-1, HomeNav.CC), HomeNav.tab(o, sizes, false, 0, 0, 0, -1))
    }

    // ---- two-line name clamp ----
    private val m: (String) -> Float = { it.length * 10f }

    @Test fun wrapKeepsShortNamesOnOneLine() {
        assertEquals(listOf("Netflix"), HomeText.wrap("Netflix", 145f, 2, m))
        assertEquals(emptyList<String>(), HomeText.wrap("  ", 145f, 2, m))
    }

    @Test fun wrapBreaksAtWordsAndClampsWithEllipsis() {
        assertEquals(listOf("Prime", "Video"), HomeText.wrap("Prime Video", 80f, 2, m))
        val l = HomeText.wrap("one two three four five six", 80f, 2, m)
        assertEquals(2, l.size)
        assertTrue(l[1].endsWith("…"))
        assertTrue(l.all { m(it) <= 80f })
        assertEquals("Supercali…", HomeText.wrap("Supercalifragilistic", 100f, 1, m)[0])
    }
}
