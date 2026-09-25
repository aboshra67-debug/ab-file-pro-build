package com.abfilepro.app.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.ZoneId

object DocumentRenewalAlarmScheduler {
    fun hasExactAlarmAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarms.canScheduleExactAlarms()
    }

    fun rescheduleAll(context: Context, entries: List<AssistantEntry> = AssistantRepository(context).loadEntries()) {
        entries.filter { it.module == AssistantModule.DOCUMENTS }.forEach { entry ->
            cancel(context, entry.id)
            if (shouldSchedule(entry)) scheduleExact(context, entry)
        }
    }

    fun sync(context: Context, entry: AssistantEntry) {
        cancel(context, entry.id)
        if (shouldSchedule(entry)) scheduleExact(context, entry)
    }

    fun cancel(context: Context, entryId: String) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        pendingIntent(context, entryId, PendingIntent.FLAG_NO_CREATE)?.let(alarms::cancel)
    }

    private fun shouldSchedule(entry: AssistantEntry): Boolean {
        if (entry.module != AssistantModule.DOCUMENTS || !entry.enabled || entry.completed || entry.dueAt == null) return false
        return triggerAt(entry) > System.currentTimeMillis()
    }

    private fun triggerAt(entry: AssistantEntry): Long {
        val due = entry.dueAt ?: return 0L
        val zone = ZoneId.systemDefault()
        val expiry = Instant.ofEpochMilli(due).atZone(zone)
        return expiry.minusDays(DocumentRenewalCodec.leadDays(entry).toLong()).toInstant().toEpochMilli()
    }

    private fun scheduleExact(context: Context, entry: AssistantEntry) {
        if (!hasExactAlarmAccess(context)) return
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = pendingIntent(context, entry.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt(entry), operation)
    }

    private fun pendingIntent(context: Context, entryId: String, mode: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        entryId.hashCode(),
        Intent(context, DocumentRenewalAlarmReceiver::class.java).putExtra(DocumentRenewalAlarmReceiver.EXTRA_DOCUMENT_ID, entryId),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )
}
