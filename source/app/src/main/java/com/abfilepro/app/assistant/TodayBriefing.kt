package com.abfilepro.app.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.abfilepro.app.MainActivity
import com.abfilepro.app.R
import java.time.ZoneId
import java.time.ZonedDateTime

/** The morning "what is today" notification: when to ring and whether it is on. */
object TodayBriefingSettings {
    const val PREFS = "ab_file_pro_today"
    const val DEFAULT_HOUR = 7
    const val DEFAULT_MINUTE = 30

    data class Value(val enabled: Boolean, val hour: Int, val minute: Int)

    fun load(context: Context): Value {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Value(p.getBoolean("enabled", false), p.getInt("hour", DEFAULT_HOUR).coerceIn(0, 23), p.getInt("minute", DEFAULT_MINUTE).coerceIn(0, 59))
    }

    fun save(context: Context, value: Value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", value.enabled).putInt("hour", value.hour).putInt("minute", value.minute).putBoolean("seeded", true).apply()
        TodayBriefingScheduler.sync(context)
    }

    /** The first time the Today screen opens the briefing is switched on once; the person can turn it off. Returns true when it just did. */
    fun seedOnFirstUse(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean("seeded", false)) return false
        save(context, Value(true, DEFAULT_HOUR, DEFAULT_MINUTE))
        return true
    }
}

object TodayBriefingPolicy {
    /** Next moment at [hour]:[minute] strictly after [now]. Pure; JVM-tested. */
    fun nextTrigger(now: Long, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val current = java.time.Instant.ofEpochMilli(now).atZone(zone)
        var next = ZonedDateTime.of(current.toLocalDate(), java.time.LocalTime.of(hour, minute), zone)
        if (!next.isAfter(current)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }
}

object TodayBriefingScheduler {
    fun sync(context: Context) {
        cancel(context)
        val value = TodayBriefingSettings.load(context)
        if (!value.enabled) return
        val operation = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        ReminderAlarms.set(context, TodayBriefingPolicy.nextTrigger(System.currentTimeMillis(), value.hour, value.minute), operation)
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        pendingIntent(context, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
    }

    internal fun isScheduled(context: Context): Boolean = pendingIntent(context, PendingIntent.FLAG_NO_CREATE) != null

    private fun pendingIntent(context: Context, mode: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        "today-briefing".hashCode(),
        Intent(context, TodayBriefingReceiver::class.java),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )
}

class TodayBriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The next morning is set first, so a failure below never ends the daily briefing.
        TodayBriefingScheduler.sync(context)
        runCatching { RecurrenceEngine.advanceOverdue(context) }
        runCatching { MoneyRecurringEngine.run(context) }
        TodayWidget.refresh(context)
        val repository = AssistantRepository(context)
        val agenda = TodayAgendaBuilder.build(repository.loadEntries(), BillReminderRecords.load(context), System.currentTimeMillis())
        val openTasks = agenda.overdue.size + agenda.today.count { !it.done && it.kind == TodayKind.TASK }
        val events = agenda.today.count { it.kind == TodayKind.EVENT || it.kind == TodayKind.HEALTH_APPOINTMENT }
        val doses = agenda.today.count { it.kind == TodayKind.MEDICINE && !it.done }
        val bills = agenda.today.count { it.kind == TodayKind.BILL } + agenda.overdue.count { it.kind == TodayKind.BILL }
        if (openTasks + events + doses + bills == 0) return
        createChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val parts = buildList {
            if (openTasks > 0) add(ReminderText.pick(context, "$openTasks مهام", "$openTasks tasks", "$openTasks tâches"))
            if (events > 0) add(ReminderText.pick(context, "$events مواعيد", "$events appointments", "$events rendez-vous"))
            if (doses > 0) add(ReminderText.pick(context, "$doses جرعات", "$doses doses", "$doses doses"))
            if (bills > 0) add(ReminderText.pick(context, "$bills فواتير", "$bills bills", "$bills factures"))
        }
        val first = (agenda.overdue + agenda.today).firstOrNull { !it.done && it.kind != TodayKind.MEDICINE }?.title.orEmpty()
        val title = ReminderText.pick(context, "يومك النهارده", "Your day today", "Votre journée")
        val body = parts.joinToString(" • ") + if (first.isNotBlank()) "\n$first" else ""
        // Titles can be private (a doctor, a medicine): the lock screen only shows the generic line.
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ab_logo_icon)
            .setContentTitle(title)
            .setContentText(ReminderText.pick(context, "افتح الهاتف لعرض اليوم", "Unlock to see your day", "Déverrouillez pour voir"))
            .build()
        val open = PendingIntent.getActivity(
            context, "today-open".hashCode(), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ab_logo_icon)
            .setContentTitle(title)
            .setContentText(parts.joinToString(" • "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify("today-briefing".hashCode(), notification) }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ReminderText.pick(context, "ملخص الصباح", "Morning summary", "Résumé du matin"),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
    }

    companion object { private const val CHANNEL_ID = "assistant_today_v1" }
}
