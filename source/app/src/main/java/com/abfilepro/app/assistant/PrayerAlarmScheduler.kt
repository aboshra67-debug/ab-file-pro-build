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
            // P18 (AS-10): a reschedule (boot, time change, settings) between the adhan and its
            // follow-up must keep today's "did you pray?" question instead of silently dropping it.
            todayOccurrence(now, prayer, settings)?.let { today ->
                val check = today.plusMinutes(settings.prayerCheckMinutes.toLong())
                if (!today.isAfter(now) && check.isAfter(now) && PrayerCompletionStore(context).answer(today.toLocalDate(), prayer) != true) {
                    schedule(context, prayer, check, EVENT_CHECK, settings.exactAlarms)
                }
            }
        }
    }

    /**
     * P18: called when an adhan fires. Before P18 nothing scheduled the next day's alarm unless the
     * app was opened (DATE_CHANGED is not delivered to manifest receivers), so the adhan stopped.
     * Only the next occurrence of this prayer is added; today's follow-up question is kept.
     */
    fun scheduleFollowing(context: Context, prayer: PrayerName) {
        val settings = AssistantRepository(context).loadPrayerSettings()
        if (prayer !in settings.enabledAlerts || prayer == PrayerName.SUNRISE) return
        val zone = runCatching { ZoneId.of(settings.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val now = ZonedDateTime.now(zone)
        val occurrence = nextOccurrence(now.plusMinutes(1), prayer, settings) ?: return
        schedule(context, prayer, occurrence, EVENT_ADHAN, settings.exactAlarms)
        if (settings.preAlertMinutes > 0) {
            val pre = occurrence.minusMinutes(settings.preAlertMinutes.toLong())
            if (pre.isAfter(now)) schedule(context, prayer, pre, EVENT_PRE, settings.exactAlarms)
        }
        schedule(context, prayer, occurrence.plusMinutes(settings.prayerCheckMinutes.toLong()), EVENT_CHECK, settings.exactAlarms)
    }

    /** Test/diagnostic hook: is an alarm pending for this prayer event on [date]? */
    internal fun isScheduled(context: Context, prayer: PrayerName, event: String, date: LocalDate): Boolean =
        pendingIntent(context, prayer, event, date, PendingIntent.FLAG_NO_CREATE) != null

    private fun todayOccurrence(now: ZonedDateTime, prayer: PrayerName, settings: PrayerSettings): ZonedDateTime? {
        val date = now.toLocalDate()
        val offsetHours = now.zone.rules.getOffset(date.atStartOfDay(now.zone).toInstant()).totalSeconds / 3600.0
        val time = PrayerTimesCalculator.calculate(
            date, settings.latitude, settings.longitude, offsetHours,
            settings.method, settings.asrSchool, settings.adjustmentsMinutes,
        )[prayer] ?: return null
        return date.atTime(time).atZone(now.zone)
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
