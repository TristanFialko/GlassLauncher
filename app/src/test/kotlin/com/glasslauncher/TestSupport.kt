package com.glasslauncher

import java.io.File
import java.util.concurrent.Executor
import org.junit.Assume.assumeTrue

/** Minimal JSON reader for the parity fixture (org.json is a stub on the host JVM). */
object MiniJson {
    fun parse(s: String): Any? = P(s).value()

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> {
                    i++
                    val m = LinkedHashMap<String, Any?>()
                    ws()
                    if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); i++; m[k] = value(); ws(); if (s[i++] == '}') break }
                    m
                }
                '[' -> {
                    i++
                    val l = ArrayList<Any?>()
                    ws()
                    if (s[i] == ']') { i++; return l }
                    while (true) { l.add(value()); ws(); if (s[i++] == ']') break }
                    l
                }
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; s.substring(st, i).toDouble() }
            }
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                if (c == '"') break
                if (c == '\\') {
                    when (val e = s[i++]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                } else sb.append(c)
            }
            return sb.toString()
        }
    }
}

fun assertSameList(name: String, expected: List<String>, actual: List<String>) {
    for (i in 0 until maxOf(expected.size, actual.size)) {
        val e = expected.getOrNull(i)
        val a = actual.getOrNull(i)
        if (e != a) org.junit.Assert.fail("$name: first difference at $i of ${expected.size}/${actual.size}: expected <$e> but was <$a>")
    }
}

object TestSupport {
    fun requireNative() {
        assumeTrue("built with -Pglass.rust=false", java.lang.Boolean.getBoolean("glass.expectNative"))
    }

    val native: Boolean get() = java.lang.Boolean.getBoolean("glass.expectNative")

    fun fixtureFile(): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            val f = File(d, "tools/parity/fixtures/registry-entries.json")
            if (f.exists()) return f
            d = d.parentFile
        }
        error("registry-entries.json not found")
    }

    class FixtureEntry(val kind: String, val id: String, val title: String, val subtitle: String, val pkg: String?, val tag: String, val keywords: List<String>)
    class FixtureCase(val name: String, val entries: List<FixtureEntry>)

    @Suppress("UNCHECKED_CAST")
    fun cases(): List<FixtureCase> {
        val root = MiniJson.parse(fixtureFile().readText(Charsets.UTF_8)) as Map<String, Any?>
        return (root["cases"] as List<Map<String, Any?>>).map { c ->
            FixtureCase(c["name"] as String, (c["entries"] as List<Map<String, Any?>>).map { e ->
                FixtureEntry(e["kind"] as String, e["id"] as String, e["title"] as String, (e["subtitle"] ?: "") as String, e["pkg"] as String?,
                    (e["tag"] ?: "") as String, (e["keywords"] as List<Any?>).map { it as String })
            })
        }
    }

    /** The live facts a case was generated from: apps (with aliases), extra provider entries, available settings pages. */
    class Facts(val installed: List<InstalledApp>, val aliases: Map<String, List<String>>, val extra: List<CatalogEntry>, val settings: Set<String>)

    fun factsOf(c: FixtureCase): Facts {
        val installed = ArrayList<InstalledApp>()
        val aliases = HashMap<String, List<String>>()
        val extra = ArrayList<CatalogEntry>()
        val settings = HashSet<String>()
        for (e in c.entries) when {
            e.id.startsWith("set:") -> settings.add(e.id.removePrefix("set:"))
            e.id.startsWith("glass:") -> {}
            e.id.startsWith("app:") && e.pkg != null -> {
                installed.add(InstalledApp(e.pkg, e.title))
                if (e.keywords.isNotEmpty()) aliases[e.pkg] = e.keywords
            }
            else -> extra.add(CatalogEntry(e.id, e.kind, e.title, e.subtitle, e.keywords, e.tag, "settings", action = SearchAction.ToggleTheme))
        }
        // supersededBy counts hidden apps too: a case without the "Settings" shortcut had a Settings package installed (possibly show:false)
        if ("main" !in settings && installed.none { it.id == "com.android.tv.settings" || it.id == "com.android.settings" }) installed.add(InstalledApp("com.android.tv.settings", "Settings", show = false))
        return Facts(installed, aliases, extra, settings)
    }

    fun snapshotOf(f: Facts, extraInstalled: List<InstalledApp> = emptyList(), extraEntries: List<CatalogEntry> = emptyList()): SearchCatalog.Snapshot =
        SearchCatalog.build(f.installed + extraInstalled, SearchCatalog.mergeAliases(f.aliases), f.extra + extraEntries,
            SearchCatalog.shortcuts { k -> k in f.settings })

    /** Runs tasks only when told to. */
    class ManualExecutor : Executor {
        val q = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { q.addLast(command) }
        fun runAll() { while (q.isNotEmpty()) q.removeFirst().run() }
    }

    class ManualUi : UiScheduler {
        var now = 0L
        private class T(val at: Long, val r: Runnable)
        private val timers = ArrayList<T>()
        val posted = ArrayDeque<Runnable>()
        override fun post(r: Runnable) { posted.addLast(r) }
        override fun postDelayed(r: Runnable, delayMs: Long) { timers.add(T(now + delayMs, r)) }
        override fun cancel(r: Runnable) { timers.removeAll { it.r === r }; posted.removeAll { it === r } }
        fun advance(ms: Long) {
            now += ms
            while (true) {
                val t = timers.filter { it.at <= now }.minByOrNull { it.at } ?: break
                timers.remove(t); t.r.run()
            }
        }
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst().run() }
        val pendingTimers get() = timers.size
    }
}
