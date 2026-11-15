package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UiRouterTest {
    private val native = UiRouter.State(mode = UiRouter.NATIVE)

    @get:Rule val tmp = TemporaryFolder()

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

    // Stage 4a: deaths the uncaught-exception handler never sees (a signal in libglass.so, the system killing a hung
    // process) must still reach the fallback, otherwise a crash-looping native HOME never hands back to the WebView UI.

    @Test fun unconfirmedStartsCountAsCrashesAndFallBack() {
        val first = UiRouter.afterNativeStart(native, 1_000L)
        assertEquals(UiRouter.NATIVE, first.mode)
        assertEquals(listOf(1_000L), first.crashes)
        val second = UiRouter.afterNativeStart(first, 2_000L)
        assertEquals(UiRouter.WEBVIEW, second.mode)
        assertEquals("native UI crashed 2x within 10 min (UncleanExit)", second.fallbackReason)
    }

    @Test fun confirmedStartIsNotACrash() {
        assertEquals(native, UiRouter.afterNativeStart(native, null))
    }

    @Test fun uncleanExitAndJvmCrashShareOneBudget() {
        assertEquals(UiRouter.WEBVIEW, UiRouter.afterNativeStart(UiRouter.afterCrash(native, 1_000, "E"), 2_000L).mode)
    }

    @Test fun uncleanExitsOutsideTheWindowDoNotAccumulate() {
        val s = UiRouter.afterNativeStart(UiRouter.afterNativeStart(native, 1_000L), 1_000L + UiRouter.CRASH_WINDOW_MS)
        assertEquals(UiRouter.NATIVE, s.mode)
    }

    @Test fun uncleanExitsWhileWebViewIsChosenNeverChangeTheMode() {
        val s = UiRouter.afterNativeStart(UiRouter.afterNativeStart(UiRouter.State(), 1_000L), 2_000L)
        assertEquals(UiRouter.WEBVIEW, s.mode)
        assertEquals("", s.fallbackReason)
    }

    @Test fun pendingMarkerParsing() {
        assertEquals(123L, UiRouter.parsePending("123\n"))
        assertNull(UiRouter.parsePending(""))
        assertNull(UiRouter.parsePending("x"))
        assertNull(UiRouter.parsePending("0"))
        assertNull(UiRouter.parsePending("-5"))
    }

    // File-level: the exact sequence MainActivity and NativeHomeActivity perform, on a real directory.

    private fun dir(): File = tmp.newFolder()

    @Test fun crashLoopWithoutJvmExceptionFallsBackOnTheThirdStart() {
        val d = dir()
        assertTrue(UiRouter.applyExtraIn(d, UiRouter.NATIVE))
        assertEquals(UiRouter.NATIVE, UiRouter.recordNativeStartIn(d, 1_000))   // process then dies by signal: no handler, no healthy mark
        assertEquals(UiRouter.NATIVE, UiRouter.recordNativeStartIn(d, 2_000))   // 1 unclean exit counted
        assertEquals(UiRouter.WEBVIEW, UiRouter.recordNativeStartIn(d, 3_000))  // 2 unclean exits: fall back
        assertEquals(UiRouter.WEBVIEW, UiRouter.modeIn(d))
        assertFalse(UiRouter.hasUnconfirmedStartIn(d))
    }

    @Test fun healthyStartsNeverCount() {
        val d = dir()
        UiRouter.applyExtraIn(d, UiRouter.NATIVE)
        repeat(5) { i ->
            assertEquals(UiRouter.NATIVE, UiRouter.recordNativeStartIn(d, 1_000L + i))
            assertTrue(UiRouter.hasUnconfirmedStartIn(d))
            UiRouter.markNativeHealthyIn(d)
            assertFalse(UiRouter.hasUnconfirmedStartIn(d))
        }
        assertEquals(UiRouter.NATIVE, UiRouter.modeIn(d))
    }

    @Test fun jvmCrashIsNotCountedTwice() {
        val d = dir()
        UiRouter.applyExtraIn(d, UiRouter.NATIVE)
        UiRouter.recordNativeStartIn(d, 1_000)
        UiRouter.recordNativeCrashIn(d, 1_500, "IllegalStateException")         // handler records it and clears the marker
        assertEquals(UiRouter.NATIVE, UiRouter.recordNativeStartIn(d, 2_000))   // still one crash, not two
    }

    @Test fun optingInAgainGivesAFreshBudget() {
        val d = dir()
        UiRouter.applyExtraIn(d, UiRouter.NATIVE)
        UiRouter.recordNativeStartIn(d, 1_000)
        UiRouter.recordNativeStartIn(d, 2_000)
        UiRouter.applyExtraIn(d, UiRouter.NATIVE)                                // owner re-runs the opt-in command
        assertFalse(UiRouter.hasUnconfirmedStartIn(d))
        assertEquals(UiRouter.NATIVE, UiRouter.recordNativeStartIn(d, 3_000))
    }

    @Test fun webViewChoiceClearsTheMarker() {
        val d = dir()
        UiRouter.applyExtraIn(d, UiRouter.NATIVE)
        UiRouter.recordNativeStartIn(d, 1_000)
        UiRouter.applyExtraIn(d, UiRouter.WEBVIEW)
        assertFalse(UiRouter.hasUnconfirmedStartIn(d))
        assertEquals(UiRouter.WEBVIEW, UiRouter.modeIn(d))
    }

    @Test fun corruptMarkerIsIgnored() {
        val d = dir()
        UiRouter.applyExtraIn(d, UiRouter.NATIVE)
        File(d, "native-start.pending").writeText("garbage")
        assertEquals(UiRouter.NATIVE, UiRouter.recordNativeStartIn(d, 1_000))
        UiRouter.markNativeHealthyIn(d)
        assertEquals(UiRouter.NATIVE, UiRouter.recordNativeStartIn(d, 2_000))
    }
}
