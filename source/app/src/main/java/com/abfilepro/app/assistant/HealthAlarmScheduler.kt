package com.abfilepro.app.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object HealthAlarmScheduler {
    fun hasExactAlarmAccess(context: Context): Boolean = ReminderAlarms.exactAllowed(context)

    fun rescheduleAll(context: Context, entries: List<AssistantEntry> = AssistantRepository(context).loadEntries()) {
        entries.filter { it.module == AssistantModule.HEALTH }.forEach { sync(context, it) }
    }

    fun sync(context: Context, entry: AssistantEntry) {
        cancel(context, entry.id)
        if (!shouldSchedule(entry)) return
        val operation = pendingIntent(context, entry.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        // P18: medicine reminders also work when exact alarms are not allowed (inexact fallback).
        ReminderAlarms.set(context, entry.dueAt ?: return, operation)
    }

    fun cancel(context: Context, entryId: String) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        // P18: cancel the PendingIntent too, so a cancelled reminder is really gone (not just its alarm).
        pendingIntent(context, entryId, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
    }

    private fun shouldSchedule(entry: AssistantEntry): Boolean =
        entry.module == AssistantModule.HEALTH && entry.enabled && !entry.completed && (entry.dueAt ?: 0L) > System.currentTimeMillis()

    /** Test/diagnostic hook: is a reminder alarm pending for this entry? */
    internal fun isScheduled(context: Context, id: String): Boolean =
        pendingIntent(context, id, PendingIntent.FLAG_NO_CREATE) != null

    private fun pendingIntent(context: Context, entryId: String, mode: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        ("health:" + entryId).hashCode(),
        Intent(context, HealthAlarmReceiver::class.java).putExtra(HealthAlarmReceiver.EXTRA_ENTRY_ID, entryId),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )
}
