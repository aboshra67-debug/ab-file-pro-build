package com.abfilepro.app.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId

object DocumentRenewalAlarmScheduler {
    fun hasExactAlarmAccess(context: Context): Boolean = ReminderAlarms.exactAllowed(context)

    fun rescheduleAll(context: Context, entries: List<AssistantEntry> = AssistantRepository(context).loadEntries()) {
        entries.filter { it.module == AssistantModule.DOCUMENTS }.forEach { entry -> sync(context, entry) }
    }

    fun sync(context: Context, entry: AssistantEntry) {
        cancel(context, entry.id)
        val trigger = plannedTrigger(context, entry) ?: return
        val operation = pendingIntent(context, entry.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        // P18: inexact fallback when exact alarms are not allowed; a renewal reminder is never lost.
        ReminderAlarms.set(context, trigger, operation)
    }

    fun cancel(context: Context, entryId: String) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        // P18: cancel the PendingIntent too, so a cancelled reminder is really gone (not just its alarm).
        pendingIntent(context, entryId, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
    }

    /** P18 (AS-05): if the lead time already passed but the expiry is still ahead, remind once now. */
    private fun plannedTrigger(context: Context, entry: AssistantEntry): Long? {
        if (entry.module != AssistantModule.DOCUMENTS || !entry.enabled || entry.completed) return null
        val due = entry.dueAt ?: return null
        val lead = (due - triggerAt(entry)).coerceAtLeast(0L)
        return ReminderPolicy.triggerFor(due, lead, System.currentTimeMillis(), ReminderLedger.notifiedFor(context, entry.id))
    }

    private fun triggerAt(entry: AssistantEntry): Long {
        val due = entry.dueAt ?: return 0L
        val zone = ZoneId.systemDefault()
        val expiry = Instant.ofEpochMilli(due).atZone(zone)
        return expiry.minusDays(DocumentRenewalCodec.leadDays(entry).toLong()).toInstant().toEpochMilli()
    }

    /** Test/diagnostic hook: is a reminder alarm pending for this entry? */
    internal fun isScheduled(context: Context, id: String): Boolean =
        pendingIntent(context, id, PendingIntent.FLAG_NO_CREATE) != null

    private fun pendingIntent(context: Context, entryId: String, mode: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        entryId.hashCode(),
        Intent(context, DocumentRenewalAlarmReceiver::class.java).putExtra(DocumentRenewalAlarmReceiver.EXTRA_DOCUMENT_ID, entryId),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )
}
