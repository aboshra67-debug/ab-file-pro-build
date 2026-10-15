package com.abfilepro.app.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object TaskAlarmScheduler {
    fun hasExactAlarmAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarms.canScheduleExactAlarms()
    }

    fun rescheduleAll(context: Context, entries: List<AssistantEntry> = AssistantRepository(context).loadEntries()) {
        entries.filter { it.module == AssistantModule.TASKS || it.module == AssistantModule.CALENDAR }.forEach { entry ->
            cancel(context, entry.id)
            if (shouldSchedule(entry)) scheduleExact(context, entry)
        }
    }

    fun sync(context: Context, task: AssistantEntry) {
        cancel(context, task.id)
        if (shouldSchedule(task)) scheduleExact(context, task)
    }

    fun cancel(context: Context, taskId: String) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        pendingIntent(context, taskId, PendingIntent.FLAG_NO_CREATE)?.let(alarms::cancel)
    }

    private fun shouldSchedule(task: AssistantEntry): Boolean {
        if (task.module != AssistantModule.TASKS && task.module != AssistantModule.CALENDAR) return false
        if (task.completed || !task.enabled || task.dueAt == null) return false
        return triggerAt(task) > System.currentTimeMillis()
    }

    private fun scheduleExact(context: Context, task: AssistantEntry) {
        if (!hasExactAlarmAccess(context)) return
        val triggerAt = triggerAt(task)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = pendingIntent(context, task.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
    }

    private fun triggerAt(task: AssistantEntry): Long {
        val due = task.dueAt ?: return 0L
        val leadMinutes = if (task.module == AssistantModule.CALENDAR) (task.amount ?: 0.0).toLong().coerceAtLeast(0L) else 0L
        return due - (leadMinutes * 60_000L)
    }

    private fun pendingIntent(context: Context, taskId: String, mode: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        taskId.hashCode(),
        Intent(context, TaskAlarmReceiver::class.java).putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, taskId),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )
}
