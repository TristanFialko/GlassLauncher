package com.glasslauncher

import android.content.res.AssetManager
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

/** assets/nativeui/home.json (kept equal to apps.js by tools/parity/nativeui-config-check.js). */
class NativeHomeConfig {
    class Card {
        @JvmField var name = ""
        @JvmField var id = ""
        @JvmField var match = ""
        @JvmField var icon = ""
        @JvmField var dark = ""
        @JvmField val ids = ArrayList<String>()
        @JvmField var placeholder = false
        @JvmField var useAppIcon = false
        @JvmField var text = ""
        @JvmField var bg = 0xFF222222.toInt()      // home.js: a.bg||"#222"
        @JvmField var fg = 0xFFFFFFFF.toInt()           // a.fg||"#fff"
        @JvmField var size = 1.6f                  // rem, a.size||1.6
    }

    class Row {
        @JvmField var kind = ""
        @JvmField var title = ""
        @JvmField val cards = ArrayList<Card>()
    }

    @JvmField val rows = ArrayList<Row>()
    /** apps.js ICON_OVERRIDES: package id -> image path (visual only). */
    @JvmField val overrides = LinkedHashMap<String, String>()
    @JvmField var wallpaperLight = "wallpaper.jpg"
    @JvmField var wallpaperDark = "wallpaper2.jpg"
    @JvmField var soundEnabled = true
    @JvmField var soundVolume = 0.2f

    companion object {
        /** appdata.js G.apps.HIDDEN */
        @JvmField
        val HIDDEN: Pattern = Pattern.compile("^(org\\.tizen|com\\.samsung|DU\\.TV\\.PRODA)", Pattern.CASE_INSENSITIVE)

        @JvmStatic
        fun load(am: AssetManager): NativeHomeConfig =
            parse(am.open("nativeui/home.json").use { String(it.readBytes(), Charsets.UTF_8) })

        @JvmStatic
        fun parse(json: String): NativeHomeConfig {
            val o = JSONObject(json)
            val c = NativeHomeConfig()
            val rs = o.getJSONArray("rows")
            for (i in 0 until rs.length()) {
                val r = rs.getJSONObject(i)
                val row = Row()
                row.kind = r.optString("kind")
                row.title = r.optString("title")
                val apps = r.getJSONArray("apps")
                for (j in 0 until apps.length()) {
                    val a = apps.getJSONObject(j)
                    val k = Card()
                    k.name = a.optString("name")
                    k.id = a.optString("id")
                    k.match = a.optString("match")
                    k.icon = a.optString("icon")
                    k.dark = a.optString("dark")
                    k.placeholder = a.optBoolean("placeholder", false)
                    k.useAppIcon = a.optBoolean("useAppIcon", false)
                    k.text = a.optString("text")
                    k.bg = parseCssColor(a.optString("bg"), k.bg)
                    k.fg = parseCssColor(a.optString("fg"), k.fg)
                    if (a.has("size")) k.size = a.optDouble("size", 1.6).toFloat().takeIf { it > 0f } ?: 1.6f
                    val ids = a.optJSONArray("ids")
                    if (ids != null) for (x in 0 until ids.length()) k.ids.add(ids.getString(x))
                    row.cards.add(k)
                }
                c.rows.add(row)
            }
            val ov = o.optJSONObject("overrides")
            if (ov != null) for (key in ov.keys()) { val p = ov.optString(key); if (p.isNotEmpty()) c.overrides[key] = p }
            c.wallpaperLight = o.optString("wallpaperLight", "wallpaper.jpg")
            c.wallpaperDark = o.optString("wallpaperDark", "wallpaper2.jpg")
            c.soundEnabled = o.optBoolean("soundEnabled", true)
            c.soundVolume = o.optDouble("soundVolume", 0.2).toFloat()
            return c
        }

        /**
         * appdata.js G.apps.resolve: configured id if installed, else the first installed of ids, else the first visible,
         * non-hidden installed app whose name matches. Null if none (the card then says it is not installed).
         * [installed] is package id -> label in Android's order, limited to apps the WebView would list.
         */
        @JvmStatic
        fun resolve(k: Card, installed: Map<String, String>): String? {
            if (k.id.isNotEmpty() && installed.containsKey(k.id)) return k.id
            for (id in k.ids) if (installed.containsKey(id)) return id
            if (k.match.isNotEmpty()) {
                val m = Pattern.compile(k.match, Pattern.CASE_INSENSITIVE)
                for (e in installed.entries) {
                    if (!HIDDEN.matcher(e.key).find() && m.matcher(e.value).find()) return e.key
                }
            }
            return null
        }

        /**
         * What a card launches: resolve(), else (like home.js, which keeps a.id) the configured id even when it is not in the
         * list, else - when no app list could be read at all - the first candidate id so a launch is still attempted.
         */
        @JvmStatic
        fun resolveForLaunch(k: Card, installed: Map<String, String>): String? {
            resolve(k, installed)?.let { return it }
            if (k.id.isNotEmpty()) return k.id
            if (installed.isEmpty() && k.ids.isNotEmpty()) return k.ids[0]
            return null
        }

        /** CSS colour (#rgb, #rrggbb, #rrggbbaa, rgb()/rgba()) to ARGB; [def] when unparseable or blank. */
        @JvmStatic
        fun parseCssColor(s: String?, def: Int): Int {
            val t = s?.trim()?.lowercase(Locale.ROOT) ?: return def
            try {
                if (t.startsWith("#")) {
                    val h = t.substring(1)
                    return when (h.length) {
                        3 -> argb(255, h[0].digitToInt(16) * 17, h[1].digitToInt(16) * 17, h[2].digitToInt(16) * 17)
                        6 -> argb(255, h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
                        8 -> argb(h.substring(6, 8).toInt(16), h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
                        else -> def
                    }
                }
                val m = Regex("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*(?:,\\s*([0-9.]+)\\s*)?\\)").matchEntire(t) ?: return def
                val a = m.groupValues[4].let { if (it.isEmpty()) 255 else Math.round(it.toFloat() * 255f) }
                return argb(a, m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            } catch (e: Exception) { return def }
        }

        private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
            (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        @JvmStatic
        fun initials(name: String): String {
            val sb = StringBuilder()
            for (w in name.trim().split(Regex("\\s+"))) {
                if (sb.length >= 2) break
                if (w.isNotEmpty()) sb.append(w[0])
            }
            return if (sb.isEmpty()) "?" else sb.toString().uppercase(Locale.ROOT)
        }
    }
}
