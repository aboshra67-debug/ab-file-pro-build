package com.abfilepro.app.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

object PrayerAlarmScheduler {
    private const val REQUEST_BASE = 7_410
    private const val EVENT_PRE = "pre"
    private const val EVENT_ADHAN = "adhan"
    private const val EVENT_CHECK = "check"
    private const val EVENT_NUDGE = "nudge"

    fun reschedule(context: Context, settings: PrayerSettings = AssistantRepository(context).loadPrayerSettings()) {
        cancelAll(context)
        if (settings.enabledAlerts.isEmpty()) return
        val zone = runCatching { ZoneId.of(settings.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val now = ZonedDateTime.now(zone)
        settings.enabledAlerts.filter { it != PrayerName.SUNRISE }.forEach { prayer ->
            nextOccurrence(now, prayer, settings)?.let { occurrence ->
                schedule(context, prayer, occurrence, EVENT_ADHAN, settings.exactAlarms)
                if (settings.preAlertMinutes > 0) {
                    val pre = occurrence.minusMinutes(settings.preAlertMinutes.toLong())
                    if (pre.isAfter(now)) schedule(context, prayer, pre, EVENT_PRE, settings.exactAlarms)
                }
                val check = occurrence.plusMinutes(settings.prayerCheckMinutes.toLong())
                if (check.isAfter(now)) schedule(context, prayer, check, EVENT_CHECK, settings.exactAlarms)
            }
        }
    }

    fun scheduleNudge(context: Context, prayer: PrayerName, date: LocalDate, delayMinutes: Int) {
        val settings = AssistantRepository(context).loadPrayerSettings()
        val zone = runCatching { ZoneId.of(settings.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val at = ZonedDateTime.now(zone).plusMinutes(delayMinutes.toLong())
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = pendingIntent(context, prayer, EVENT_NUDGE, date, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        setAlarm(alarms, at.toInstant().toEpochMilli(), operation, settings.exactAlarms)
    }

    fun cancelQuestion(context: Context, prayer: PrayerName, date: LocalDate) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        pendingIntent(context, prayer, EVENT_NUDGE, date, PendingIntent.FLAG_NO_CREATE)?.let(alarms::cancel)
        pendingIntent(context, prayer, EVENT_CHECK, date, PendingIntent.FLAG_NO_CREATE)?.let(alarms::cancel)
    }

    fun cancelAll(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val today = LocalDate.now()
        PrayerName.entries.forEach { prayer ->
            listOf(EVENT_PRE, EVENT_ADHAN, EVENT_CHECK, EVENT_NUDGE).forEach { event ->
                for (offset in -1L..2L) {
                    pendingIntent(context, prayer, event, today.plusDays(offset), PendingIntent.FLAG_NO_CREATE)?.let(alarms::cancel)
                }
            }
        }
    }

    private fun nextOccurrence(now: ZonedDateTime, prayer: PrayerName, settings: PrayerSettings): ZonedDateTime? {
        for (dayOffset in 0L..2L) {
            val date = now.toLocalDate().plusDays(dayOffset)
            val offsetHours = now.zone.rules.getOffset(date.atStartOfDay(now.zone).toInstant()).totalSeconds / 3600.0
            val time = PrayerTimesCalculator.calculate(
                date, settings.latitude, settings.longitude, offsetHours,
                settings.method, settings.asrSchool, settings.adjustmentsMinutes,
            )[prayer] ?: continue
            val candidate = date.atTime(time).atZone(now.zone)
            if (candidate.isAfter(now.plusSeconds(5))) return candidate
        }
        return null
    }

    private fun schedule(context: Context, prayer: PrayerName, whenAt: ZonedDateTime, event: String, exactRequested: Boolean) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = pendingIntent(context, prayer, event, whenAt.toLocalDate(), PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        setAlarm(alarms, whenAt.toInstant().toEpochMilli(), operation, exactRequested)
    }

    private fun setAlarm(alarms: AlarmManager, triggerAt: Long, operation: PendingIntent, exactRequested: Boolean) {
        val exactAllowed = exactRequested && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms())
        if (exactAllowed) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
    }

    private fun pendingIntent(context: Context, prayer: PrayerName, event: String, date: LocalDate, mode: Int): PendingIntent? {
        val eventIndex = when (event) { EVENT_PRE -> 0; EVENT_ADHAN -> 1; EVENT_CHECK -> 2; else -> 3 }
        val dayCode = date.toEpochDay().toInt()
        val requestCode = REQUEST_BASE + (dayCode and 0x7FFF) * 32 + prayer.ordinal * 4 + eventIndex
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, PrayerAlarmReceiver::class.java)
                .putExtra(PrayerAlarmReceiver.EXTRA_PRAYER, prayer.storageId)
                .putExtra(PrayerAlarmReceiver.EXTRA_EVENT, event)
                .putExtra(PrayerAlarmReceiver.EXTRA_DATE, date.toString()),
            mode or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
