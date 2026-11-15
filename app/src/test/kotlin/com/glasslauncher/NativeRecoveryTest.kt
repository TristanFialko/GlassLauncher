package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N6 native crash-loop recovery (replaces UiRouterTest: there is no WebView UI to hand HOME back to). */
class NativeRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val none = NativeRecovery.State()

    @Test fun oneCrashDoesNotEnterSafeMode() {
        assertFalse(NativeRecovery.afterCrash(none, 1_000, "E").safeAt(1_000))
    }

    @Test fun secondCrashInsideWindowEntersSafeMode() {
        val once = NativeRecovery.afterCrash(none, 1_000, "E")
        val now = 1_000 + NativeRecovery.CRASH_WINDOW_MS - 1
        val twice = NativeRecovery.afterCrash(once, now, "IllegalStateException")
        assertTrue(twice.safeAt(now))
        assertEquals(now + NativeRecovery.SAFE_MODE_MS, twice.safeUntil)
        assertEquals("native UI crashed 2x within 10 min (IllegalStateException)", twice.reason)
    }

    @Test fun crashesOutsideWindowDoNotAccumulate() {
        val once = NativeRecovery.afterCrash(none, 1_000, "E")
        val later = NativeRecovery.afterCrash(once, 1_000 + NativeRecovery.CRASH_WINDOW_MS, "E")
        assertFalse(later.safeAt(1_000 + NativeRecovery.CRASH_WINDOW_MS))
        assertEquals(listOf(1_000 + NativeRecovery.CRASH_WINDOW_MS), later.crashes)
    }

    @Test fun safeModeExpiresWithAFreshBudget() {
        val twice = NativeRecovery.afterCrash(NativeRecovery.afterCrash(none, 1_000, "E"), 2_000, "E")
        val after = 2_000 + NativeRecovery.SAFE_MODE_MS
        assertEquals(twice, NativeRecovery.expire(twice, after - 1))
        assertEquals(none, NativeRecovery.expire(twice, after))
    }

    @Test fun roundTripsAndIgnoresMalformedEntries() {
        val s = NativeRecovery.State(listOf(1L, 2L), 99L, "why")
        assertEquals(s, NativeRecovery.parse(NativeRecovery.format(s)))
        assertEquals(NativeRecovery.State(listOf(5L, 7L), 0L, ""), NativeRecovery.parse("crashes=5, x,,7\nsafeUntil=nope\n"))
    }

    @Test fun newlinesInTheReasonCannotCorruptTheFile() {
        val s = NativeRecovery.State(listOf(1L), 0L, "a\nsafeUntil=999999")
        val back = NativeRecovery.parse(NativeRecovery.format(s))
        assertEquals(0L, back.safeUntil)
        assertEquals("a safeUntil=999999", back.reason)
    }

    // Deaths the uncaught-exception handler never sees (a signal in libglass.so, a killed hung process): unclean starts.

    @Test fun cleanStartsNeverEnterSafeMode() {
        val dir = tmp.newFolder()
        assertFalse(NativeRecovery.recordStartIn(dir, 1_000))
        NativeRecovery.markHealthyIn(dir)
        assertFalse(NativeRecovery.hasUnconfirmedStartIn(dir))
        assertFalse(NativeRecovery.recordStartIn(dir, 2_000))
        assertTrue(NativeRecovery.hasUnconfirmedStartIn(dir))
    }

    @Test fun twoUncleanStartsEnterSafeModeOnTheThird() {
        val dir = tmp.newFolder()
        assertFalse(NativeRecovery.recordStartIn(dir, 1_000))   // dies without confirming
        assertFalse(NativeRecovery.recordStartIn(dir, 2_000))   // counts 1_000; dies again
        assertTrue(NativeRecovery.recordStartIn(dir, 3_000))    // counts 2_000: limit reached
        assertEquals(listOf(1_000L, 2_000L), NativeRecovery.stateIn(dir).crashes)
        assertTrue(NativeRecovery.stateIn(dir).reason.contains(NativeRecovery.UNCLEAN_EXIT))
    }

    @Test fun recordedCrashIsNotCountedAgainAsAnUncleanStart() {
        val dir = tmp.newFolder()
        NativeRecovery.recordStartIn(dir, 1_000)
        NativeRecovery.recordCrashIn(dir, 1_500, "E")
        assertFalse(NativeRecovery.hasUnconfirmedStartIn(dir))
        assertFalse(NativeRecovery.recordStartIn(dir, 2_000))
        assertEquals(listOf(1_500L), NativeRecovery.stateIn(dir).crashes)
    }

    @Test fun safeModeSearchBackendReportsRustUnavailable() {
        assertFalse(SafeModeBackend.available)
        assertEquals(null, SafeModeBackend.prepare("x"))
        assertEquals(null, SafeModeBackend.search("x", 5))
    }
}
