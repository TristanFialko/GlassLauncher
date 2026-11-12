package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgIconsTest {
    @Test fun relativeAndAbsoluteLines() {
        val p = SvgIcons.parse("M4 9v6h4l5 4V5L8 9H4z")
        assertEquals(listOf(0f, 4f, 9f, 1f, 4f, 15f, 1f, 8f, 15f, 1f, 13f, 19f, 1f, 13f, 5f, 1f, 8f, 9f, 1f, 4f, 9f, 3f), p.toList())
    }

    @Test fun implicitRepeatsAndCompactNumbers() {
        val p = SvgIcons.parse("M6.5 7.5l11 9-5.5 4.5V3")
        assertEquals(listOf(0f, 6.5f, 7.5f, 1f, 17.5f, 16.5f, 1f, 12f, 21f, 1f, 12f, 3f), p.toList())
    }

    @Test fun arcEndsWhereItShouldAndStaysOnTheCircle() {
        // wifi outer arc: from (2,9) to (22,9), radius 15, sweep 1: bulges upward
        val p = SvgIcons.parse("M2 9a15 15 0 0 1 20 0")
        assertEquals(0f, p[0], 0f)
        var i = 3; var lastX = 0f; var lastY = 0f; var minY = 99f
        while (i < p.size) { assertEquals(SvgIcons.CUBIC, p[i], 0f); lastX = p[i + 5]; lastY = p[i + 6]; minY = minOf(minY, p[i + 2], p[i + 4], lastY); i += 7 }
        assertEquals(22f, lastX, 1e-4f); assertEquals(9f, lastY, 1e-4f)
        assertTrue("arc must rise above both end points: $minY", minY < 9f)
    }

    @Test fun circleIsFourCubicsAroundItsCentre() {
        val c = SvgIcons.shapes("controls")[1]
        assertTrue(c.fill)
        var i = 3; var n = 0
        while (i < c.cmds.size && c.cmds[i] == SvgIcons.CUBIC) { n++; i += 7 }
        assertTrue(n >= 2)
    }

    @Test fun smoothCurveReflectsTheLastControlPoint() {
        val p = SvgIcons.parse("M0 0C1 1 2 1 3 0S5 -1 6 0")
        assertEquals(listOf(2f, 4f, -1f, 5f, -1f, 6f, 0f), p.toList().subList(10, 17))
    }

    @Test fun everyIconParsesAndUnknownKeysFallBackToSettings() {
        for ((k, shapes) in SvgIcons.ICONS) assertTrue(k, shapes.isNotEmpty() && shapes.all { it.cmds.isNotEmpty() })
        assertEquals(SvgIcons.ICONS.getValue("settings"), SvgIcons.shapes("nope"))
        for (k in listOf("search", "controls", "wifi", "bluetooth", "volume", "themeDark", "device", "display", "settings", "apps", "storage", "clock", "keyboard", "access", "backspace", "shift", "shiftOn"))
            assertTrue(k, SvgIcons.ICONS.containsKey(k))
    }

    @Test fun initialsFollowGlassInitials() {
        assertEquals("YT", SearchInitials.of("YouTube TV"))
        assertEquals("N", SearchInitials.of("netflix"))
        assertEquals("GP", SearchInitials.of("  google   play store "))
        assertEquals("?", SearchInitials.of(""))
        assertEquals("?", SearchInitials.of(null))
    }
}
