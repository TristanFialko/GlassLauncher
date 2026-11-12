package com.glasslauncher

import java.util.Locale

/** Exact Kotlin port of registry.js norm/compact/subseq/fieldScore/score/queryJs and the entries() rules (fallback only). */
object SearchRanking {
    const val DEFAULT_MAX = 24
    private val HIDDEN_PREFIXES = arrayOf("org.tizen", "com.samsung", "DU.TV.PRODA")
    const val SELF = "com.glasslauncher"

    private fun isJsSpace(c: Char) = c == ' ' || c == '\u1680' || c in '\u2000'..'\u200a' ||
        c == '\u2028' || c == '\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000' || c == '\ufeff' || c == '\u00a0'

    /** norm(): lower-case, keep [a-z0-9 À-\uffff], collapse JS whitespace to one space, trim. */
    fun norm(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val lower = s.lowercase(Locale.ROOT)
        val out = StringBuilder(lower.length)
        var pendingSpace = false
        for (c in lower) {
            val keep = c in 'a'..'z' || c in '0'..'9' || c == ' ' || c.code >= 0xC0
            if (!keep) continue
            if (isJsSpace(c)) { pendingSpace = true; continue }
            if (pendingSpace && out.isNotEmpty()) out.append(' ')
            pendingSpace = false
            out.append(c)
        }
        return out.toString()
    }

    fun compact(s: String): String = s.replace(" ", "")

    private fun subseq(q: String, t: String): Boolean {
        var i = 0
        var j = 0
        while (j < t.length && i < q.length) { if (t[j] == q[i]) i++; j++ }
        return i == q.length
    }

    private fun fieldScore(text: String, q: String, qc: String, fuzzy: Boolean): Double {
        val t = norm(text)
        if (t.isEmpty()) return 0.0
        val tc = compact(t)
        if (t == q || tc == qc) return 100.0
        if (t.startsWith(q) || tc.startsWith(qc)) return 85.0
        for (w in t.split(' ')) if (w.startsWith(q)) return 70.0
        if (tc.contains(qc)) return 50.0
        if (fuzzy && qc.length >= 4 && subseq(qc, tc)) return 20.0
        return 0.0
    }

    class Candidate(val isApp: Boolean, val title: String, val keywords: List<String>)

    private fun score(e: Candidate, q: String, qc: String): Double {
        var best = fieldScore(e.title, q, qc, true)
        for (k in e.keywords) { val s = fieldScore(k, q, qc, false) * 0.92; if (s > best) best = s }
        return if (best > 0) best - minOf(5.0, norm(e.title).length * 0.02) else 0.0
    }

    /** Indices into [entries], best first (registry.queryJs). */
    fun query(entries: List<Candidate>, text: String, max: Int): IntArray {
        val q = norm(text)
        if (q.isEmpty()) return IntArray(0)
        val qc = compact(q)
        val hits = ArrayList<Int>()
        val scores = DoubleArray(entries.size)
        val titles = arrayOfNulls<String>(entries.size)
        for (i in entries.indices) {
            val s = score(entries[i], q, qc)
            if (s > 0) { scores[i] = s; hits.add(i) }
        }
        val sorted = hits.sortedWith { a, b ->
            if (scores[a] != scores[b]) return@sortedWith scores[b].compareTo(scores[a])
            val ea = entries[a]; val eb = entries[b]
            if (ea.isApp != eb.isApp) return@sortedWith if (ea.isApp) -1 else 1
            val ta = titles[a] ?: norm(ea.title).also { titles[a] = it }
            val tb = titles[b] ?: norm(eb.title).also { titles[b] = it }
            ta.compareTo(tb)
        }
        val limit = if (max <= 0) DEFAULT_MAX else max
        return sorted.take(limit).toIntArray()
    }

    /** ASCII-case-insensitive prefix test, like HIDDEN = /^(org\.tizen|com\.samsung|DU\.TV\.PRODA)/i. */
    fun isHidden(id: String): Boolean = HIDDEN_PREFIXES.any { p -> id.length >= p.length && p.indices.all { asciiLower(id[it]) == asciiLower(p[it]) } }

    private fun asciiLower(c: Char) = if (c in 'A'..'Z') c + 32 else c

    /** Installed-app visibility rule (appProvider.entries / snapshot.rs). */
    fun appVisible(a: InstalledApp): Boolean = a.show && a.id != SELF && !isHidden(a.id)
}
