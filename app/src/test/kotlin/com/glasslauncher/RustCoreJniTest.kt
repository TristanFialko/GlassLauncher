package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real JNI layer (libglass built for the host by :app:cargoBuildHost) from the JVM,
 * through the same RustCore wrapper the app uses. N6: migrated from RustCoreJniTest.java, cases unchanged.
 */
class RustCoreJniTest {
    private companion object {
        const val R = "\u001e"
        const val F = "\u001f"
        const val SNAP = "glass-snapshot${F}1"
    }

    @Before fun requireNative() {
        assumeTrue("built with -Pglass.rust=false", java.lang.Boolean.getBoolean("glass.expectNative"))
    }

    @Test fun loadsAndPassesSelfTest() {
        assertTrue("status: " + RustCore.status(), RustCore.isAvailable())
        assertTrue(RustCore.version().matches(Regex("\\d+\\.\\d+\\.\\d+")))
        assertEquals("ok", RustCore.status())
    }

    @Test fun indexAndSearch() {
        val wire = "app${F}YouTube$R" +
            "shortcut${F}Wi-Fi${F}wifi${F}network$R" +
            "app${F}Netflix$R" +
            "shortcut${F}Settings$R" +
            "app${F}Settings"
        assertEquals(5, RustCore.setSearchIndex(wire))
        assertEquals("0", RustCore.search("YOU", 24))
        assertEquals("2,1", RustCore.search("net", 24))
        assertEquals("4,3", RustCore.search("settings", 24))
        assertEquals("4", RustCore.search("settings", 1))
        assertEquals("", RustCore.search("zzzz", 24))
        assertEquals("", RustCore.search("   ", 24))
    }

    @Test fun unicodeSurvivesTheBoundary() {
        assertEquals(3, RustCore.setSearchIndex("app${F}Ünïcödé Player${R}app${F}日本のテレビ${R}app${F}🎮 Game Hub"))
        assertEquals("0", RustCore.search("ünï", 24))
        assertEquals("1", RustCore.search("テレビ", 24))
        assertEquals("2", RustCore.search("🎮", 24))
    }

    @Test fun badInputIsAControlledFailure() {
        assertEquals(-1, RustCore.setSearchIndex(null))
        assertNull(RustCore.search(null, 24))
        assertEquals(0, RustCore.setSearchIndex(""))
        assertEquals("", RustCore.search("anything", 24))
    }

    @Test fun largeIndex() {
        val wire = (0 until 5000).joinToString(R) { "app${F}App number $it" }
        assertEquals(5000, RustCore.setSearchIndex(wire))
        assertEquals(24, RustCore.search("app", 0)!!.split(",").size)
    }

    @Test fun prepareSearchAppliesRegistryRulesAndReplacesTheIndex() {
        val snap = SNAP +
            "${R}i${F}com.google.android.youtube.tv${F}YouTube${F}1${F}yt" +      // 0 kept
            "${R}i${F}com.samsung.tvplus${F}Samsung TV Plus${F}1" +              // 1 hidden vendor
            "${R}i${F}com.glasslauncher${F}Glass${F}1" +                         // 2 Glass itself
            "${R}i${F}com.android.tv.settings${F}Settings${F}0" +                // 3 show:false
            "${R}e${F}shortcut${F}1${F}com.android.settings\u001dcom.android.tv.settings${F}Settings" +  // 4 superseded
            "${R}e${F}shortcut${F}1${F}${F}Wi-Fi${F}wifi" +                      // 5 kept
            "${R}e${F}shortcut${F}0${F}${F}Storage"                              // 6 unavailable
        assertEquals("0,5", RustCore.prepareSearch(snap))
        assertEquals("0", RustCore.search("yt", 24))       // index positions follow the refs
        assertEquals("1", RustCore.search("wifi", 24))
        assertEquals("", RustCore.search("samsung", 24))
    }

    @Test fun prepareSearchRejectsBadSnapshotsWithoutTouchingTheIndex() {
        assertEquals(2, RustCore.setSearchIndex("app${F}YouTube${R}app${F}Netflix"))
        assertNull(RustCore.prepareSearch(null))
        assertNull(RustCore.prepareSearch(""))
        assertNull(RustCore.prepareSearch("glass-snapshot${F}9"))
        assertNull(RustCore.prepareSearch("$SNAP${R}i${F}only.id"))
        assertNull(RustCore.prepareSearch("$SNAP${R}e${F}?${F}1${F}${F}Unknown kind"))
        assertEquals("1", RustCore.search("netflix", 24))
        assertEquals("", RustCore.prepareSearch(SNAP))
        assertEquals("", RustCore.search("netflix", 24))
    }
}
