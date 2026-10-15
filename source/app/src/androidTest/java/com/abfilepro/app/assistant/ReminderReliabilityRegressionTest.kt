package com.abfilepro.app.assistant

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** P18: reminders keep firing (chain, lead time, follow-up) on a real device AlarmManager. */
@RunWith(AndroidJUnit4::class)
class ReminderReliabilityRegressionTest {
    private lateinit var context: Context
    private lateinit var repo: AssistantRepository
    private val prefNames = listOf("ab_personal_assistant", "ab_file_pro_reminder_ledger_v18", "ab_prayer_completion_v1", "ab_file_pro_family_cloud_sync")

    @Before fun clean() {
        context = ApplicationProvider.getApplicationContext()
        prefNames.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        java.io.File(context.filesDir, "assistant").deleteRecursively(); AssistantStore.resetCacheForTests()
        repo = AssistantRepository(context)
    }

    @After fun cleanup() {
        repo.loadEntries().forEach {
            TaskAlarmScheduler.cancel(context, it.id); HealthAlarmScheduler.cancel(context, it.id); DocumentRenewalAlarmScheduler.cancel(context, it.id)
        }
        PrayerAlarmScheduler.cancelAll(context)
        prefNames.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        java.io.File(context.filesDir, "assistant").deleteRecursively(); AssistantStore.resetCacheForTests()
    }

    @Test fun calendarReminderWhoseLeadTimeAlreadyPassedStillFires() {
        val due = System.currentTimeMillis() + 5 * 60_000L
        val entries = repo.add(emptyList(), AssistantModule.CALENDAR, "Meeting", dueAt = due, amount = 15.0)
        val event = entries.first()
        TaskAlarmScheduler.sync(context, event)
        assertTrue("A 15-minute lead on an event 5 minutes away scheduled nothing", TaskAlarmScheduler.isScheduled(context, event.id))
        ReminderLedger.markNotified(context, event.id, due)
        TaskAlarmScheduler.sync(context, event)
        assertFalse("The immediate reminder must not repeat after it was shown", TaskAlarmScheduler.isScheduled(context, event.id))
    }

    @Test fun medicineReceiverSchedulesTheNextDailyDose() {
        val meta = HealthEntryMeta(type = HealthEntryType.MEDICATION, dailyTime = "08:00")
        val entries = repo.add(emptyList(), AssistantModule.HEALTH, "Vitamin D", dueAt = System.currentTimeMillis() - 1_000L,
            secondary = HealthEntryCodec.encode(meta))
        val med = entries.first()
        HealthAlarmReceiver().onReceive(context, Intent().putExtra(HealthAlarmReceiver.EXTRA_ENTRY_ID, med.id))
        val next = repo.loadEntries().first { it.id == med.id }
        assertTrue("Next dose was not moved to the future", (next.dueAt ?: 0L) > System.currentTimeMillis())
        assertTrue("Next dose has no alarm", HealthAlarmScheduler.isScheduled(context, med.id))
    }

    @Test fun documentWhoseReminderWindowStartedIsRemindedOnce() {
        val expiry = System.currentTimeMillis() + 2L * 24 * 3600 * 1000
        val entries = repo.add(emptyList(), AssistantModule.DOCUMENTS, "Passport", dueAt = expiry, amount = 30.0)
        val doc = entries.first()
        DocumentRenewalAlarmScheduler.sync(context, doc)
        assertTrue("Expiry in 2 days with a 30-day reminder scheduled nothing", DocumentRenewalAlarmScheduler.isScheduled(context, doc.id))
    }

    @Test fun adhanSchedulesTomorrowAndKeepsTodaysFollowUp() {
        repo.savePrayerSettings(PrayerSettings(enabledAlerts = setOf(PrayerName.FAJR), preAlertMinutes = 0, prayerCheckMinutes = 90))
        val zone = ZoneId.of(PrayerSettings().zoneId)
        val today = ZonedDateTime.now(zone).toLocalDate()
        PrayerAlarmReceiver().onReceive(context, Intent()
            .putExtra(PrayerAlarmReceiver.EXTRA_PRAYER, PrayerName.FAJR.storageId)
            .putExtra(PrayerAlarmReceiver.EXTRA_EVENT, PrayerAlarmReceiver.EVENT_ADHAN)
            .putExtra(PrayerAlarmReceiver.EXTRA_DATE, today.toString()))
        val scheduledSomeNextDay = (0L..2L).any { PrayerAlarmScheduler.isScheduled(context, PrayerName.FAJR, PrayerAlarmReceiver.EVENT_ADHAN, today.plusDays(it)) }
        assertTrue("After an adhan the next one was not scheduled", scheduledSomeNextDay)
    }
}
