package com.glasslauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Exercises the real JNI layer (libglass built for the host by :app:cargoBuildHost) from the JVM,
 * through the same RustCore wrapper the app uses.
 */
public class RustCoreJniTest {
    private static final String R = "\u001e", F = "\u001f";

    @Before public void requireNative() {
        assumeTrue("built with -Pglass.rust=false", Boolean.getBoolean("glass.expectNative"));
    }

    @Test public void loadsAndPassesSelfTest() {
        assertTrue("status: " + RustCore.status(), RustCore.isAvailable());
        assertTrue(RustCore.version().matches("\\d+\\.\\d+\\.\\d+"));
        assertEquals("ok", RustCore.status());
    }

    @Test public void indexAndSearch() {
        String wire = "app" + F + "YouTube" + R
                + "shortcut" + F + "Wi-Fi" + F + "wifi" + F + "network" + R
                + "app" + F + "Netflix" + R
                + "shortcut" + F + "Settings" + R
                + "app" + F + "Settings";
        assertEquals(5, RustCore.setSearchIndex(wire));
        assertEquals("0", RustCore.search("YOU", 24));
        assertEquals("2,1", RustCore.search("net", 24));
        assertEquals("4,3", RustCore.search("settings", 24));
        assertEquals("4", RustCore.search("settings", 1));
        assertEquals("", RustCore.search("zzzz", 24));
        assertEquals("", RustCore.search("   ", 24));
    }

    @Test public void unicodeSurvivesTheBoundary() {
        assertEquals(3, RustCore.setSearchIndex("app" + F + "Ünïcödé Player" + R + "app" + F + "日本のテレビ" + R + "app" + F + "🎮 Game Hub"));
        assertEquals("0", RustCore.search("ünï", 24));
        assertEquals("1", RustCore.search("テレビ", 24));
        assertEquals("2", RustCore.search("🎮", 24));
    }

    @Test public void badInputIsAControlledFailure() {
        assertEquals(-1, RustCore.setSearchIndex(null));
        assertNull(RustCore.search(null, 24));
        assertEquals(0, RustCore.setSearchIndex(""));
        assertEquals("", RustCore.search("anything", 24));
    }

    @Test public void largeIndex() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) { if (i > 0) sb.append(R); sb.append("app").append(F).append("App number ").append(i); }
        assertEquals(5000, RustCore.setSearchIndex(sb.toString()));
        assertEquals(24, RustCore.search("app", 0).split(",").length);
    }
}
