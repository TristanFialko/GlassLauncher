package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class NativeHomeConfigTest {
    private fun card(id: String = "", match: String = "", vararg ids: String) =
        NativeHomeConfig.Card().also { it.id = id; it.match = match; it.ids.addAll(ids) }

    private fun installed(vararg p: Pair<String, String>) = linkedMapOf(*p)

    @Test fun configuredIdWins() {
        val k = card("a.b", "x", "c.d")
        assertEquals("a.b", NativeHomeConfig.resolve(k, installed("c.d" to "X", "a.b" to "Y")))
    }

    @Test fun firstInstalledOfIds() {
        val k = card("missing", "", "n.one", "n.two")
        assertEquals("n.two", NativeHomeConfig.resolve(k, installed("n.two" to "T")))
    }

    @Test fun nameMatchSkipsHiddenPrefixes() {
        val k = card("", "^netflix$")
        val apps = installed("com.samsung.netflix" to "Netflix", "org.tizen.x" to "Netflix", "DU.TV.PRODA.n" to "Netflix", "com.netflix.ninja" to "netflix")
        assertEquals("com.netflix.ninja", NativeHomeConfig.resolve(k, apps))
    }

    @Test fun nullWhenNothingMatches() {
        assertNull(NativeHomeConfig.resolve(card("a", "zzz", "b"), installed("c" to "Other")))
        assertNull(NativeHomeConfig.resolve(card(), installed("c" to "Other")))
    }

    @Test fun initials() {
        assertEquals("PV", NativeHomeConfig.initials("Prime Video"))
        assertEquals("Y", NativeHomeConfig.initials("youtube"))
        assertEquals("AB", NativeHomeConfig.initials("  alpha  beta gamma "))
    }

    @Test fun parsesShippedHomeJson() {
        val cfg = NativeHomeConfig.parse(File("src/main/assets/nativeui/home.json").readText())
        assertEquals(2, cfg.rows.size)
        assertEquals(5, cfg.rows[0].cards.size)
        assertEquals(4, cfg.rows[1].cards.size)
        assertEquals("YouTube", cfg.rows[0].cards[0].name)
        assertEquals("com.google.android.youtube.tv", cfg.rows[0].cards[0].id)
        assertEquals("icons/Youtube.png", cfg.rows[0].cards[0].icon)
        assertEquals("darkicons/darkyoutube.png", cfg.rows[0].cards[0].dark)
    }

    @Test fun defaultsWhenOptionalKeysAbsent() {
        val cfg = NativeHomeConfig.parse("""{"rows":[{"apps":[{"name":"A","ids":["x","y"]}]}]}""")
        assertEquals("wallpaper.jpg", cfg.wallpaperLight)
        assertEquals("wallpaper2.jpg", cfg.wallpaperDark)
        assertEquals(true, cfg.soundEnabled)
        assertEquals(0.2f, cfg.soundVolume, 1e-6f)
        assertEquals(listOf("x", "y"), cfg.rows[0].cards[0].ids)
    }
}
