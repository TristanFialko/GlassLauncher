package com.glasslauncher

/** What the home activity provides to Search. All calls arrive on the UI thread. */
interface SearchHost {
    /** G.launch: launch the package; on success record Recently Used; on failure toast "<name> could not be opened." / "<name> is not installed on this box." */
    fun launchApp(id: String, name: String)
    /** G.openSettings(kind): open that Android settings page (SettingsLauncher.open falls back to the main Settings). */
    fun openSettings(kind: String)
    /** G.theme.toggle(); returns true if the theme is now dark (Search toasts "Dark appearance"/"Light appearance"). */
    fun toggleTheme(): Boolean
    /** Open the Control Centre from the rows zone, focusing the volume row if [focusVolume]. Search has already closed. */
    fun openControlCentre(focusVolume: Boolean)
    fun toast(text: String)
    fun playNav()
    fun playClick()
    fun playKey(kind: KeyKind)
    /** Search finished closing. [restoreFocus]: put home focus back where it was when Search opened (false after Control Centre opens). */
    fun onSearchClosed(restoreFocus: Boolean)
}

/** Remote/keyboard keys Search understands. */
enum class SKey { UP, DOWN, LEFT, RIGHT, OK, BACK, BACKSPACE }

/**
 * search.js + the Search part of keyboard.js as a pure state machine: query, results, selection, focus, keyboard.
 * Drawing lives in NativeSearchView. Results arrive asynchronously through [onResults]; where search.js read freshly
 * rendered results (flushRender) this flushes the engine and re-checks when they arrive.
 */
