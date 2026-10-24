package com.abfilepro.app.assistant

import java.time.LocalDate
import java.time.YearMonth

/** P27: a fixed monthly expense (rent, installment, subscription) that adds itself every month. */
data class MoneyRecurringRule(
    val id: String,
    val categoryKey: String,
    val categoryArabic: String,
    val amount: Double,
    val note: String,
    /** Day of the month; a shorter month uses its last day. */
    val day: Int,
    /** "yyyy-MM" of the last month already recorded. */
    val lastMonth: String,
    val work: Boolean = false,
)

object MoneyRecurringPolicy {
    /** At most this many missed months are filled in, so a phone left alone for a year does not flood the ledger. */
    const val MAX_CATCH_UP = 3

    fun dateFor(rule: MoneyRecurringRule, month: YearMonth): LocalDate =
        month.atDay(rule.day.coerceIn(1, 31).coerceAtMost(month.lengthOfMonth()))

    /** Months that should have an entry by [today] and do not yet. Oldest first. Pure; JVM-tested. */
    fun monthsDue(rule: MoneyRecurringRule, today: LocalDate): List<YearMonth> {
        val last = runCatching { YearMonth.parse(rule.lastMonth) }.getOrNull() ?: return emptyList()
        val current = YearMonth.from(today)
        val due = mutableListOf<YearMonth>()
        var month = last.plusMonths(1)
        var guard = 0
        while (!month.isAfter(current) && guard++ < 600) {
            if (month.isBefore(current) || !today.isBefore(dateFor(rule, month))) due += month
            month = month.plusMonths(1)
        }
        return due.takeLast(MAX_CATCH_UP)
    }
}
