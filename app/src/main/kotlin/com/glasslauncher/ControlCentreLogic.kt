package com.glasslauncher

import org.json.JSONObject

/** Icon keys of the Control Centre tiles (NativeControlCentre maps them to NativeIcons). */
object CcIcon {
    const val WIFI = 0
    const val WIFI_OFF = 1
    const val ETHERNET = 2
    const val BLUETOOTH = 3
    const val BLUETOOTH_OFF = 4
    const val VOLUME = 5
    const val VOLUME_MUTED = 6
    const val THEME = 7
    const val DEVICE = 8
    const val SETTINGS = 9
    const val DISPLAY = 10
    const val SEARCH = 11
}

/** What one tile shows. Strings are final: nothing is formatted while drawing. */
class CcTile(val value: String, val sub: String, val on: Boolean, val icon: Int, val dim: Boolean = false)

/** Result of a volume read: tile text plus the numbers the draw and the key handler need. */
class CcVolume(val tile: CcTile, val pct: Int, val muted: Boolean, val fixed: Boolean, val cur: Int, val max: Int, val available: Boolean = true)

/** Pure Control Centre rules (controlcentre.js): tile table, spatial navigation, texts. No Android types. */
object CcLogic {
    const val T_THEME = 0
    const val T_WIFI = 1
    const val T_BT = 2
    const val T_VOL = 3
    const val T_DEV = 4
    const val T_SET = 5
    const val T_SND = 6
    const val T_DSP = 7
    const val T_SRCH = 8
    const val COUNT = 9

    const val DOT = "  \u00b7  "

    val IDS = arrayOf("theme", "wifi", "bt", "vol", "dev", "set", "snd", "dsp", "srch")
    val TITLES = arrayOf("Appearance", "Wi-Fi", "Bluetooth", "Volume", "This box", "Settings", "Sound", "Display", "Search")

    /** Grid area per tile: row start, column start, row end, column end (1-based, end exclusive) as in SPEC. */
    val AREA = arrayOf(
        intArrayOf(1, 1, 3, 3), intArrayOf(1, 3, 2, 5), intArrayOf(2, 3, 3, 5), intArrayOf(3, 1, 4, 5), intArrayOf(4, 1, 5, 5),
        intArrayOf(5, 1, 6, 2), intArrayOf(5, 2, 6, 3), intArrayOf(5, 3, 6, 4), intArrayOf(5, 4, 6, 5),
    )
    val ROW_H = floatArrayOf(152f, 152f, 160f, 140f, 124f)
    const val GAP = 20f
    const val COL_W = 166f

    fun colX(col0: Int): Float = col0 * (COL_W + GAP)
    fun rowY(row0: Int): Float { var y = 0f; for (r in 0 until row0) y += ROW_H[r] + GAP; return y }
    fun tileX(i: Int): Float = colX(AREA[i][1] - 1)
    fun tileY(i: Int): Float = rowY(AREA[i][0] - 1)
    fun tileW(i: Int): Float { val n = AREA[i][3] - AREA[i][1]; return n * COL_W + (n - 1) * GAP }
    fun tileH(i: Int): Float {
        var h = 0f
        for (r in AREA[i][0] - 1 until AREA[i][2] - 1) h += ROW_H[r]
        return h + (AREA[i][2] - AREA[i][0] - 1) * GAP
    }

    const val LEFT = 0
    const val UP = 1
    const val RIGHT = 2
    const val DOWN = 3

    /**
     * G.spatial.move: nearest centre in a direction (candidate must lie more than 4 px that way), score
     * primary + 2.5 * perpendicular. -1 when nothing lies that way (focus stays: no wrap, no sound).
     */
    fun spatial(cx: FloatArray, cy: FloatArray, i: Int, dir: Int): Int {
        var best = -1
        var bs = 1e9f
        for (j in cx.indices) {
            if (j == i) continue
            val dx = cx[j] - cx[i]
            val dy = cy[j] - cy[i]
            val prim: Float
            val perp: Float
            when (dir) {
                RIGHT -> { if (dx <= 4f) continue; prim = dx; perp = Math.abs(dy) }
                LEFT -> { if (dx >= -4f) continue; prim = -dx; perp = Math.abs(dy) }
                DOWN -> { if (dy <= 4f) continue; prim = dy; perp = Math.abs(dx) }
                else -> { if (dy >= -4f) continue; prim = -dy; perp = Math.abs(dx) }
            }
            val s = prim + perp * 2.5f
            if (s < bs) { bs = s; best = j }
        }
        return best
    }

    /** NAV[tile][dir] precomputed from the tile geometry, so key handling does no work. */
    val NAV: Array<IntArray> = run {
        val cx = FloatArray(COUNT) { tileX(it) + tileW(it) / 2f }
        val cy = FloatArray(COUNT) { tileY(it) + tileH(it) / 2f }
        Array(COUNT) { i -> IntArray(4) { d -> spatial(cx, cy, i, d) } }
    }

    /** Tab / Shift+Tab: cycles with wrap. */
    fun tab(i: Int, dir: Int): Int = (i + dir + COUNT) % COUNT

