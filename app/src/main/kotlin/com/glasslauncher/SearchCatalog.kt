package com.glasslauncher

/**
 * Builds the search snapshot from live facts exactly as registry.js `snapshot()`/`listed()` do (wire format of
 * rust/glass-core/src/snapshot.rs), and keeps the entry list needed by the Kotlin fallback ranking. Pure Kotlin: the facts
 * (installed apps, aliases, which settings pages exist) are gathered by the caller off the UI thread.
 */
object SearchCatalog {
    const val RS = '\u001e'; const val US = '\u001f'; const val GS = '\u001d'
    const val HEADER = "glass-snapshot\u001f1"

    /** shortcuts.js settings pages, in registration order; "main" is always available and superseded by a Settings app. */
    val SETTINGS_KINDS = listOf("wifi", "bluetooth", "display", "sound", "apps", "storage", "datetime", "keyboard", "accessibility", "device", "main")

    private class S(val kind: String, val title: String, val sub: String, val icon: String, val kw: List<String>, val sup: List<String> = emptyList())
    private val SETTINGS = listOf(
        S("wifi", "Wi-Fi", "Network settings", "wifi", listOf("wifi", "wireless", "network", "internet", "connection")),
        S("bluetooth", "Bluetooth", "Bluetooth settings", "bluetooth", listOf("pairing", "remote", "headphones", "devices")),
        S("display", "Display", "Display settings", "display", listOf("screen", "resolution", "hdmi", "picture")),
        S("sound", "Sound", "Sound settings", "volume", listOf("audio", "speaker", "surround")),
        S("apps", "Apps", "Manage installed applications", "apps", listOf("application management", "uninstall", "manage apps", "permissions")),
        S("storage", "Storage", "Storage settings", "storage", listOf("space", "memory", "disk")),
        S("datetime", "Date & time", "Date and time settings", "clock", listOf("clock", "timezone", "time zone")),
        S("keyboard", "Keyboard", "Keyboard and input settings", "keyboard", listOf("input", "typing", "language")),
        S("accessibility", "Accessibility", "Accessibility settings", "access", listOf("captions", "talkback", "high contrast")),
        S("device", "About this box", "Model, Android version, status", "device", listOf("device info", "device information", "about", "version", "model", "system info")),
        S("main", "Settings", "Android TV Settings", "settings", listOf("android settings", "system", "preferences", "tv settings"),
            listOf("com.android.tv.settings", "com.android.settings")),
    )

    /** shortcuts.js: `set:*` rows (availability from [settingsAvailable]) followed by the three Glass controls. */
    fun shortcuts(settingsAvailable: (String) -> Boolean): List<CatalogEntry> {
        val out = ArrayList<CatalogEntry>(SETTINGS.size + 3)
        for (s in SETTINGS) out.add(CatalogEntry("set:" + s.kind, "shortcut", s.title, s.sub, s.kw, "Settings", s.icon, s.sup,
            s.kind == "main" || settingsAvailable(s.kind), SearchAction.OpenSettings(s.kind)))
        out.add(CatalogEntry("glass:cc", "shortcut", "Control Centre", "Wi-Fi, Bluetooth, volume and appearance", listOf("controls", "quick settings", "panel"),
            "Glass", "controls", action = SearchAction.OpenControlCentre(false)))
        out.add(CatalogEntry("glass:volume", "shortcut", "Volume", "Open the Glass volume controls", listOf("sound level", "mute", "louder", "quieter"),
            "Glass", "volume", action = SearchAction.OpenControlCentre(true)))
        out.add(CatalogEntry("glass:theme", "shortcut", "Appearance", "Switch between light and dark", listOf("dark mode", "light mode", "theme", "night", "day"),
            "Glass", "themeDark", action = SearchAction.ToggleTheme))
        return out
    }

    /** shortcuts.js: default alias for the Play Store, applied only when the config has none for that package. */
    fun mergeAliases(configured: Map<String, List<String>>): Map<String, List<String>> {
        if (configured.containsKey("com.android.vending")) return configured
        return configured + ("com.android.vending" to listOf("play store", "google play", "store", "app store", "download apps"))
    }

