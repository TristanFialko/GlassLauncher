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

    private val assets = File("src/main/assets")
    private fun shipped() = NativeHomeConfig.parse(File(assets, "nativeui/home.json").readText())

    /** Replaces tools/parity/nativeui-config-check.js: every artwork and wallpaper the config names is packaged. */
    @Test fun shippedArtworkAndWallpapersExist() {
        val cfg = shipped()
        val missing = ArrayList<String>()
        for (r in cfg.rows) for (c in r.cards) for (p in listOf(c.icon, c.dark)) {
            if (p.isNotEmpty() && !File(assets, p).isFile) missing.add("${c.name}: $p")
        }
        for (p in listOf(cfg.wallpaperLight, cfg.wallpaperDark)) if (!File(assets, p).isFile) missing.add(p)
        assertEquals(emptyList<String>(), missing)
    }

    /** apps.js was deleted in N6; these are the rows nativeui-config-check.js last verified against it. */
    @Test fun shippedRowsArePinned() {
        val cfg = shipped()
        assertEquals(listOf("top", "ent"), cfg.rows.map { it.kind })
        assertEquals(listOf("", "Entertainment"), cfg.rows.map { it.title })
        val got = cfg.rows.flatMap { r -> r.cards.map { "${it.name}|${it.id}|${it.ids.joinToString(",")}|${it.match}|${it.icon}|${it.dark}" } }
        assertEquals(listOf(
            "YouTube|com.google.android.youtube.tv|||icons/Youtube.png|darkicons/darkyoutube.png",
            "Netflix|com.netflix.ninja|||icons/Netflix.png|darkicons/darknetflix.png",
            "Prime Video|com.amazon.amazonvideo.livingroom|||icons/Prime.png|darkicons/darkprime.png",
            "Disney+|com.disney.disneyplus|||icons/Disney+.png|darkicons/darkdisney.png",
            "Spotify|com.spotify.tv.android|||icons/Spotify.png|darkicons/Spotify.png",
            "Stremio||com.stremio.one|stremio|icons/stremio.png|darkicons/stremio.png",
            "Rhenium|||rhenium|icons/rhenium.png|darkicons/rhenium.png",
            "Moonlight||com.limelight,com.limelight.noroot,com.limelight.root|moonlight|icons/moonlight.png|darkicons/moonlight.png",
            "RetroArch||com.retroarch,com.retroarch.aarch64,com.retroarch.ra32|retroarch|icons/retroarch.png|darkicons/retroarch.png",
        ), got)
        assertEquals("wallpaper.jpg", cfg.wallpaperLight)
        assertEquals("wallpaper2.jpg", cfg.wallpaperDark)
        assertEquals(true, cfg.soundEnabled)
        assertEquals(0.2f, cfg.soundVolume, 1e-6f)
        assertEquals(0.3f, cfg.keyVolume, 1e-6f)
    }
}