    // ---------------- volume ----------------
    fun volumeStep(max: Int): Int = maxOf(1, Math.round(max / 20f))
    fun volumeTarget(cur: Int, max: Int, dir: Int): Int = maxOf(0, minOf(max, cur + dir * volumeStep(max)))
    fun percent(cur: Int, max: Int): Int = if (max > 0) Math.floor(cur * 100.0 / max + 0.5).toInt() else 0

    // ---------------- texts (strings match controlcentre.js refresh*) ----------------
    private fun obj(json: String?): JSONObject? =
        try { if (json == null || json == "null") null else JSONObject(json) } catch (e: Exception) { null }

    fun wifi(json: String?): CcTile {
        val w = obj(json) ?: return CcTile("Unavailable", "Status unavailable", false, CcIcon.WIFI_OFF)
        val ssid = w.optString("ssid", "")
        val connected = w.optBoolean("wifiConnected")
        return when {
            w.optBoolean("ethernet") ->
                CcTile("Ethernet", "Wired connection" + (if (connected && ssid.isNotEmpty()) DOT + "Wi-Fi " + ssid else ""), true, CcIcon.ETHERNET)
            connected ->
                CcTile("Connected", if (ssid.isNotEmpty()) ssid else if (w.optBoolean("locationGranted")) "Network name unavailable" else "Network name hidden", true, CcIcon.WIFI)
            w.optBoolean("wifiEnabled") -> CcTile("Not connected", "OK opens Wi-Fi settings", false, CcIcon.WIFI_OFF)
            else -> CcTile("Off", "OK opens Wi-Fi settings", false, CcIcon.WIFI_OFF)
        }
    }

    fun bluetooth(json: String?): CcTile {
        val b = obj(json)
        if (b == null || !b.optBoolean("supported")) return CcTile("Unavailable", "Not available on this box", false, CcIcon.BLUETOOTH_OFF)
        if (!b.optBoolean("enabled")) return CcTile("Off", "OK opens Bluetooth settings", false, CcIcon.BLUETOOTH_OFF)
        val c = names(b, "connected")
        val p = names(b, "paired")
        val known = b.optBoolean("connectedKnown")
        val sub = when {
            known && c.isNotEmpty() -> c.take(2).joinToString(", ") + (if (c.size > 2) " +" + (c.size - 2) else "")
            known -> "No device connected \u00b7 " + p.size + " paired"
            p.isNotEmpty() -> p.size.toString() + " paired: " + p.take(2).joinToString(", ") + (if (p.size > 2) "..." else "")
            else -> "No paired devices"
        }
        return CcTile("On", sub, true, CcIcon.BLUETOOTH)
    }

    private fun names(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return List(a.length()) { a.optString(it) }
    }

    fun audioOutput(json: String?): String = obj(json)?.optString("label", "") ?: ""

    fun volume(json: String?, output: String): CcVolume {
        val v = obj(json) ?: return CcVolume(CcTile("", "Unavailable", false, CcIcon.VOLUME), 0, false, false, 0, 0, false)
        val cur = v.optInt("cur")
        val max = v.optInt("max")
        val muted = v.optBoolean("muted")
        val fixed = v.optBoolean("fixed")
        val pct = percent(cur, max)
        val value = if (fixed) "" else if (muted) "Muted" else "$pct%"
        val sub = (if (output.isNotEmpty()) "Output: $output$DOT" else "") +
            (if (fixed) "Fixed volume: your TV or soundbar controls it" else if (muted) "OK to unmute" else "Left / Right to adjust, OK to mute")
        return CcVolume(CcTile(value, sub, !muted && !fixed, if (muted) CcIcon.VOLUME_MUTED else CcIcon.VOLUME, fixed), pct, muted, fixed, cur, max)
    }


    fun device(json: String?): CcTile {
        val d = obj(json) ?: return CcTile("", "Unavailable", false, CcIcon.DEVICE)
        fun s(k: String, dflt: String) = d.optString(k, "").ifEmpty { dflt }
        val sub = d.optString("model", "") + DOT + "Android " + s("android", "?") + DOT + s("ip", "no IP") + DOT + s("freeGb", "?") + " GB free"
        return CcTile("", sub, false, CcIcon.DEVICE)
    }

    const val FIXED_VOLUME_TOAST = "Volume is fixed: use your TV or soundbar volume."
}

/**
 * Poll state machine: the 2 s refresh runs only while the Control Centre is open and the activity is resumed.
 * update() reports the transition so the owner posts or cancels exactly one timer.
 */
class CcPollGate {
    companion object {
        const val NONE = 0
        const val START = 1
        const val STOP = 2
        const val INTERVAL_MS = 2000L
        const val RESUME_DELAY_MS = 400L
        const val VOLUME_SYNC_DELAY_MS = 250L
    }

    var running = false
        private set

    fun update(open: Boolean, resumed: Boolean): Int {
        val want = open && resumed
        return if (want && !running) { running = true; START } else if (!want && running) { running = false; STOP } else NONE
    }
}
