package com.abfilepro.app.assistant

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P25: family members and medicines taken several times a day. */
class HealthEntryCodecTest {
    private val zone = ZoneId.systemDefault()
    private fun at(date: LocalDate, hour: Int) = ZonedDateTime.of(date, LocalTime.of(hour, 0), zone).toInstant().toEpochMilli()
    private val day = LocalDate.of(2026, 10, 25)

    @Test fun anOlderSingleDoseEntryStillReadsTheSame() {
        val meta = HealthEntryCodec.parse("health|med|owner=partner|time=09:30|last=2026-10-20")
        assertEquals(HealthOwner.PARTNER, meta.owner)
        assertEquals(listOf("09:30"), meta.doseTimes)
        assertEquals(1, meta.takenCount)
        assertFalse(HealthEntryCodec.takenToday(meta, at(day, 12)))
    }

    @Test fun severalDosesSurviveEncodingAndAreSorted() {
        val meta = HealthEntryMeta(HealthEntryType.MEDICATION, HealthOwner.SELF, "14:00", "", moreTimes = listOf("20:00", "08:00"))
        val back = HealthEntryCodec.parse(HealthEntryCodec.encode(meta))
        assertEquals(listOf("08:00", "14:00", "20:00"), back.doseTimes)
    }

    @Test fun theNextAlarmIsTheSoonestDoseOfTheDay() {
        val meta = HealthEntryMeta(HealthEntryType.MEDICATION, HealthOwner.SELF, "08:00", "", moreTimes = listOf("14:00", "20:00"))
        assertEquals(at(day, 14), HealthEntryCodec.nextMedicationTrigger(meta, at(day, 10)))
    }

    @Test fun afterTheLastDoseTheNextAlarmIsTomorrowsFirst() {
        val meta = HealthEntryMeta(HealthEntryType.MEDICATION, HealthOwner.SELF, "08:00", "", moreTimes = listOf("14:00", "20:00"))
        assertEquals(at(day.plusDays(1), 8), HealthEntryCodec.nextMedicationTrigger(meta, at(day, 21)))
    }

    @Test fun aMedicineIsTakenTodayOnlyWhenEveryDoseIsConfirmed() {
        val now = at(day, 12)
        var meta = HealthEntryMeta(HealthEntryType.MEDICATION, HealthOwner.SELF, "08:00", "", moreTimes = listOf("14:00", "20:00"))
        meta = HealthEntryCodec.markTakenToday(meta, now)
        assertFalse(HealthEntryCodec.takenToday(meta, now))
        meta = HealthEntryCodec.markTakenToday(meta, now)
        assertFalse(HealthEntryCodec.takenToday(meta, now))
        meta = HealthEntryCodec.markTakenToday(meta, now)
        assertTrue(HealthEntryCodec.takenToday(meta, now))
        assertEquals(3, HealthEntryCodec.parse(HealthEntryCodec.encode(meta)).takenCount)
    }

    @Test fun anExtraMemberKeepsTheNameAndSeparatorsCannotBreakTheFormat() {
        val meta = HealthEntryMeta(HealthEntryType.MEDICATION, HealthOwner.MEMBER, "08:00", "", member = "Ali|x=y,z")
        val back = HealthEntryCodec.parse(HealthEntryCodec.encode(meta))
        assertEquals(HealthOwner.MEMBER, back.owner)
        assertEquals(HealthEntryCodec.cleanName("Ali|x=y,z"), back.member)
        assertEquals(listOf("08:00"), back.doseTimes)
    }

    @Test fun anUnknownOwnerFallsBackToMe() {
        assertEquals(HealthOwner.SELF, HealthEntryCodec.parse("health|med|owner=cousin|time=08:00|last=").owner)
    }
}
