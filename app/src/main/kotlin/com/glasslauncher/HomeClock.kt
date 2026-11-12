package com.glasslauncher

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Clock and date strings, formatted only when the clock ticks (never in the draw pass). */
object HomeClock {
    /** main.js tick(): 12-hour h:mm without AM/PM. */
    fun clock(cal: Calendar): String {
        val h12 = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        return h12.toString() + ":" + String.format(Locale.ROOT, "%02d", cal.get(Calendar.MINUTE))
    }

    /**
     * main.js toLocaleDateString('en-GB', {weekday:'long', day:'numeric', month:'long'}). Current CLDR en-GB (WebView 157)
     * prints "Thursday 12 November" with no comma; older WebViews printed "Thursday, 12 November". We keep the modern form.
     */
    fun date(cal: Calendar): String = format("EEEE d MMMM", cal)

    /** Control Centre clock, 24-hour H:mm (controlcentre.js). */
    fun ccTime(cal: Calendar): String = format("H:mm", cal)

    private fun format(pattern: String, cal: Calendar): String =
        SimpleDateFormat(pattern, Locale.UK).apply { timeZone = cal.timeZone }.format(cal.time)
}
