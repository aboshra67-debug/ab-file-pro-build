package com.abfilepro.app.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object HealthAlarmScheduler {
    fun hasExactAlarmAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    fun rescheduleAll(context: Context, entries: List<AssistantEntry> = AssistantRepository(context).loadEntries()) {
        entries.filter { it.module == AssistantModule.HEALTH }.forEach { sync(context, it) }
    }

    fun sync(context: Context, entry: AssistantEntry) {
        cancel(context, entry.id)
        if (!shouldSchedule(entry) || !hasExactAlarmAccess(context)) return
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = pendingIntent(context, entry.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.dueAt ?: return, operation)
    }

    fun cancel(context: Context, entryId: String) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        pendingIntent(context, entryId, PendingIntent.FLAG_NO_CREATE)?.let(alarms::cancel)
    }

    private fun shouldSchedule(entry: AssistantEntry): Boolean =
        entry.module == AssistantModule.HEALTH && entry.enabled && !entry.completed && (entry.dueAt ?: 0L) > System.currentTimeMillis()

    private fun pendingIntent(context: Context, entryId: String, mode: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        ("health:" + entryId).hashCode(),
        Intent(context, HealthAlarmReceiver::class.java).putExtra(HealthAlarmReceiver.EXTRA_ENTRY_ID, entryId),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )
}
