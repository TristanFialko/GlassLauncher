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
    }

    class Row {
        @JvmField var kind = ""
        @JvmField var title = ""
        @JvmField val cards = ArrayList<Card>()
    }

    @JvmField val rows = ArrayList<Row>()
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
                    val ids = a.optJSONArray("ids")
                    if (ids != null) for (x in 0 until ids.length()) k.ids.add(ids.getString(x))
                    row.cards.add(k)
                }
                c.rows.add(row)
            }
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

        @JvmStatic
        fun initials(name: String): String {
            val sb = StringBuilder()
            for (w in name.trim().split(Regex("\\s+"))) {
                if (sb.length >= 2) break
                if (w.isNotEmpty()) sb.append(w[0])
            }
            return sb.toString().uppercase(Locale.ROOT)
        }
    }
}
