package com.glasslauncher

import org.json.JSONObject

/** The single "last launched app" shown by the Recently Used panel (recent.js: `rec={id,name}`). */
class RecentApp(@JvmField val id: String, @JvmField val name: String) {
    override fun equals(other: Any?) = other is RecentApp && other.id == id && other.name == name
    override fun hashCode() = id.hashCode() * 31 + name.hashCode()
}

/**
 * Pure rules behind the Recently Used panel, ported from recent.js / glass.js. The WebView keeps its own list in its
 * localStorage (another process); this one lives in the native process's SharedPreferences and the two are independent.
 */
object HomeRecents {
    const val SELF = "com.glasslauncher"

    /** recent.js record(): ignores empty ids and this launcher itself; the name falls back to the id. Max one entry, newest wins. */
    @JvmStatic
    fun toRecord(id: String?, name: String?): RecentApp? {
        if (id.isNullOrEmpty() || id == SELF) return null
        return RecentApp(id, if (name.isNullOrEmpty()) id else name)
    }

    /** recent.js valid(): shown when the app list is unavailable (empty) or still contains the id. */
    @JvmStatic
    fun isValid(rec: RecentApp?, installedIds: Collection<String>): Boolean =
        rec != null && rec.id.isNotEmpty() && (installedIds.isEmpty() || installedIds.contains(rec.id))

    /** Name of the configured card that currently resolves to [rec], else the stored name (recent.js def()). */
    @JvmStatic
    fun displayName(rec: RecentApp, cards: List<NativeHomeConfig.Card>, installed: Map<String, String>): String =
        find(rec, cards, installed)?.name?.takeIf { it.isNotEmpty() } ?: rec.name

    /** The configured card (home row or Entertainment) whose resolved package is [rec], if any. */
    @JvmStatic
    fun find(rec: RecentApp, cards: List<NativeHomeConfig.Card>, installed: Map<String, String>): NativeHomeConfig.Card? {
        for (k in cards) if (!k.placeholder && NativeHomeConfig.resolveForLaunch(k, installed) == rec.id) return k
        return null
    }

    @JvmStatic
    fun encode(rec: RecentApp?): String? = rec?.let { JSONObject().put("id", it.id).put("name", it.name).toString() }

    @JvmStatic
    fun decode(s: String?): RecentApp? = try {
        if (s.isNullOrEmpty()) null else JSONObject(s).let { toRecord(it.optString("id"), it.optString("name")) }
    } catch (e: Exception) { null }
}

/** Pure artwork-selection rules from home.js setTileIcon() / icons.js override(). */
object HomeArt {
    enum class Source { ART, OVERRIDE, PACKAGE_ICON, NONE }

    /** `(dark&&a.dark)?a.dark:(a.icon||a.dark)`; empty when the card has no configured art. */
    @JvmStatic
    fun artPath(k: NativeHomeConfig.Card, dark: Boolean): String =
        if (dark && k.dark.isNotEmpty()) k.dark else if (k.icon.isNotEmpty()) k.icon else k.dark

    @JvmStatic
    fun override(overrides: Map<String, String>, id: String?): String? =
        if (id.isNullOrEmpty()) null else overrides[id]?.takeIf { it.isNotEmpty() }

    /** Configured art wins over an override, which wins over the installed app's own icon. */
    @JvmStatic
    fun source(k: NativeHomeConfig.Card, overrides: Map<String, String>, resolvedId: String?): Source = when {
        k.placeholder -> Source.NONE
        k.icon.isNotEmpty() || k.dark.isNotEmpty() -> Source.ART
        override(overrides, resolvedId ?: k.id) != null -> Source.OVERRIDE
        k.useAppIcon && !(resolvedId ?: k.id).isNullOrEmpty() -> Source.PACKAGE_ICON
        else -> Source.NONE
    }

    /** `.sq` rule: overrides and package icons, and any image narrower than 1.3:1, are drawn contained at 74% height. */
    @JvmStatic
    fun squareFit(w: Int, h: Int, forced: Boolean): Boolean = forced || (h > 0 && w.toFloat() / h < 1.3f)

    /** Text on a card with no artwork: its configured text, else initials (G.initials: "?" for a blank name). */
    @JvmStatic
    fun label(k: NativeHomeConfig.Card): String = if (k.text.isNotEmpty()) k.text else NativeHomeConfig.initials(k.name)
}

/** Pure header / Tab navigation from home.js. Header stop ids: 0 Search, 1 Control Centre, 2 Recently Used. */
object HomeNav {
    const val SEARCH = 0
    const val CC = 1
    const val RECENT = 2

    private val WITH_RECENT = intArrayOf(RECENT, SEARCH, CC)
    private val WITHOUT_RECENT = intArrayOf(SEARCH, CC)

    /** Left-to-right header stops: Recently Used only while it has an app. */
    @JvmStatic
    fun headerOrder(recentFocusable: Boolean): IntArray = if (recentFocusable) WITH_RECENT else WITHOUT_RECENT

    /** Left/Right in the header: clamped, never wrapping. A stop that is no longer available restarts from the first one. */
    @JvmStatic
    fun headerStep(order: IntArray, cur: Int, dir: Int): Int {
        var p = order.indexOf(cur)
        if (p < 0) p = 0
        return order[(p + dir).coerceIn(0, order.size - 1)]
    }

    /**
     * Tab / Shift+Tab: one linear, wrapping path through the header stops and then every card. Returns
     * `[-1, headerStop]` or `[row, col]`.
     */
    @JvmStatic
    fun tab(order: IntArray, rowSizes: IntArray, inHeader: Boolean, hdr: Int, row: Int, col: Int, dir: Int): IntArray {
        val total = order.size + rowSizes.sum()
        var cur = -1
        if (inHeader) cur = order.indexOf(hdr)
        else {
            var base = order.size
            for (r in rowSizes.indices) { if (r == row && col in 0 until rowSizes[r]) { cur = base + col; break }; base += rowSizes[r] }
        }
        val n = ((maxOf(cur, 0) + dir) % total + total) % total
        if (n < order.size) return intArrayOf(-1, order[n])
        var rest = n - order.size
        for (r in rowSizes.indices) { if (rest < rowSizes[r]) return intArrayOf(r, rest); rest -= rowSizes[r] }
        return intArrayOf(0, 0)
    }
}

/** Text helpers that must not run in onDraw. */
object HomeText {
    /** At most [maxLines] lines of [text] within [width]: greedy word wrap, the last line ellipsised (CSS line-clamp). */
    @JvmStatic
    fun wrap(text: String, width: Float, maxLines: Int, measure: (String) -> Float): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            var line = words[i++]
            while (i < words.size && measure(line + " " + words[i]) <= width) line += " " + words[i++]
            lines.add(line)
            if (lines.size == maxLines) break
        }
        val cut = i < words.size
        return lines.mapIndexed { n, l -> fit(l, cut && n == lines.size - 1, width, measure) }
    }

    private fun fit(s: String, forceEllipsis: Boolean, width: Float, measure: (String) -> Float): String {
        if (!forceEllipsis && measure(s) <= width) return s
        var t = s
        while (t.isNotEmpty() && measure("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }
}
