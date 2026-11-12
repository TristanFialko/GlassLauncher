package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchKeyboardTest {
    private fun kb() = SearchKeyboard()

    @Test fun layoutsMatchKeyboardJs() {
        val k = kb()
        assertEquals("qwertyuiop", k.rows[0].joinToString("") { it.text })
        assertEquals("asdfghjkl", k.rows[1].take(9).joinToString("") { it.text })
        assertEquals(SearchKeyboard.Type.SHIFT, k.rows[1][9].type)
        assertEquals("zxcvbnm.-", k.rows[2].take(9).joinToString("") { it.text })
        assertEquals(SearchKeyboard.Type.BACK, k.rows[2][9].type)
        assertEquals(listOf("123", "space", "clear", "search"), k.rows[3].map { it.text })
        assertEquals(listOf(2.1f, 5.4f, 1.9f, 2.2f), k.rows[3].map { it.weight })
        assertEquals(1, k.row); assertEquals(0, k.index)
    }

    @Test fun numericLayoutAndModeKeepsPosition() {
        val k = kb()
        k.jump(true); k.move(37); k.move(37); k.move(37)       // "123"
        assertEquals(SearchKeyboard.Type.MODE, k.current.type)
        assertEquals(SearchKeyboard.Press.Mode, k.press())
        assertTrue(k.numeric)
        assertEquals("abc", k.current.text)
        assertEquals("1234567890", k.rows[0].joinToString("") { it.text })
        assertEquals("@#&*()-_+=", k.rows[1].joinToString("") { it.text })
        assertEquals(".,?!':;/\"", k.rows[2].take(9).joinToString("") { it.text })
        assertEquals(3, k.row)
    }

    @Test fun shiftAppliesToOneCharacterOnly() {
        val k = kb()
        k.jump(false); k.move(40); for (i in 0 until 9) k.move(39)
        assertEquals(SearchKeyboard.Press.Shift, k.press()); assertTrue(k.shifted)
        k.jump(false)
        assertEquals(SearchKeyboard.Press.Char("Q"), k.press())
        assertEquals(SearchKeyboard.Press.Char("q"), k.press())
    }

    @Test fun verticalMovesCarryTheColumnAndRowsClampAtTheEdges() {
        val k = kb()
        k.jump(false)
        for (i in 0 until 5) k.move(39)                         // y
        assertEquals("y", k.current.text)
        assertEquals(SearchKeyboard.Move.MOVED, k.move(40)); assertEquals("h", k.current.text)
        assertEquals(SearchKeyboard.Move.MOVED, k.move(40)); assertEquals("n", k.current.text)
        assertEquals(SearchKeyboard.Move.MOVED, k.move(40)); assertEquals("space", k.current.text)
        assertEquals(SearchKeyboard.Move.EDGE, k.move(40))
        assertEquals(SearchKeyboard.Move.MOVED, k.move(38)); assertEquals("n", k.current.text)    // column memory is the last horizontal position
        k.jump(false)
        assertEquals(SearchKeyboard.Move.EDGE, k.move(38)); assertEquals(SearchKeyboard.Move.EDGE, k.move(37))
    }

    @Test fun horizontalEdgesAndStepOrder() {
        val k = kb()
        k.jump(true)
        assertEquals(SearchKeyboard.Move.EDGE, k.move(39)); assertEquals(SearchKeyboard.Move.EDGE, k.step(1))
        assertEquals(SearchKeyboard.Move.MOVED, k.step(-1)); assertEquals("clear", k.current.text)
        k.jump(false)
        for (i in 0 until 10) k.step(1)
        assertEquals("a", k.current.text); assertEquals(1, k.row)
    }

    @Test fun pressResultsForEveryKeyType() {
        val k = kb()
        k.jump(true)
        assertEquals(SearchKeyboard.Press.Submit, k.press())
        k.move(37); assertEquals(SearchKeyboard.Press.Clear, k.press())
        k.move(37); assertEquals(SearchKeyboard.Press.Space, k.press())
        k.jump(false); k.move(40); k.move(40); for (i in 0 until 9) k.move(39)
        assertEquals(SearchKeyboard.Press.Backspace, k.press())
    }

    @Test fun resetRestoresInitialState() {
        val k = kb()
        k.jump(true); k.move(37); k.move(37); k.move(37); k.press(); k.reset()
        assertEquals(false, k.numeric); assertEquals(1, k.row); assertEquals(0, k.index)
    }

    @Test fun keyGeometryFillsTheRowExactly() {
        val k = kb()
        for (r in k.rows.indices) {
            val last = k.rows[r].size - 1
            assertEquals(SearchKeyboard.ROW_W, k.keyLeft(r, last) + k.keyWidth(r, last), 0.01f)
        }
    }
}