    sealed class Source {
        class App(val app: InstalledApp, val aliases: List<String>) : Source()
        class Entry(val entry: CatalogEntry) : Source()
    }

    /**
     * [wire] is null when registry.js would stay on its JavaScript path (a control character in a package id, a lone
     * surrogate): the caller then uses [fallbackEntries]. [sources] is indexed by snapshot record number.
     */
    class Snapshot(val wire: String?, val installed: List<InstalledApp>, val sources: List<Source>) {
        /** registry.entries(): visibility, availability and supersededBy rules, in provider order (apps, extras, shortcuts). */
        fun fallbackEntries(): Pair<List<Source>, List<SearchRanking.Candidate>> {
            val ids = HashSet<String>(installed.size * 2)
            for (a in installed) ids.add(a.id)
            val src = ArrayList<Source>(sources.size)
            val cand = ArrayList<SearchRanking.Candidate>(sources.size)
            for (s in sources) when (s) {
                is Source.App -> if (SearchRanking.appVisible(s.app)) { src.add(s); cand.add(SearchRanking.Candidate(true, s.app.name, s.aliases)) }
                is Source.Entry -> {
                    val e = s.entry
                    if (!e.available || e.supersededBy.any { it in ids }) continue
                    src.add(s); cand.add(SearchRanking.Candidate(e.kind == "app", e.title, e.keywords))
                }
            }
            return src to cand
        }

        fun resultFor(s: Source): SearchResult = when (s) {
            is Source.App -> SearchResult(ResultKind.APP, s.app.name, s.app.id, "App", "", s.app.id, SearchAction.LaunchApp(s.app.id, s.app.name))
            is Source.Entry -> s.entry.let {
                SearchResult(if (it.kind == "app") ResultKind.APP else ResultKind.SHORTCUT, it.title, it.subtitle, it.tag, it.icon.ifEmpty { "settings" }, null, it.action)
            }
        }
    }

    private fun hasCtl(s: String): Boolean { for (c in s) if (c in '\u001c'..'\u001f') return true; return false }
    private fun clean(s: String): String = if (hasCtl(s)) s.filter { it !in '\u001c'..'\u001f' } else s

    /** True for text with an unpaired UTF-16 surrogate (no UTF-8 form), registry.js LONE. */
    fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) { if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) i++ else return true }
            else if (Character.isLowSurrogate(c)) return true
            i++
        }
        return false
    }

    /** [extra]: other providers' entries (none in production); they sit between the installed apps and the shortcuts, as in registry.js. */
    fun build(
        installed: List<InstalledApp>,
        aliases: Map<String, List<String>>,
        extra: List<CatalogEntry> = emptyList(),
        shortcuts: List<CatalogEntry>,
    ): Snapshot {
        val sb = StringBuilder(HEADER)
        val src = ArrayList<Source>(installed.size + extra.size + shortcuts.size)
        var ok = true
        for (x in installed) {
            if (hasCtl(x.id)) ok = false
            val al = aliases[x.id] ?: emptyList()
            sb.append(RS).append('i').append(US).append(x.id).append(US).append(clean(x.name)).append(US).append(if (x.show) '1' else '0')
            for (a in al) sb.append(US).append(clean(a))
            src.add(Source.App(x, al))
        }
        for (e in extra + shortcuts) {
            sb.append(RS).append('e').append(US).append(if (e.kind == "app") "app" else if (e.kind == "shortcut") "shortcut" else "?")
                .append(US).append(if (e.available) '1' else '0').append(US)
            var n = 0
            for (p in e.supersededBy) if (!hasCtl(p)) { if (n++ > 0) sb.append(GS); sb.append(p) }
            sb.append(US).append(clean(e.title))
            for (k in e.keywords) sb.append(US).append(clean(k))
            src.add(Source.Entry(e))
        }
        val wire = sb.toString()
        return Snapshot(if (!ok || hasLoneSurrogate(wire)) null else wire, installed, src)
    }
}
