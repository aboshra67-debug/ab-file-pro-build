package com.abfilepro.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** P25: when a bill's two reminders ring (the day before and on the due date, at 09:00). */
class BillReminderPolicyTest {
    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
    }.timeInMillis

    private val dueDay20 = at(20, 9)

    @Test fun aBillDaysAwayRingsTheDayBeforeAndOnTheDueDateAtNine() {
        val now = at(10, 12)
        assertEquals(at(19, 9), BillReminderPolicy.triggerFor(BillReminderPolicy.Kind.TOMORROW, dueDay20, now, null))
        assertEquals(at(20, 9), BillReminderPolicy.triggerFor(BillReminderPolicy.Kind.TODAY, dueDay20, now, null))
    }

    @Test fun aDueTimeOtherThanNineIsMovedToNineOfThatDay() {
        val now = at(10, 12)
        assertEquals(at(20, 9), BillReminderPolicy.triggerFor(BillReminderPolicy.Kind.TODAY, at(20, 17, 45), now, null))
    }

    @Test fun addingABillDueTomorrowRingsTheDayBeforeReminderRightAwayOnce() {
        val now = at(19, 20)
        val trigger = BillReminderPolicy.triggerFor(BillReminderPolicy.Kind.TOMORROW, dueDay20, now, null)
        assertNotNull(trigger)
        assertTrue(trigger!! > now && trigger - now <= 10_000L)
        assertNull("Already shown, so a reboot must not repeat it",
            BillReminderPolicy.triggerFor(BillReminderPolicy.Kind.TOMORROW, dueDay20, now, BillReminderPolicy.reminderMoment(dueDay20)))
    }

    @Test fun aBillDueTodayAfterNineStillRingsOnceUntilTheEvening() {
        val afternoon = at(20, 14)
        val trigger = BillReminderPolicy.triggerFor(BillReminderPolicy.Kind.TODAY, dueDay20, afternoon, null)
        assertNotNull(trigger)
        assertTrue(trigger!! - afternoon <= 10_000L)
        assertNull(BillReminderPolicy.triggerFor(BillReminderPolicy.Kind.TODAY, dueDay20, afternoon, at(20, 9)))
        assertNull("Too late in the day to be useful", BillReminderPolicy.triggerFor(BillReminderPolicy.Kind.TODAY, dueDay20, at(20, 22), null))
    }

    @Test fun aPastBillRingsNothing() {
        val now = at(25, 10)
        assertNull(BillReminderPolicy.triggerFor(BillReminderPolicy.Kind.TOMORROW, dueDay20, now, null))
        assertNull(BillReminderPolicy.triggerFor(BillReminderPolicy.Kind.TODAY, dueDay20, now, null))
    }

    @Test fun theTwoRemindersHaveSeparateLedgerKeys() {
        assertTrue(BillAlarmScheduler.ledgerKey(7L, BillReminderPolicy.Kind.TOMORROW) != BillAlarmScheduler.ledgerKey(7L, BillReminderPolicy.Kind.TODAY))
    }
}
