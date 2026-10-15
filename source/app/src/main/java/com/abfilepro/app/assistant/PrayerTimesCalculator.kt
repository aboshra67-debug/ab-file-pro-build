package com.abfilepro.app.assistant

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Offline astronomical calculation based on the widely used PrayTimes equations. */
object PrayerTimesCalculator {
    fun calculate(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zoneOffsetHours: Double,
        method: PrayerMethod,
        asrSchool: AsrSchool,
        adjustmentsMinutes: Map<PrayerName, Int> = emptyMap(),
    ): Map<PrayerName, LocalTime> {
        require(latitude in -66.0..66.0) { "Latitude outside supported prayer calculation range" }
        require(longitude in -180.0..180.0) { "Invalid longitude" }
        val julian = julianDate(date.year, date.monthValue, date.dayOfMonth) - longitude / (15.0 * 24.0)
        var guesses = doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0)
        repeat(2) { guesses = computeTimes(julian, latitude, method, asrSchool, guesses) }
        val adjusted = guesses.map { fixHour(it + zoneOffsetHours - longitude / 15.0) }
        return PrayerName.entries.mapIndexed { index, prayer ->
            val minutes = (adjusted[index] * 60.0).toInt() + (adjustmentsMinutes[prayer] ?: 0)
            prayer to LocalTime.of(floorMod(minutes / 60, 24), floorMod(minutes, 60))
        }.toMap()
    }

    private fun computeTimes(
        julian: Double,
        latitude: Double,
        method: PrayerMethod,
        asrSchool: AsrSchool,
        guesses: DoubleArray,
    ): DoubleArray {
        val portions = guesses.map { it / 24.0 }
        return doubleArrayOf(
            computeTime(julian, latitude, 180.0 - method.fajrAngle, portions[0]),
            computeTime(julian, latitude, 180.0 - 0.833, portions[1]),
            midDay(julian, portions[2]),
            asrTime(julian, latitude, asrSchool.shadowFactor.toDouble(), portions[3]),
            computeTime(julian, latitude, 0.833, portions[4]),
            computeTime(julian, latitude, method.ishaAngle, portions[5]),
        )
    }

    private fun computeTime(julian: Double, latitude: Double, angle: Double, portion: Double): Double {
        val declination = sunPosition(julian + portion).first
        val noon = midDay(julian, portion)
        val numerator = -sinDeg(angle) - sinDeg(declination) * sinDeg(latitude)
        val denominator = cosDeg(declination) * cosDeg(latitude)
        val ratio = (numerator / denominator).coerceIn(-1.0, 1.0)
        val delta = acosDeg(ratio) / 15.0
        return noon + if (angle > 90.0) -delta else delta
    }

    private fun asrTime(julian: Double, latitude: Double, factor: Double, portion: Double): Double {
        val declination = sunPosition(julian + portion).first
        val angle = -acotDeg(factor + tanDeg(abs(latitude - declination)))
        return computeTime(julian, latitude, angle, portion)
    }

    private fun midDay(julian: Double, portion: Double): Double =
        fixHour(12.0 - sunPosition(julian + portion).second)

    /** Declination in degrees and equation of time in hours. */
    private fun sunPosition(julian: Double): Pair<Double, Double> {
        val days = julian - 2451545.0
        val meanAnomaly = fixAngle(357.529 + 0.98560028 * days)
        val meanLongitude = fixAngle(280.459 + 0.98564736 * days)
        val eclipticLongitude = fixAngle(
            meanLongitude + 1.915 * sinDeg(meanAnomaly) + 0.020 * sinDeg(2.0 * meanAnomaly)
        )
        val obliquity = 23.439 - 0.00000036 * days
        var rightAscension = atan2Deg(
            cosDeg(obliquity) * sinDeg(eclipticLongitude),
            cosDeg(eclipticLongitude),
        ) / 15.0
        rightAscension = fixHour(rightAscension)
        val equation = meanLongitude / 15.0 - rightAscension
        val declination = asinDeg(sinDeg(obliquity) * sinDeg(eclipticLongitude))
        return declination to equation
    }

    private fun julianDate(yearValue: Int, monthValue: Int, day: Int): Double {
        var year = yearValue
        var month = monthValue
        if (month <= 2) {
            year -= 1
            month += 12
        }
        val a = floor(year / 100.0)
        val b = 2.0 - a + floor(a / 4.0)
        return floor(365.25 * (year + 4716)) + floor(30.6001 * (month + 1)) + day + b - 1524.5
    }

    private fun floorMod(value: Int, divisor: Int): Int = ((value % divisor) + divisor) % divisor
    private fun fixAngle(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    private fun fixHour(value: Double): Double = ((value % 24.0) + 24.0) % 24.0
    private fun radians(value: Double): Double = value * PI / 180.0
    private fun degrees(value: Double): Double = value * 180.0 / PI
    private fun sinDeg(value: Double): Double = sin(radians(value))
    private fun cosDeg(value: Double): Double = cos(radians(value))
    private fun tanDeg(value: Double): Double = tan(radians(value))
    private fun asinDeg(value: Double): Double = degrees(asin(value))
    private fun acosDeg(value: Double): Double = degrees(acos(value))
    private fun atan2Deg(y: Double, x: Double): Double = degrees(atan2(y, x))
    private fun acotDeg(value: Double): Double = degrees(atan(1.0 / value))
}
