package com.abfilepro.app.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object TaskAlarmScheduler {
    fun hasExactAlarmAccess(context: Context): Boolean = ReminderAlarms.exactAllowed(context)

    fun rescheduleAll(context: Context, entries: List<AssistantEntry> = AssistantRepository(context).loadEntries()) {
        entries.filter { it.module == AssistantModule.TASKS || it.module == AssistantModule.CALENDAR }.forEach { entry ->
            sync(context, entry)
        }
    }

    fun sync(context: Context, task: AssistantEntry) {
        cancel(context, task.id)
        val triggerAt = triggerAt(context, task) ?: return
        val operation = pendingIntent(context, task.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        // P18: falls back to an inexact alarm when exact alarms are not allowed (Android 14+ default).
        ReminderAlarms.set(context, triggerAt, operation)
    }

    fun cancel(context: Context, taskId: String) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        // P18: cancel the PendingIntent too, so a cancelled reminder is really gone (not just its alarm).
        pendingIntent(context, taskId, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
    }

    private fun triggerAt(context: Context, task: AssistantEntry): Long? {
        if (task.module != AssistantModule.TASKS && task.module != AssistantModule.CALENDAR) return null
        if (task.completed || !task.enabled) return null
        val due = task.dueAt ?: return null
        val leadMinutes = if (task.module == AssistantModule.CALENDAR) (task.amount ?: 0.0).toLong().coerceAtLeast(0L) else 0L
        return ReminderPolicy.triggerFor(due, leadMinutes * 60_000L, System.currentTimeMillis(), ReminderLedger.notifiedFor(context, task.id))
    }

    /** Test/diagnostic hook: is a reminder alarm pending for this entry? */
    internal fun isScheduled(context: Context, id: String): Boolean =
        pendingIntent(context, id, PendingIntent.FLAG_NO_CREATE) != null

    private fun pendingIntent(context: Context, taskId: String, mode: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        taskId.hashCode(),
        Intent(context, TaskAlarmReceiver::class.java).putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, taskId),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )
}
