package com.abfilepro.app.assistant

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P27: repeat rules. */
class RecurrenceTest {
    private val zone = ZoneId.of("Africa/Cairo")
    private fun at(y: Int, m: Int, d: Int, h: Int = 9) =
        ZonedDateTime.of(LocalDate.of(y, m, d), LocalTime.of(h, 0), zone).toInstant().toEpochMilli()

    @Test fun dailyAndWeekly() {
        assertEquals(at(2026, 10, 26), Recurrence.DAILY.next(at(2026, 10, 25), zone))
        assertEquals(at(2026, 11, 1), Recurrence.WEEKLY.next(at(2026, 10, 25), zone))
    }

    @Test fun monthlyClampsToTheEndOfAShortMonth() {
        assertEquals(at(2027, 2, 28), Recurrence.MONTHLY.next(at(2027, 1, 31), zone))
    }

    @Test fun noneNeverMoves() {
        assertEquals(at(2026, 10, 25), Recurrence.NONE.next(at(2026, 10, 25), zone))
        assertEquals(at(2026, 10, 25), Recurrence.NONE.nextAfter(at(2026, 10, 25), at(2026, 12, 1), zone))
    }

    @Test fun nextAfterSkipsEveryMissedOccurrence() {
        val next = Recurrence.DAILY.nextAfter(at(2026, 10, 1), at(2026, 10, 25, 10), zone)
        assertEquals(at(2026, 10, 26), next)
    }

    @Test fun nextAfterIsBoundedForAbsurdDates() {
        val next = Recurrence.DAILY.nextAfter(0L, at(2026, 10, 25), zone)
        assertTrue(next > 0L)
    }

    @Test fun keysRoundTripAndUnknownMeansNone() {
        Recurrence.entries.forEach { assertEquals(it, Recurrence.fromKey(it.key)) }
        assertEquals(Recurrence.NONE, Recurrence.fromKey("zzz"))
        assertEquals(Recurrence.NONE, Recurrence.fromKey(null))
    }
}
