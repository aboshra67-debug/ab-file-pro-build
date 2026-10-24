package com.abfilepro.app.assistant

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P27: the one-sentence quick add. "Now" is Sunday 2026-10-25 10:00 in Cairo. */
class QuickAddParserTest {
    private val zone = ZoneId.of("Africa/Cairo")
    private val now = ZonedDateTime.of(LocalDate.of(2026, 10, 25), LocalTime.of(10, 0), zone).toInstant().toEpochMilli()
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(LocalDate.of(2026, 10, day), LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()
    private fun parse(text: String) = QuickAddParser.parse(text, now, zone)

    @Test fun tomorrowWithAnAfternoonHour() {
        val r = parse("ذكرني بكرة الساعة 5 العصر أكلم أحمد")
        assertEquals(at(26, 17), r.dueAt)
        assertEquals("أكلم أحمد", r.title)
        assertEquals(Recurrence.NONE, r.recurrence)
        assertFalse(r.calendar)
    }

    @Test fun dailyRepeatAfterTheHourHasPassedStartsTomorrow() {
        val r = parse("كل يوم الساعة 8 الصبح اشرب مية")
        assertEquals(Recurrence.DAILY, r.recurrence)
        assertEquals(at(26, 8), r.dueAt)
        assertEquals("اشرب مية", r.title)
    }

    @Test fun relativeHours() {
        val r = parse("بعد ساعتين أكلم المهندس")
        assertEquals(now + 120 * 60_000L, r.dueAt)
        assertEquals("أكلم المهندس", r.title)
    }

    @Test fun weekdayAppointmentIsACalendarEntry() {
        val r = parse("موعد دكتور يوم الخميس 6 المسا")
        assertTrue(r.calendar)
        assertEquals(at(29, 18), r.dueAt)
        assertTrue(r.title.contains("دكتور"))
    }

    @Test fun everyWeekdayRepeatsWeeklyAtNineByDefault() {
        val r = parse("كل خميس اذاكر")
        assertEquals(Recurrence.WEEKLY, r.recurrence)
        assertEquals(at(29, 9), r.dueAt)
    }

    @Test fun aSentenceWithoutATimeStaysUndated() {
        val r = parse("اشتري خبز")
        assertNull(r.dueAt)
        assertEquals("اشتري خبز", r.title)
        assertEquals(Recurrence.NONE, r.recurrence)
    }

    @Test fun arabicIndicDigitsAreRead() {
        val r = parse("الساعة ٩ الصبح اجتماع")
        assertEquals(at(26, 9), r.dueAt)
    }

    @Test fun emptyInputGivesAnEmptyResult() {
        val r = parse("   ")
        assertEquals("", r.title)
        assertNull(r.dueAt)
    }
}
