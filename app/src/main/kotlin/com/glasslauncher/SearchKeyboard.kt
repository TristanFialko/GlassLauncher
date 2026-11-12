package com.glasslauncher

import kotlin.math.abs
import kotlin.math.roundToInt

/** keyboard.js: the remote-friendly on-screen keyboard model (layouts, focus movement, shift/mode). No drawing here. */
class SearchKeyboard {
    enum class Type { CH, SHIFT, BACK, MODE, SPACE, CLEAR, SUBMIT }
    class Key(val type: Type, val text: String, val weight: Float = 1f, val fn: Boolean = false, val icon: String? = null)

    /** What a press did; the owner turns it into text edits and sounds. */
    sealed class Press {
        data class Char(val text: String) : Press()
        object Space : Press()
        object Backspace : Press()
        object Clear : Press()
        object Submit : Press()
        object Shift : Press()
        object Mode : Press()
    }
    enum class Move { MOVED, EDGE }

    companion object {
        const val ROW_W = 1040f      // #skb flex-basis 52rem at 1920 px
        const val GAP = 12f          // .6rem
        const val KEY_H = 94f        // 4.7rem

        private fun chars(s: String, extra: List<Key> = emptyList()) = s.map { Key(Type.CH, it.toString()) } + extra
        private fun bottom(mode: String) = listOf(
            Key(Type.MODE, mode, 2.1f, true), Key(Type.SPACE, "space", 5.4f), Key(Type.CLEAR, "clear", 1.9f, true), Key(Type.SUBMIT, "search", 2.2f, true))
        private val SHIFT = Key(Type.SHIFT, "", 1f, true, "shift")
        private val BACK = Key(Type.BACK, "", 1f, true, "backspace")
        val ABC: List<List<Key>> = listOf(chars("qwertyuiop"), chars("asdfghjkl", listOf(SHIFT)), chars("zxcvbnm.-", listOf(BACK)), bottom("123"))
        val NUM: List<List<Key>> = listOf(chars("1234567890"), chars("@#&*()-_+="), chars(".,?!':;/\"", listOf(BACK)), bottom("abc"))
    }

    var numeric = false; private set
    var shifted = false; private set
    var row = 1; private set
    var index = 0; private set
    var active = false
    private var xmem = 0f

    val rows: List<List<Key>> get() = if (numeric) NUM else ABC
    val current: Key get() = rows[row][index]

    // Chrome lays flex items out on a 1/64 px grid and exposes rounded offsets; ties in nearest() depend on that rounding.
    private val geo = HashMap<Boolean, Array<FloatArray>>()   // [row] -> (left, width) pairs flattened
    private fun geometry(num: Boolean): Array<FloatArray> = geo.getOrPut(num) {
        val rs = if (num) NUM else ABC
        Array(rs.size) { r ->
            val ks = rs[r]
            val free = ROW_W - GAP * (ks.size - 1)
            val sum = ks.sumOf { it.weight.toDouble() }.toFloat()
            val a = FloatArray(ks.size * 2)
            var x = 0f
            for (i in ks.indices) { val w = free * ks[i].weight / sum; a[i * 2] = x; a[i * 2 + 1] = w; x += w + GAP }
            a
        }
    }
    fun keyLeft(r: Int, i: Int) = geometry(numeric)[r][i * 2]
    fun keyWidth(r: Int, i: Int) = geometry(numeric)[r][i * 2 + 1]
    private fun center(r: Int, i: Int): Float {
        val g = geometry(numeric)[r]
        val l = g[i * 2]; val w = g[i * 2 + 1]
        val ol = l.roundToInt(); val ow = (l + w).roundToInt() - ol
        return ol + ow / 2f
    }
    private fun remember() { xmem = center(row, index) }

    init { remember() }

    fun label(k: Key): String = if (k.type == Type.CH && shifted) k.text.uppercase() else k.text

    private fun nearest(r: Int, x: Float): Int {
        var best = 0; var bd = 1e9f
        for (i in rows[r].indices) { val d = abs(center(r, i) - x); if (d < bd) { bd = d; best = i } }
        return best
    }

    fun move(dir: Int): Move {
        when (dir) {
            37 -> { if (index == 0) return Move.EDGE; index--; remember() }
            39 -> { if (index == rows[row].size - 1) return Move.EDGE; index++; remember() }
            38 -> { if (row == 0) return Move.EDGE; row--; index = nearest(row, xmem) }
            40 -> { if (row == rows.size - 1) return Move.EDGE; row++; index = nearest(row, xmem) }
        }
        return Move.MOVED
    }

    /** Tab order: every key left to right, top to bottom. */
    fun step(dir: Int): Move {
        var r = row; var i = index + dir
        if (i < 0) { if (r == 0) return Move.EDGE; r--; i = rows[r].size - 1 }
        else if (i >= rows[r].size) { if (r == rows.size - 1) return Move.EDGE; r++; i = 0 }
        row = r; index = i; remember(); return Move.MOVED
    }

    fun jump(last: Boolean) {
        if (last) { row = rows.size - 1; index = rows[row].size - 1 } else { row = 0; index = 0 }
        remember()
    }

    fun press(): Press {
        val k = current
        return when (k.type) {
            Type.CH -> { val t = label(k); if (shifted) shifted = false; Press.Char(t) }
            Type.SPACE -> Press.Space
            Type.BACK -> Press.Backspace
            Type.CLEAR -> Press.Clear
            Type.SUBMIT -> Press.Submit
            Type.SHIFT -> { shifted = !shifted; Press.Shift }
            Type.MODE -> { numeric = !numeric; shifted = false; remember(); Press.Mode }   // the focused position is kept
        }
    }

    fun reset() { numeric = false; shifted = false; row = 1; index = 0; remember() }
}
