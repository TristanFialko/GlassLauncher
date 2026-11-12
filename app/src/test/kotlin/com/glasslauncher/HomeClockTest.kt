package com.glasslauncher

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HomeClockTest {
    private fun cal(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, mo, d, h, mi) }

    @Test fun midnightIsTwelve() = assertEquals("12:05", HomeClock.clock(cal(2026, Calendar.NOVEMBER, 12, 0, 5)))
    @Test fun noonIsTwelve() = assertEquals("12:00", HomeClock.clock(cal(2026, Calendar.NOVEMBER, 12, 12, 0)))
    @Test fun afternoonHasNoAmPm() = assertEquals("1:07", HomeClock.clock(cal(2026, Calendar.NOVEMBER, 12, 13, 7)))
    @Test fun dateHasNoComma() = assertEquals("Thursday 12 November", HomeClock.date(cal(2026, Calendar.NOVEMBER, 12, 9, 0)))
    @Test fun ccTimeIs24Hour() = assertEquals("13:07", HomeClock.ccTime(cal(2026, Calendar.NOVEMBER, 12, 13, 7)))
}
