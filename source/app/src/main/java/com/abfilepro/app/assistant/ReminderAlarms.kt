package com.abfilepro.app.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build

/**
 * P18: one place that decides how a reminder alarm is set.
 *
 * Android 14+ denies SCHEDULE_EXACT_ALARM by default for new installs. Before P18 the task,
 * medicine and document schedulers simply returned in that case, so those reminders never fired.
 * Now an exact alarm is used when allowed, otherwise an inexact, Doze-allowed alarm (Android may
 * deliver it a few minutes late) — a late reminder instead of no reminder.
 */
object ReminderAlarms {
    fun exactAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    /** Returns true when an alarm was set. */
    fun set(context: Context, triggerAt: Long, operation: PendingIntent, preferExact: Boolean = true): Boolean {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching {
            if (preferExact && exactAllowed(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        }.isSuccess
    }
}

/**
 * P18 (AS-05): when the lead time has already passed but the event itself is still ahead, the
 * reminder fires once, right away, instead of never. The ledger remembers what was already shown so
 * a reboot or time change does not repeat it.
 */
object ReminderPolicy {
    const val IMMEDIATE_DELAY_MS = 3_000L

    /** Trigger time to use, or null when nothing should be scheduled. Pure; JVM-tested. */
    fun triggerFor(eventAt: Long, leadMs: Long, now: Long, alreadyNotifiedFor: Long?): Long? {
        if (eventAt <= now) return null
        val wanted = eventAt - leadMs.coerceAtLeast(0L)
        if (wanted > now) return wanted
        if (alreadyNotifiedFor == eventAt) return null
        return now + IMMEDIATE_DELAY_MS
    }
}

internal object ReminderLedger {
    private const val PREFS = "ab_file_pro_reminder_ledger_v18"

    fun notifiedFor(context: Context, id: String): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(id, -1L).takeIf { it >= 0L }

    fun markNotified(context: Context, id: String, eventAt: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(id, eventAt).apply()
    }
}

/** P18: notification text in the app language chosen in Settings (was Arabic-only). */
internal object ReminderText {
    fun language(context: Context): String =
        context.getSharedPreferences("ab_file_pro", Context.MODE_PRIVATE).getString("language", "ar").orEmpty().ifBlank { "ar" }

    fun pick(context: Context, ar: String, en: String, fr: String = en): String = when (language(context)) {
        "en" -> en
        "fr" -> fr
        else -> ar
    }
}