class SearchController(
    private val host: SearchHost,
    private val engine: QuerySource,
    private val clock: () -> Long,
    private val onChange: (Int) -> Unit,
) {
    companion object {
        const val QUERY = 1; const val RESULTS = 2; const val FOCUS = 4; const val KEYS = 8; const val PRESS = 16; const val OPEN = 32
        const val MAX_QUERY = 60
        const val HW_ENTER_WINDOW_MS = 2500L
        val SUGGESTIONS = listOf("Settings", "Wi-Fi", "Bluetooth", "Volume", "Play Store", "Appearance")
        private val SUGGEST_RESULTS = SUGGESTIONS.map { SearchResult(ResultKind.SUGGEST, it, "", "", "search", null, SearchAction.UseSuggestion(it)) }
    }

    val kb = SearchKeyboard()
    var isOpen = false; private set
    var query = ""; private set
    var results: List<SearchResult> = emptyList(); private set
    var sel = 0; private set
    var focusInList = false; private set
    private var lastHw = 0L
    private var awaiting = false
    private var submitPending = false

    fun open() {
        isOpen = true
        kb.reset()
        query = ""; sel = 0; lastHw = 0; submitPending = false
        results = SUGGEST_RESULTS
        engine.beginSession()
        awaiting = true
        engine.request("")
        focusInList = false; kb.active = true
        onChange(OPEN or QUERY or RESULTS or FOCUS or KEYS)
    }

    fun close(restoreFocus: Boolean = true) {
        if (!isOpen) return
        isOpen = false; submitPending = false; awaiting = false
        engine.endSession()
        onChange(OPEN)
        host.onSearchClosed(restoreFocus)
    }

    fun onResults(q: String, list: List<SearchResult>) {
        if (!isOpen || q != query) return
        awaiting = false
        results = if (query.isEmpty()) SUGGEST_RESULTS else list
        sel = minOf(sel, maxOf(0, results.size - 1))
        onChange(RESULTS)
        if (submitPending) {
            submitPending = false
            if (query.isNotEmpty() && list.isNotEmpty()) { sel = 0; setFocus(true); host.playNav() }
            else host.toast("Nothing matches that")
        } else if (focusInList && results.isEmpty()) setFocus(false)
    }

    private fun setQuery(q: String) {
        query = q; sel = 0; submitPending = false; awaiting = true
        engine.request(q)
        onChange(QUERY)
    }
    private fun addChar(c: String) { if (query.length < MAX_QUERY) setQuery(query + c) }
    private fun backspace() { if (query.isNotEmpty()) setQuery(query.substring(0, query.length - 1)) }

    private fun setFocus(list: Boolean) {
        var l = list
        if (l) { if (awaiting) engine.flush(); if (results.isEmpty()) l = false }
        focusInList = l; kb.active = !l
        onChange(FOCUS)
    }

    private fun submit() {
        if (query.isEmpty()) { host.toast("Type to search"); return }
        if (awaiting) { submitPending = true; engine.flush(); return }
        if (results.isNotEmpty()) { sel = 0; setFocus(true); host.playNav() } else host.toast("Nothing matches that")
    }

    private fun run(i: Int) {
        val e = results.getOrNull(i) ?: return
        host.playClick()
        when (val a = e.action) {
            is SearchAction.LaunchApp -> host.launchApp(a.id, a.name)
            is SearchAction.OpenSettings -> host.openSettings(a.kind)
            is SearchAction.OpenControlCentre -> { close(false); host.openControlCentre(a.focusVolume) }
            SearchAction.ToggleTheme -> host.toast(if (host.toggleTheme()) "Dark appearance" else "Light appearance")
            is SearchAction.UseSuggestion -> { setQuery(a.text); setFocus(false) }
        }
    }

    private fun pressKey() {
        val p = kb.press()
        onChange(PRESS or KEYS)
        when (p) {
            is SearchKeyboard.Press.Char -> { host.playKey(KeyKind.ALPHA); addChar(p.text) }
            SearchKeyboard.Press.Space -> { host.playKey(KeyKind.SPACE); addChar(" ") }
            SearchKeyboard.Press.Backspace -> { host.playKey(KeyKind.ALT); backspace() }
            SearchKeyboard.Press.Clear -> { host.playKey(KeyKind.ALT); setQuery("") }
            SearchKeyboard.Press.Submit -> { host.playKey(KeyKind.ENTER); submit() }
            SearchKeyboard.Press.Shift, SearchKeyboard.Press.Mode -> host.playKey(KeyKind.ALT)
        }
    }

    /** Hardware keyboard / remote digit: a single printable character typed straight into the field. */
    fun hardwareChar(text: String) {
        lastHw = clock()
        host.playKey(if (text == " ") KeyKind.SPACE else KeyKind.ALPHA)
        addChar(text)
        if (focusInList) setFocus(false)
    }

    /** Tab / Shift+Tab: keys first, then results, then round again. */
    fun tab(dir: Int) {
        host.playNav()
        if (!focusInList) {
            if (kb.step(dir) == SearchKeyboard.Move.EDGE) {
                if (results.isNotEmpty()) { if (awaiting) engine.flush(); sel = if (dir > 0) 0 else results.size - 1; setFocus(true) }
                else { kb.jump(dir < 0); onChange(KEYS) }
            } else onChange(KEYS)
            return
        }
        val n = sel + dir
        if (n >= 0 && n < results.size) { sel = n; onChange(FOCUS); return }
        setFocus(false); kb.jump(dir < 0); onChange(KEYS)
    }

    /** @return true if the key was consumed. [repeat]: auto-repeat of a held key. */
    fun key(k: SKey, repeat: Boolean = false): Boolean {
        if (!isOpen) return false
        when (k) {
            SKey.BACK -> { close(true); return true }
            SKey.BACKSPACE -> { lastHw = clock(); host.playKey(KeyKind.ALT); backspace(); return true }
            SKey.OK -> if (lastHw != 0L && clock() - lastHw < HW_ENTER_WINDOW_MS) {   // Enter right after hardware typing = go to results
                lastHw = 0; host.playKey(KeyKind.ENTER); submit(); return true
            }
            else -> {}
        }
        if (!focusInList) {
            when (k) {
                SKey.UP, SKey.DOWN, SKey.LEFT, SKey.RIGHT -> {
                    val dir = when (k) { SKey.LEFT -> 37; SKey.UP -> 38; SKey.RIGHT -> 39; else -> 40 }
                    if (kb.move(dir) == SearchKeyboard.Move.MOVED) { host.playNav(); onChange(KEYS) }
                    else if (k == SKey.LEFT && results.isNotEmpty()) { setFocus(true); host.playNav() }
                }
                SKey.OK -> pressKey()      // held OK repeats here (Backspace-style keys auto-repeat)
                else -> {}
            }
            return true
        }
        when (k) {
            SKey.UP -> if (sel > 0) { sel--; onChange(FOCUS); host.playNav() }
            SKey.DOWN -> if (sel < results.size - 1) { sel++; onChange(FOCUS); host.playNav() }
            SKey.RIGHT -> { setFocus(false); host.playNav() }
            SKey.OK -> if (!repeat) run(sel)   // a held OK must not activate a result repeatedly
            else -> {}
        }
        return true
    }
}
