package com.abfilepro.app.assistant

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P27: what lands on the Today screen. "Now" is 2026-10-25 10:00 in Cairo. */
class TodayAgendaTest {
    private val zone = ZoneId.of("Africa/Cairo")
    private val day = LocalDate.of(2026, 10, 25)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()
    private val now = at(day, 10)
    private fun build(entries: List<AssistantEntry>, bills: List<BillReminderRecord> = emptyList()) = TodayAgendaBuilder.build(entries, bills, now, zone)
    private fun task(id: String, due: Long?, done: Boolean = false) = AssistantEntry(id, AssistantModule.TASKS, "task $id", dueAt = due, completed = done)

    @Test fun tasksAreSplitIntoOverdueTodaySoonAndUndated() {
        val agenda = build(listOf(
            task("old", at(day.minusDays(2), 9)),
            task("now", at(day, 15)),
            task("soon", at(day.plusDays(2), 9)),
            task("far", at(day.plusDays(9), 9)),
            task("none", null),
            task("done", at(day, 11), done = true),
        ))
        assertEquals(listOf("task:old"), agenda.overdue.map { it.key })
        assertEquals(listOf("task:now"), agenda.today.map { it.key })
        assertEquals(listOf("task:soon"), agenda.soon.map { it.key })
        assertEquals(1, agenda.undatedTasks)
    }

    @Test fun everyMedicineDoseIsAnItemAndTakenOnesAreMarkedDone() {
        val meta = HealthEntryMeta(dailyTime = "08:00", moreTimes = listOf("20:00"), lastTakenDate = day.toString(), takenCount = 1)
        val med = AssistantEntry("m1", AssistantModule.HEALTH, "دوا", secondary = HealthEntryCodec.encode(meta))
        val doses = build(listOf(med)).today.filter { it.kind == TodayKind.MEDICINE }
        assertEquals(2, doses.size)
        assertTrue(doses[0].done)
        assertFalse(doses[1].done)
    }

    @Test fun doseCountFromAnotherDayDoesNotCount() {
        val meta = HealthEntryMeta(dailyTime = "08:00", lastTakenDate = day.minusDays(1).toString(), takenCount = 1)
        val med = AssistantEntry("m1", AssistantModule.HEALTH, "دوا", secondary = HealthEntryCodec.encode(meta))
        assertFalse(build(listOf(med)).today.single().done)
    }

    @Test fun disabledMedicineIsSkipped() {
        val med = AssistantEntry("m1", AssistantModule.HEALTH, "دوا", secondary = HealthEntryCodec.encode(HealthEntryMeta()), enabled = false)
        assertTrue(build(listOf(med)).isEmpty)
    }

    @Test fun billsAndDocuments() {
        val bills = listOf(
            BillReminderRecord(1, "كهرباء", at(day, 12), "a"),
            BillReminderRecord(2, "مياه", at(day.minusDays(1), 12), "b"),
            BillReminderRecord(3, "نت", at(day.plusDays(2), 12), "c"),
            BillReminderRecord(4, "غاز", at(day.plusDays(10), 12), "d"),
        )
        val doc = AssistantEntry("d1", AssistantModule.DOCUMENTS, "جواز", dueAt = at(day.plusDays(10), 9))
        val agenda = build(listOf(doc), bills)
        assertEquals(listOf("bill:2"), agenda.overdue.map { it.key })
        assertEquals(listOf("bill:1"), agenda.today.map { it.key })
        assertEquals(setOf("bill:3", "doc:d1"), agenda.soon.map { it.key }.toSet())
    }

    @Test fun todayIsSortedByTimeAndPendingIsCounted() {
        val agenda = build(listOf(task("b", at(day, 18)), task("a", at(day, 12))))
        assertEquals(listOf("task:a", "task:b"), agenda.today.map { it.key })
        assertEquals(2, agenda.pendingToday)
    }
}
