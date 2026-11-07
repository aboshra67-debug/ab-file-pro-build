package com.abfilepro.app.core

/**
 * P33: the free-tier "points" rules (Play channel only; Pro and the direct APK are unlimited).
 * Pure functions so the rules are covered by JVM tests.
 */
object PointsPolicy {
    const val START_BALANCE = 20
    const val DAILY_BONUS = 2
    const val AD_REWARD = 5
    const val MAX_ADS_PER_DAY = 3
    /** Earned points stop piling up beyond this (the starting gift never counts against it). */
    const val MAX_BALANCE = 100
    const val COST_PER_PAGE = 1
    /** A long book would otherwise be out of reach for free users. */
    const val MAX_SCAN_COST = 20
    const val COST_TRANSLATE = 2

    /** Points one save costs. Single documents, cards and receipts stay free. */
    fun saveCost(mode: ScanCaptureMode, pages: Int): Int = when (mode) {
        ScanCaptureMode.BATCH, ScanCaptureMode.BOOK ->
            (pages.coerceAtLeast(0) * COST_PER_PAGE).coerceAtMost(MAX_SCAN_COST)
        ScanCaptureMode.TRANSLATION -> COST_TRANSLATE
        else -> 0
    }
}

/** The whole stored state; [lastBonusDay] and [adsDay] are epoch days (-1 = never). */
data class PointsLedger(
    val balance: Int = PointsPolicy.START_BALANCE,
    val lastBonusDay: Long = -1,
    val adsDay: Long = -1,
    val adsToday: Int = 0,
) {
    fun adsLeftToday(today: Long): Int =
        if (adsDay == today) (PointsPolicy.MAX_ADS_PER_DAY - adsToday).coerceAtLeast(0) else PointsPolicy.MAX_ADS_PER_DAY

    fun canSpend(cost: Int): Boolean = cost <= balance

    /** Spends [cost]; returns the same ledger when there is not enough (never goes negative). */
    fun spend(cost: Int): PointsLedger =
        if (cost <= 0 || cost > balance) this else copy(balance = balance - cost)

    fun dailyBonusAvailable(today: Long): Boolean = lastBonusDay != today

    fun claimDailyBonus(today: Long): PointsLedger =
        if (!dailyBonusAvailable(today)) this
        else copy(balance = capped(balance + PointsPolicy.DAILY_BONUS), lastBonusDay = today)

    /** One finished rewarded video. Past the daily limit nothing is added. */
    fun earnFromAd(today: Long): PointsLedger {
        if (adsLeftToday(today) <= 0) return this
        val used = if (adsDay == today) adsToday else 0
        return copy(balance = capped(balance + PointsPolicy.AD_REWARD), adsDay = today, adsToday = used + 1)
    }

    /** A gift never takes points away: a balance already above the cap just stops growing. */
    private fun capped(value: Int): Int = if (balance >= PointsPolicy.MAX_BALANCE) balance else value.coerceAtMost(PointsPolicy.MAX_BALANCE)
}
