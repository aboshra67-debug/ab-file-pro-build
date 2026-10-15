package com.abfilepro.app.assistant

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrayerTimesCalculatorTest {
    @Test fun cairoTimesRemainChronological() {
        val result = PrayerTimesCalculator.calculate(
            date = LocalDate.of(2026, 9, 21),
            latitude = 30.0444,
            longitude = 31.2357,
            zoneOffsetHours = 3.0,
            method = PrayerMethod.EGYPTIAN,
            asrSchool = AsrSchool.STANDARD,
        )
        val minutes = PrayerName.entries.map { result[it]!!.toSecondOfDay() / 60 }
        assertTrue(minutes.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun perPrayerAdjustmentIsAppliedExactly() {
        val base = PrayerTimesCalculator.calculate(
            LocalDate.of(2026, 9, 21), 30.0444, 31.2357, 3.0,
            PrayerMethod.EGYPTIAN, AsrSchool.STANDARD,
        )
        val adjusted = PrayerTimesCalculator.calculate(
            LocalDate.of(2026, 9, 21), 30.0444, 31.2357, 3.0,
            PrayerMethod.EGYPTIAN, AsrSchool.STANDARD,
            adjustmentsMinutes = mapOf(PrayerName.FAJR to 2, PrayerName.ISHA to -3),
        )
        assertEquals(base[PrayerName.FAJR]!!.plusMinutes(2), adjusted[PrayerName.FAJR])
        assertEquals(base[PrayerName.ISHA]!!.minusMinutes(3), adjusted[PrayerName.ISHA])
    }
}
