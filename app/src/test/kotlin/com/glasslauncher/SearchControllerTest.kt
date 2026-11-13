package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchControllerTest {
    private class Host : SearchHost {
        val log = ArrayList<String>()
        var dark = true
        override fun launchApp(id: String, name: String) { log.add("launch:$id:$name") }
        override fun openSettings(kind: String) { log.add("settings:$kind") }
        override fun toggleTheme(): Boolean { dark = !dark; log.add("theme"); return dark }
        override fun openControlCentre(focusVolume: Boolean) { log.add("cc:$focusVolume") }
        override fun toast(text: String) { log.add("toast:$text") }
        override fun playNav() { log.add("nav") }
        override fun playClick() { log.add("click") }
        override fun playKey(kind: KeyKind) { log.add("key:$kind") }
        override fun onSearchClosed(restoreFocus: Boolean) { log.add("closed:$restoreFocus") }
    }

    private class Engine : QuerySource {
        val log = ArrayList<String>()
        override var hasPending = false
        override fun beginSession() { log.add("begin") }
        override fun endSession() { log.add("end"); hasPending = false }
        override fun request(query: String) { log.add("req:$query"); hasPending = true }
        override fun flush() { if (hasPending) log.add("flush"); hasPending = false }
    }

    private val host = Host()
    private val engine = Engine()
    private var t = 10_000L
    private val c = SearchController(host, engine, { t }, {})

    private fun result(title: String, action: SearchAction = SearchAction.LaunchApp(title, title), kind: ResultKind = ResultKind.APP) =
        SearchResult(kind, title, "sub", "App", "settings", title, action)

    private fun typed(q: String, vararg r: SearchResult) { for (ch in q) c.hardwareChar(ch.toString()); t += 1; c.onResults(q, r.toList()); host.log.clear() }

    @Test fun openShowsSuggestionsWithKeyboardFocusOnTheFirstKeyOfTheSecondRow() {
        c.open()
        assertEquals(listOf("begin", "req:"), engine.log)
        assertEquals(SearchController.SUGGESTIONS, c.results.map { it.title })
        assertFalse(c.focusInList)
        assertEquals(1, c.kb.row); assertEquals(0, c.kb.index); assertTrue(c.kb.active)
        assertEquals("a", c.kb.current.text)
    }

    @Test fun keyboardTypesWithShiftSpaceBackspaceClear() {
        c.open(); host.log.clear()
        c.key(SKey.OK)                                  // 'a'
        assertEquals("a", c.query)
        c.key(SKey.RIGHT); c.key(SKey.OK)               // 's'
        assertEquals("as", c.query)
        repeat(8) { c.key(SKey.RIGHT) }; assertEquals(SearchKeyboard.Type.SHIFT, c.kb.current.type)
        c.key(SKey.OK); assertTrue(c.kb.shifted)
        c.key(SKey.UP); c.key(SKey.OK)                  // shifted 'o' or nearest key above shift
        assertTrue(c.query.last().isUpperCase()); assertFalse(c.kb.shifted)
        assertEquals(listOf("key:ALPHA", "nav", "key:ALPHA", "nav"), host.log.take(4))
        c.kb.jump(false); c.kb.move(40); c.kb.move(40); c.kb.move(40)   // bottom row, "123"
        c.key(SKey.RIGHT)                               // space
        c.key(SKey.OK)
        assertTrue(c.query.endsWith(" "))
        c.key(SKey.RIGHT); c.key(SKey.OK)               // clear
        assertEquals("", c.query)
    }

    @Test fun queryIsCappedAtSixtyCharacters() {
        c.open()
        repeat(70) { c.hardwareChar("x") }
        assertEquals(60, c.query.length)
    }

    @Test fun hardwareBackspaceAndEnterSubmitWithinWindow() {
        c.open(); c.hardwareChar("a"); c.hardwareChar("b")
        c.key(SKey.BACKSPACE); assertEquals("a", c.query)
        t += 100
        c.onResults("a", listOf(result("Alpha"), result("Beta")))
        host.log.clear()
        c.key(SKey.OK)                                   // Enter right after typing = go to results
        assertTrue(c.focusInList); assertEquals(0, c.sel)
        assertEquals(listOf("key:ENTER", "nav"), host.log)
        c.key(SKey.DOWN); assertEquals(1, c.sel)
    }

    @Test fun enterAfterWindowActsOnTheFocusedKey() {
        c.open(); c.hardwareChar("a")
        t += 3000
        c.key(SKey.OK)
        assertEquals("aa", c.query)
    }

    @Test fun submitWaitsForPendingResults() {
        c.open(); host.log.clear(); engine.log.clear()
        c.hardwareChar("n"); host.log.clear()
        c.key(SKey.OK)                                   // hardware Enter: flush and wait
        assertEquals("flush", engine.log.last())
        assertFalse(c.focusInList)
        c.onResults("n", listOf(result("Netflix")))
        assertTrue(c.focusInList); assertTrue(host.log.contains("nav"))
    }

    @Test fun submitWithoutMatchesOrQueryToasts() {
        c.open(); host.log.clear()
        c.kb.jump(true); c.key(SKey.OK)                  // 'search' key with empty query
        assertEquals(listOf("key:ENTER", "toast:Type to search"), host.log)
        host.log.clear()
        c.hardwareChar("z"); t += 5000; host.log.clear(); c.onResults("z", emptyList())
        c.kb.jump(true); c.key(SKey.OK)
        assertEquals(listOf("key:ENTER", "toast:Nothing matches that"), host.log)
        assertFalse(c.focusInList)
    }

    @Test fun leftFromKeyboardEdgeEntersResultsOnlyWhenThereAreSome() {
        c.open()
        c.kb.jump(false); c.kb.move(40)                  // row 1, first key
        host.log.clear()
        c.key(SKey.LEFT)                                  // suggestions count as results (render() fills them)
        assertTrue(c.focusInList)
        c.key(SKey.RIGHT); assertFalse(c.focusInList)
        typed("q")
        c.onResults("q", emptyList())
        c.key(SKey.LEFT); assertFalse(c.focusInList)
    }

    @Test fun resultsEmptyingWhileFocusedInListReturnsToKeyboard() {
        c.open(); typed("a", result("A1"))
        c.kb.jump(true); c.tab(1)
        assertTrue(c.focusInList)
        c.onResults("a", emptyList())
        assertFalse(c.focusInList); assertTrue(c.kb.active)
    }

    @Test fun typingInResultsReturnsToKeyboard() {
        c.open(); typed("a", result("A1"), result("A2"))
        c.kb.jump(true); c.tab(1)                         // past the last key -> results
        assertTrue(c.focusInList)
        c.hardwareChar("b")                               // typing returns to the keyboard
        assertFalse(c.focusInList)
    }

    @Test fun tabCyclesKeysThenResultsThenKeys() {
        c.open(); typed("a", result("A1"), result("A2"))
        val total = SearchKeyboard.ABC.sumOf { it.size }
        c.kb.jump(false)
        repeat(total - 1) { c.tab(1) }
        assertEquals(SearchKeyboard.Type.SUBMIT, c.kb.current.type)
        c.tab(1); assertTrue(c.focusInList); assertEquals(0, c.sel)
        c.tab(1); assertEquals(1, c.sel)
        c.tab(1); assertFalse(c.focusInList); assertEquals(0, c.kb.row); assertEquals(0, c.kb.index)
        c.tab(-1)                                          // first key, backwards -> results from the end
        assertTrue(c.focusInList); assertEquals(1, c.sel)
        c.tab(-1); assertEquals(0, c.sel)
        c.tab(-1); assertFalse(c.focusInList); assertEquals(SearchKeyboard.Type.SUBMIT, c.kb.current.type)
    }

    @Test fun activatingResultsPerformsTheMappedActions() {
        c.open()
        typed("x", result("Netflix", SearchAction.LaunchApp("com.netflix.ninja", "Netflix")), result("Wi-Fi", SearchAction.OpenSettings("wifi"), ResultKind.SHORTCUT),
            result("Volume", SearchAction.OpenControlCentre(true), ResultKind.SHORTCUT), result("Appearance", SearchAction.ToggleTheme, ResultKind.SHORTCUT))
        c.tab(1); c.kb.jump(true); c.tab(1); t += 5000
        assertTrue(c.focusInList)
        host.log.clear(); c.key(SKey.OK)
        assertEquals(listOf("click", "launch:com.netflix.ninja:Netflix"), host.log); assertTrue(c.isOpen)   // Search stays open after a launch
        c.key(SKey.DOWN); host.log.clear(); c.key(SKey.OK)
        assertEquals(listOf("click", "settings:wifi"), host.log)
        c.key(SKey.DOWN); c.key(SKey.DOWN); host.log.clear(); c.key(SKey.OK)
        assertEquals(listOf("click", "toast:Light appearance"), host.log.filterNot { it == "theme" })
        c.key(SKey.UP); host.log.clear(); c.key(SKey.OK)
        assertEquals(listOf("click", "closed:false", "cc:true"), host.log)
        assertFalse(c.isOpen)
    }

    @Test fun heldOkDoesNotRepeatResultsButRepeatsKeys() {
        c.open()
        c.key(SKey.OK, repeat = true); c.key(SKey.OK, repeat = true)
        assertEquals("aa", c.query)
        typed("x", result("One"))
        c.tab(1); c.kb.jump(true); c.tab(1); t += 5000
        host.log.clear(); c.key(SKey.OK, repeat = true)
        assertTrue(host.log.isEmpty())
    }

    @Test fun suggestionFillsTheQueryAndFocusesTheKeyboard() {
        c.open()
        c.key(SKey.LEFT)                                  // first key, left edge -> suggestions
        assertTrue(c.focusInList)
        c.key(SKey.DOWN); host.log.clear(); c.key(SKey.OK)
        assertEquals("Wi-Fi", c.query); assertFalse(c.focusInList)
        assertEquals("click", host.log.first())
    }

    @Test fun backClosesAndRestoresFocusOnceAndStaleResultsAreIgnored() {
        c.open(); c.hardwareChar("a"); host.log.clear()
        c.key(SKey.BACK)
        assertEquals(listOf("closed:true"), host.log)
        assertEquals("end", engine.log.last())
        c.onResults("a", listOf(result("A")))
        assertEquals(SearchController.SUGGESTIONS.size, c.results.size)
        assertFalse(c.key(SKey.OK))
    }

    @Test fun resultsForAnOlderQueryAreIgnored() {
        c.open(); c.hardwareChar("a"); c.hardwareChar("b")
        c.onResults("a", listOf(result("A")))
        assertEquals(SearchController.SUGGESTIONS.size, c.results.size)
        c.onResults("ab", listOf(result("AB")))
        assertEquals(listOf("AB"), c.results.map { it.title })
    }

    // ---- M3: failure contract and activation while refreshing ----

    @Test fun internalFailureIsAnErrorStateNotNoMatch() {
        c.open()
        c.hardwareChar("n")
        c.onFailed("n")
        assertTrue(c.failed)
        assertTrue(c.results.isEmpty())
        c.onResults("n", listOf(result("Netflix")))
        assertFalse("the next delivered ranking clears the error", c.failed)
        assertEquals(listOf("Netflix"), c.results.map { it.title })
    }

    @Test fun submitDuringOrAfterAFailureToastsUnavailable() {
        c.open()
        c.hardwareChar("n"); host.log.clear()
        c.key(SKey.OK)                                   // hardware Enter window: submit while awaiting
        c.onFailed("n")
        assertTrue(host.log.toString(), host.log.contains("toast:" + SearchController.UNAVAILABLE_TOAST))
        assertFalse(host.log.contains("toast:Nothing matches that"))
        host.log.clear(); t += 5_000
        c.kb.jump(true); c.key(SKey.OK)
        assertTrue(host.log.toString(), host.log.contains("toast:" + SearchController.UNAVAILABLE_TOAST))
    }

    @Test fun failureMovesFocusOutOfTheList() {
        c.open()
        typed("a", result("A1"))
        c.kb.jump(true); c.key(SKey.OK)
        assertTrue(c.focusInList)
        c.onFailed("a")
        assertFalse(c.focusInList)
    }

    @Test fun staleOrClosedFailuresAreIgnored() {
        c.open()
        c.hardwareChar("a"); c.hardwareChar("b")
        c.onFailed("a")
        assertFalse(c.failed)
        c.onFailed("ab")
        assertTrue(c.failed)
        c.open()
        assertFalse("reopening clears the error", c.failed)
        c.key(SKey.BACK)
        c.onFailed("")
        assertFalse(c.isOpen)
    }

    @Test fun rowsOfThePreviousQueryAreNotActivatedWhileTheNewOneRanks() {
        c.open()
        typed("a", result("Alpha"), result("Beta"))
        c.hardwareChar("b")                              // "ab" requested; "a" rows still shown
        t += 5_000
        c.key(SKey.LEFT)                                 // keyboard is on row 1 key 0: left edge -> list
        assertTrue(c.focusInList)
        host.log.clear()
        c.key(SKey.OK)
        assertTrue(host.log.toString(), host.log.none { it.startsWith("launch:") })
        c.onResults("ab", listOf(result("Abba")))
        c.key(SKey.OK)
        assertTrue(host.log.toString(), host.log.contains("launch:Abba:Abba"))
    }
}
