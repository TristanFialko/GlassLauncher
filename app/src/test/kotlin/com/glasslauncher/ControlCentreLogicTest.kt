package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCentreLogicTest {
    private val D = CcLogic.DOT

    // ---- Wi-Fi ----
    @Test fun wifiUnavailable() {
        val t = CcLogic.wifi("null")
        assertEquals("Unavailable", t.value); assertEquals("Status unavailable", t.sub); assertFalse(t.on); assertEquals(CcIcon.WIFI_OFF, t.icon)
    }
    @Test fun wifiEthernetWinsAndAddsSsid() {
        val t = CcLogic.wifi("""{"ethernet":true,"wifiConnected":true,"ssid":"Home"}""")
        assertEquals("Ethernet", t.value); assertEquals("Wired connection" + D + "Wi-Fi Home", t.sub); assertEquals(CcIcon.ETHERNET, t.icon); assertTrue(t.on)
        assertEquals("Wired connection", CcLogic.wifi("""{"ethernet":true,"wifiConnected":false,"ssid":"Home"}""").sub)
    }
    @Test fun wifiConnectedNames() {
        assertEquals("Home", CcLogic.wifi("""{"wifiConnected":true,"ssid":"Home"}""").sub)
        assertEquals("Network name unavailable", CcLogic.wifi("""{"wifiConnected":true,"ssid":"","locationGranted":true}""").sub)
        assertEquals("Network name hidden", CcLogic.wifi("""{"wifiConnected":true,"ssid":"","locationGranted":false}""").sub)
    }
    @Test fun wifiNotConnectedAndOff() {
        val n = CcLogic.wifi("""{"wifiEnabled":true}""")
        assertEquals("Not connected", n.value); assertEquals("OK opens Wi-Fi settings", n.sub); assertFalse(n.on)
        assertEquals("Off", CcLogic.wifi("""{"wifiEnabled":false}""").value)
    }

    // ---- Bluetooth ----
    @Test fun bluetoothStates() {
        assertEquals("Not available on this box", CcLogic.bluetooth("""{"supported":false}""").sub)
        assertEquals("Not available on this box", CcLogic.bluetooth(null).sub)
        val off = CcLogic.bluetooth("""{"supported":true,"enabled":false}""")
        assertEquals("Off", off.value); assertEquals("OK opens Bluetooth settings", off.sub); assertEquals(CcIcon.BLUETOOTH_OFF, off.icon)
    }
    @Test fun bluetoothConnectedList() {
        fun bt(c: String) = CcLogic.bluetooth("""{"supported":true,"enabled":true,"connectedKnown":true,"paired":["a","b","c"],"connected":$c}""")
        assertEquals("a", bt("""["a"]""").sub)
        assertEquals("a, b", bt("""["a","b"]""").sub)
        assertEquals("a, b +1", bt("""["a","b","c"]""").sub)
        assertEquals("No device connected \u00b7 3 paired", bt("[]").sub)
        assertEquals("On", bt("[]").value)
    }
    @Test fun bluetoothConnectedUnknown() {
        fun bt(p: String) = CcLogic.bluetooth("""{"supported":true,"enabled":true,"connectedKnown":false,"paired":$p,"connected":[]}""")
        assertEquals("No paired devices", bt("[]").sub)
        assertEquals("1 paired: a", bt("""["a"]""").sub)
        assertEquals("3 paired: a, b...", bt("""["a","b","c"]""").sub)
    }

    // ---- Volume ----
    @Test fun volumeTexts() {
        val v = CcLogic.volume("""{"cur":5,"max":15,"muted":false,"fixed":false}""", "HDMI")
        assertEquals("33%", v.tile.value); assertEquals(33, v.pct)
        assertEquals("Output: HDMI" + D + "Left / Right to adjust, OK to mute", v.tile.sub)
        assertTrue(v.tile.on); assertEquals(CcIcon.VOLUME, v.tile.icon)
        val m = CcLogic.volume("""{"cur":5,"max":15,"muted":true,"fixed":false}""", "")
        assertEquals("Muted", m.tile.value); assertEquals("OK to unmute", m.tile.sub); assertEquals(CcIcon.VOLUME_MUTED, m.tile.icon); assertFalse(m.tile.on)
        val f = CcLogic.volume("""{"cur":5,"max":15,"muted":false,"fixed":true}""", "")
        assertEquals("", f.tile.value); assertEquals("Fixed volume: your TV or soundbar controls it", f.tile.sub); assertTrue(f.tile.dim)
    }
    @Test fun volumeUnavailable() {
        val v = CcLogic.volume("null", "HDMI")
        assertEquals("Unavailable", v.tile.sub); assertEquals("", v.tile.value); assertFalse(v.available)
    }
    @Test fun volumeStepAndClamp() {
        assertEquals(1, CcLogic.volumeStep(15))      // round(0.75)
        assertEquals(5, CcLogic.volumeStep(100))
        assertEquals(1, CcLogic.volumeStep(7))
        assertEquals(2, CcLogic.volumeStep(30))
        assertEquals(15, CcLogic.volumeTarget(15, 15, 1))
        assertEquals(0, CcLogic.volumeTarget(0, 15, -1))
        assertEquals(60, CcLogic.volumeTarget(55, 100, 1))
    }
    @Test fun percentRoundsHalfUp() {
        assertEquals(0, CcLogic.percent(3, 0))
        assertEquals(7, CcLogic.percent(1, 15))
        assertEquals(50, CcLogic.percent(1, 2))
        assertEquals(100, CcLogic.percent(15, 15))
    }

    // ---- This box ----
    @Test fun deviceLine() {
        val t = CcLogic.device("""{"model":"Arris VIP7802","android":"11","ip":"192.168.1.5","freeGb":"12.3"}""")
        assertEquals("Arris VIP7802" + D + "Android 11" + D + "192.168.1.5" + D + "12.3 GB free", t.sub)
        assertEquals("X" + D + "Android ?" + D + "no IP" + D + "? GB free", CcLogic.device("""{"model":"X","ip":""}""").sub)
        assertEquals("Unavailable", CcLogic.device("null").sub)
    }
    @Test fun audioOutputLabel() {
        assertEquals("HDMI ARC", CcLogic.audioOutput("""{"label":"HDMI ARC"}"""))
        assertEquals("", CcLogic.audioOutput("null"))
    }

    // ---- grid + focus navigation ----
    @Test fun gridGeometry() {
        assertEquals(352f, CcLogic.tileW(0), 0.01f)       // 2 columns
        assertEquals(324f, CcLogic.tileH(0), 0.01f)       // 2 rows + gap
        assertEquals(724f, CcLogic.tileW(3), 0.01f)       // full width
        assertEquals(808f, CcLogic.rowY(4) + CcLogic.ROW_H[4], 0.01f)
        assertEquals(166f, CcLogic.tileW(5), 0.01f)
    }
    @Test fun navigationTable() {
        val L = CcLogic.LEFT; val U = CcLogic.UP; val R = CcLogic.RIGHT; val Dn = CcLogic.DOWN
        val n = CcLogic.NAV
        assertEquals(CcLogic.T_WIFI, n[CcLogic.T_THEME][R])
        assertEquals(CcLogic.T_VOL, n[CcLogic.T_THEME][Dn])
        assertEquals(CcLogic.T_SET, n[CcLogic.T_THEME][L]); assertEquals(CcLogic.T_WIFI, n[CcLogic.T_THEME][U])   // G.spatial quirk: any centre on that side counts
        assertEquals(CcLogic.T_BT, n[CcLogic.T_WIFI][Dn])
        assertEquals(CcLogic.T_THEME, n[CcLogic.T_WIFI][L])
        assertEquals(CcLogic.T_SRCH, n[CcLogic.T_WIFI][R]); assertEquals(-1, n[CcLogic.T_WIFI][U])   // quirk: Search sits right of the Wi-Fi centre
        assertEquals(CcLogic.T_WIFI, n[CcLogic.T_BT][U])
        assertEquals(CcLogic.T_VOL, n[CcLogic.T_BT][Dn])
        assertEquals(CcLogic.T_DEV, n[CcLogic.T_VOL][Dn])
        assertEquals(CcLogic.T_SND, n[CcLogic.T_DEV][Dn])      // Sound and Display tie on distance; the first in DOM order wins
    }
    @Test fun navigationBottomRow() {
        val n = CcLogic.NAV
        assertEquals(CcLogic.T_SND, n[CcLogic.T_SET][CcLogic.RIGHT])
        assertEquals(CcLogic.T_DSP, n[CcLogic.T_SND][CcLogic.RIGHT])
        assertEquals(CcLogic.T_SRCH, n[CcLogic.T_DSP][CcLogic.RIGHT])
        assertEquals(-1, n[CcLogic.T_SRCH][CcLogic.RIGHT]); assertEquals(-1, n[CcLogic.T_SET][CcLogic.LEFT])
        assertEquals(-1, n[CcLogic.T_SND][CcLogic.DOWN]); assertEquals(CcLogic.T_DEV, n[CcLogic.T_SND][CcLogic.UP])
        assertEquals(CcLogic.T_BT, n[CcLogic.T_SRCH][CcLogic.UP])   // same quirk: Bluetooth scores lower than This box
    }
    @Test fun tabWraps() {
        assertEquals(1, CcLogic.tab(0, 1)); assertEquals(0, CcLogic.tab(8, 1)); assertEquals(8, CcLogic.tab(0, -1))
    }
    @Test fun spatialIgnoresNearlyAlignedCandidates() {
        val cx = floatArrayOf(0f, 3f); val cy = floatArrayOf(0f, 0f)
        assertEquals(-1, CcLogic.spatial(cx, cy, 0, CcLogic.RIGHT))        // not more than 4 px away
    }

    // ---- poll state machine ----
    @Test fun pollRunsOnlyWhileOpenAndResumed() {
        val g = CcPollGate()
        assertEquals(CcPollGate.NONE, g.update(false, true))
        assertEquals(CcPollGate.START, g.update(true, true)); assertTrue(g.running)
        assertEquals(CcPollGate.NONE, g.update(true, true))
        assertEquals(CcPollGate.STOP, g.update(true, false))               // paused (e.g. Settings screen on top)
        assertEquals(CcPollGate.START, g.update(true, true))               // resumed
        assertEquals(CcPollGate.STOP, g.update(false, true))               // closed
        assertEquals(CcPollGate.NONE, g.update(false, false)); assertFalse(g.running)
    }
    @Test fun pollTimings() {
        assertEquals(2000L, CcPollGate.INTERVAL_MS); assertEquals(400L, CcPollGate.RESUME_DELAY_MS); assertEquals(250L, CcPollGate.VOLUME_SYNC_DELAY_MS)
    }
}
