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

    // Stage 4a contract (MIGRATION_PLAN.md): the fallback state must survive the file round trip MainActivity reads after a crash.

    @Test fun crashesWhileWebViewIsChosenNeverChangeTheMode() {
        val twice = UiRouter.afterCrash(UiRouter.afterCrash(UiRouter.State(), 1_000, "E"), 2_000, "E")
        assertEquals(UiRouter.WEBVIEW, twice.mode)
        assertEquals("", twice.fallbackReason)
    }

    @Test fun fallbackRecordsCountAndExceptionClassOnly() {
        val twice = UiRouter.afterCrash(UiRouter.afterCrash(native, 1_000, "IllegalStateException"), 2_000, "IllegalStateException")
        assertEquals(UiRouter.WEBVIEW, twice.mode)
        assertEquals(listOf(1_000L, 2_000L), twice.crashes)
        assertEquals("native UI crashed 2x within 10 min (IllegalStateException)", twice.fallbackReason)
    }

    @Test fun fallbackSurvivesTheFileRoundTrip() {
        val twice = UiRouter.afterCrash(UiRouter.afterCrash(native, 1_000, "E"), 2_000, "E")
        assertEquals(twice, UiRouter.parse(UiRouter.format(twice)))
    }

    @Test fun furtherCrashesAfterFallbackStayOnWebView() {
        val twice = UiRouter.afterCrash(UiRouter.afterCrash(native, 1_000, "E"), 2_000, "E")
        assertEquals(UiRouter.WEBVIEW, UiRouter.afterCrash(twice, 3_000, "E").mode)
    }

    @Test fun malformedCrashEntriesAreIgnored() {
        assertEquals(UiRouter.State(UiRouter.NATIVE, listOf(5L, 7L), ""), UiRouter.parse("mode=native\ncrashes=5, x,,7\nfallback=\n"))
    }

    @Test fun newlinesInTheReasonCannotCorruptTheFile() {
        val s = UiRouter.State(UiRouter.WEBVIEW, listOf(1L), "a\nmode=native")
        val back = UiRouter.parse(UiRouter.format(s))
        assertEquals(UiRouter.WEBVIEW, back.mode)
        assertEquals("a mode=native", back.fallbackReason)
    }
}
