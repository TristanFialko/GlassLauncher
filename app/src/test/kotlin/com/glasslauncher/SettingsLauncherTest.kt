package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** M4 audit: `has` (WebView) keeps answering false on an internal error; `hasOrThrow` (native Search) lets it through. */
class SettingsLauncherTest {
    @Test fun availableAsksForTheKindsAction() {
        val asked = ArrayList<String>()
        assertTrue(SettingsLauncher.available("wifi") { asked.add(it); true })
        assertFalse(SettingsLauncher.available("bluetooth") { asked.add(it); false })
        assertEquals(listOf(SettingsLauncher.actionFor("wifi"), SettingsLauncher.actionFor("bluetooth")), asked)
    }

    @Test fun availablePropagatesAnInternalError() {
        val e = SecurityException("pm")
        try {
            SettingsLauncher.available("wifi") { throw e }
            fail("expected the resolver's exception")
        } catch (t: SecurityException) {
            assertSame(e, t)
        }
    }

    @Test fun webViewRuleStillAnswersFalseOnAnInternalError() {
        assertFalse(SettingsLauncher.availableOrFalse("wifi") { throw SecurityException("pm") })
        assertTrue(SettingsLauncher.availableOrFalse("wifi") { true })
        assertFalse(SettingsLauncher.availableOrFalse("wifi") { false })
    }
}
