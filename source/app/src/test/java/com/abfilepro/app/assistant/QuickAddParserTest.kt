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

    // P30: explicit dates are read, never guessed.
    private fun on(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(LocalDate.of(y, m, d), LocalTime.of(h, min), zone).toInstant().toEpochMilli()

    @Test fun monthNameDateWithAnHour() {
        val r = parse("ذكرني يوم 15 نوفمبر الساعة 3 العصر أدفع الإيجار")
        assertEquals(on(2026, 11, 15, 15), r.dueAt)
        assertEquals("أدفع الإيجار", r.title)
    }

    @Test fun monthNameDateWithoutAnHourUsesNineAm() {
        val r = parse("موعد الدكتور 3 ديسمبر")
        assertEquals(on(2026, 12, 3, 9), r.dueAt)
        assertEquals("موعد الدكتور", r.title)
    }

    @Test fun aPastMonthDayMeansNextYear() {
        val r = parse("عيد ميلاد ماما 10 مارس")
        assertEquals(on(2027, 3, 10, 9), r.dueAt)
    }

    @Test fun anExplicitYearIsKept() {
        val r = parse("تجديد الرخصة 5 يناير 2028")
        assertEquals(on(2028, 1, 5, 9), r.dueAt)
        assertEquals("تجديد الرخصة", r.title)
    }

    @Test fun slashDates() {
        assertEquals(on(2026, 11, 20, 9), parse("سداد القسط 20/11").dueAt)
        assertEquals(on(2027, 2, 1, 9), parse("اجتماع 1/2/2027").dueAt)
    }

    @Test fun dayOfMonthAloneMeansTheNextTimeItComes() {
        assertEquals(on(2026, 10, 28, 9), parse("ادفع الكهرباء يوم 28").dueAt)
        assertEquals(on(2026, 11, 5, 9), parse("ادفع الايجار يوم 5").dueAt)
    }

    @Test fun relativeAndRepeatPhrasesAreNotTakenAsDates() {
        val after = parse("بعد 3 ايام اتصل بالمصلح")
        assertEquals(on(2026, 10, 28, 9), after.dueAt)
        assertEquals("اتصل بالمصلح", after.title)
        val daily = parse("كل يوم 2 اشرب مية")
        assertEquals(Recurrence.DAILY, daily.recurrence)
        assertEquals(on(2026, 10, 26, 9), daily.dueAt)
    }

    @Test fun anImpossibleDateIsLeftInTheTitle() {
        val r = parse("اشتري 31 نوفمبر")
        assertNull(r.dueAt)
    }
}
