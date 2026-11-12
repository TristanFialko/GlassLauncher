package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class UiRouterTest {
    private val native = UiRouter.State(mode = UiRouter.NATIVE)

    @Test fun defaultsToWebViewForMissingOrUnknownMode() {
        assertEquals(UiRouter.WEBVIEW, UiRouter.parse("").mode)
        assertEquals(UiRouter.WEBVIEW, UiRouter.parse("mode=something").mode)
    }

    @Test fun roundTrips() {
        val s = UiRouter.State(UiRouter.NATIVE, listOf(1L, 2L), "why")
        assertEquals(s, UiRouter.parse(UiRouter.format(s)))
    }

    @Test fun oneCrashKeepsNative() {
        assertEquals(UiRouter.NATIVE, UiRouter.afterCrash(native, 1_000, "E").mode)
    }

    @Test fun secondCrashInsideWindowFallsBack() {
        val once = UiRouter.afterCrash(native, 1_000, "E")
        val twice = UiRouter.afterCrash(once, 1_000 + UiRouter.CRASH_WINDOW_MS - 1, "E")
        assertEquals(UiRouter.WEBVIEW, twice.mode)
    }

    @Test fun crashesOutsideWindowDoNotAccumulate() {
        val once = UiRouter.afterCrash(native, 1_000, "E")
        val later = UiRouter.afterCrash(once, 1_000 + UiRouter.CRASH_WINDOW_MS, "E")
        assertEquals(UiRouter.NATIVE, later.mode)
        assertEquals(listOf(1_000 + UiRouter.CRASH_WINDOW_MS), later.crashes)
    }
}
