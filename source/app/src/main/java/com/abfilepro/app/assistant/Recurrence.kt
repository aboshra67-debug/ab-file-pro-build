package com.abfilepro.app.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** P27: how a task or appointment repeats. */
enum class Recurrence(val key: String) {
    NONE("none"), DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly");

    /** The next occurrence after [dueAt], same wall-clock time. Pure; JVM-tested. */
    fun next(dueAt: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (this == NONE) return dueAt
        val start = Instant.ofEpochMilli(dueAt).atZone(zone)
        return when (this) {
            DAILY -> start.plusDays(1)
            WEEKLY -> start.plusWeeks(1)
            else -> start.plusMonths(1)
        }.toInstant().toEpochMilli()
    }

    /** First occurrence strictly after [now], stepping from [dueAt] (bounded, so a bad date can't loop). */
    fun nextAfter(dueAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (this == NONE) return dueAt
        var value = dueAt
        var guard = 0
        while (value <= now && guard++ < 800) value = next(value, zone)
        return value
    }

    companion object {
        fun fromKey(value: String?): Recurrence = entries.firstOrNull { it.key == value } ?: NONE
    }
}

/** Which entry repeats. Kept beside the records (not inside them) so older data and sync stay unchanged. */
object RecurrenceStore {
    const val PREFS = "ab_file_pro_recurrence"
    const val KEY = "rules_v1"

    private fun load(context: Context): JSONObject =
        runCatching { JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "{}") }
            .getOrDefault(JSONObject())

    fun get(context: Context, id: String): Recurrence = Recurrence.fromKey(load(context).optString(id, null))

    fun set(context: Context, id: String, recurrence: Recurrence) {
        val all = load(context)
        if (recurrence == Recurrence.NONE) all.remove(id) else all.put(id, recurrence.key)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, all.toString()).apply()
    }

    fun remove(context: Context, id: String) = set(context, id, Recurrence.NONE)

    fun ids(context: Context): Set<String> = load(context).keys().asSequence().toSet()
}

/**
 * The editors choose a repeat rule just before they hand a new record to the shared add path, which
 * is what creates the record id. The chosen rule waits here for the instant between the two calls.
 */
object RecurrencePending {
    @Volatile private var value: Recurrence = Recurrence.NONE
    fun put(rule: Recurrence) { value = rule }
    fun take(): Recurrence { val v = value; value = Recurrence.NONE; return v }
}

object RecurrenceEngine {
    /**
     * A repeating task that is ticked off moves to its next occurrence instead of closing, so
     * "كل يوم" really comes back. Everything else is saved as it is.
     */
    fun beforeSave(context: Context, entry: AssistantEntry, now: Long = System.currentTimeMillis()): AssistantEntry {
        if (entry.module != AssistantModule.TASKS || !entry.completed || entry.dueAt == null) return entry
        val rule = RecurrenceStore.get(context, entry.id)
        return if (rule == Recurrence.NONE) entry else advance(entry, rule, now)
    }

    /**
     * A repeating task/appointment whose time has passed moves to its next occurrence (and is
     * un-completed). Runs when the assistant opens, each morning, and after a reboot.
     * Returns how many records moved.
     */
    fun advanceOverdue(context: Context, now: Long = System.currentTimeMillis()): Int {
        val ids = RecurrenceStore.ids(context)
        if (ids.isEmpty()) { scheduleNextAdvance(context, now); return 0 }
        val repository = AssistantRepository(context)
        var moved = 0
        repository.loadEntries()
            .filter { it.id in ids && (it.module == AssistantModule.TASKS || it.module == AssistantModule.CALENDAR) }
            .forEach { entry ->
                val due = entry.dueAt ?: return@forEach
                if (due > now) return@forEach
                val rule = RecurrenceStore.get(context, entry.id)
                if (rule == Recurrence.NONE) return@forEach
                val updated = advance(entry, rule, now)
                repository.update(repository.loadEntries(), updated)
                TaskAlarmScheduler.sync(context, updated)
                moved++
            }
        scheduleNextAdvance(context, now)
        return moved
    }

    /**
     * One alarm, a minute after the nearest repeating record falls due, moves it to its next
     * occurrence (and arms that occurrence's reminder). Without it a daily task would ring once and
     * then wait for the assistant to be opened.
     */
    fun scheduleNextAdvance(context: Context, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context, "recurrence-advance".hashCode(), Intent(context, RecurrenceAdvanceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ids = RecurrenceStore.ids(context)
        val next = if (ids.isEmpty()) null else AssistantRepository(context).loadEntries()
            .filter { it.id in ids && (it.module == AssistantModule.TASKS || it.module == AssistantModule.CALENDAR) && it.dueAt != null }
            .mapNotNull { it.dueAt }
            .filter { it > now - 60_000L }
            .minOrNull()
        if (next == null) { alarms.cancel(pending); return }
        ReminderAlarms.set(context, maxOf(next + 60_000L, now + 5_000L), pending)
    }

    /** Pure: the entry moved to its next occurrence after [now]. */
    fun advance(entry: AssistantEntry, rule: Recurrence, now: Long): AssistantEntry {
        val due = entry.dueAt ?: return entry
        val next = rule.nextAfter(due, now)
        val shifted = if (entry.module == AssistantModule.CALENDAR) {
            entry.secondary.toLongOrNull()?.let { (it + (next - due)).toString() } ?: entry.secondary
        } else entry.secondary
        return entry.copy(dueAt = next, secondary = shifted, completed = false)
    }
}

class RecurrenceAdvanceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { RecurrenceEngine.advanceOverdue(context) }
    }
}
