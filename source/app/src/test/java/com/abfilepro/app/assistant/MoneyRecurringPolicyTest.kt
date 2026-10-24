package com.abfilepro.app.assistant

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyRecurringPolicyTest {
    private fun rule(day: Int, last: String) = MoneyRecurringRule("r", "home", "مشتريات المنزل", 100.0, "", day, last)

    @Test fun nothingIsDueBeforeTheDayOfTheMonth() {
        assertTrue(MoneyRecurringPolicy.monthsDue(rule(20, "2026-09"), LocalDate.of(2026, 10, 19)).isEmpty())
    }

    @Test fun dueOnAndAfterTheDay() {
        assertEquals(listOf(YearMonth.of(2026, 10)), MoneyRecurringPolicy.monthsDue(rule(20, "2026-09"), LocalDate.of(2026, 10, 20)))
    }

    @Test fun alreadyRecordedMonthIsNotRepeated() {
        assertTrue(MoneyRecurringPolicy.monthsDue(rule(5, "2026-10"), LocalDate.of(2026, 10, 25)).isEmpty())
    }

    @Test fun missedMonthsAreFilledButBounded() {
        val due = MoneyRecurringPolicy.monthsDue(rule(1, "2025-01"), LocalDate.of(2026, 10, 25))
        assertEquals(listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10)), due)
    }

    @Test fun shortMonthUsesItsLastDay() {
        assertEquals(LocalDate.of(2027, 2, 28), MoneyRecurringPolicy.dateFor(rule(31, "2027-01"), YearMonth.of(2027, 2)))
        assertTrue(MoneyRecurringPolicy.monthsDue(rule(31, "2027-01"), LocalDate.of(2027, 2, 27)).isEmpty())
        assertEquals(1, MoneyRecurringPolicy.monthsDue(rule(31, "2027-01"), LocalDate.of(2027, 2, 28)).size)
    }

    @Test fun badLastMonthGivesNothing() {
        assertTrue(MoneyRecurringPolicy.monthsDue(rule(1, "zzz"), LocalDate.of(2026, 10, 25)).isEmpty())
    }
}
