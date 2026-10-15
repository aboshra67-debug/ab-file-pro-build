package com.abfilepro.app.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.abfilepro.app.MainActivity
import com.abfilepro.app.R
import java.text.DateFormat
import java.util.Date

class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        val task = AssistantRepository(context).loadEntries().firstOrNull {
            it.id == id && (it.module == AssistantModule.TASKS || it.module == AssistantModule.CALENDAR)
        } ?: return
        if (task.completed || !task.enabled) return
        createChannel(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val openApp = PendingIntent.getActivity(context, id.hashCode(), Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val isCalendar = task.module == AssistantModule.CALENDAR
        val lead = if (isCalendar) (task.amount ?: 0.0).toInt().coerceAtLeast(0) else 0
        val title = if (isCalendar) {
            if (lead > 0) ReminderText.pick(context, "تذكير بموعد: ${task.title}", "Upcoming: ${task.title}", "Rappel : ${task.title}")
            else ReminderText.pick(context, "حان الآن موعد: ${task.title}", "Now: ${task.title}", "Maintenant : ${task.title}")
        } else {
            ReminderText.pick(context, "تذكير بمهمة: ${task.title}", "Task reminder: ${task.title}", "Rappel de tâche : ${task.title}")
        }
        val fallback = if (isCalendar) buildCalendarMessage(context, task, lead)
            else ReminderText.pick(context, "حان موعد المهمة", "Your task is due", "Votre tâche est prévue")
        val body = if (isCalendar) fallback else task.details.ifBlank { fallback }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ab_logo_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(Settings.System.DEFAULT_ALARM_ALERT_URI)
            .setVibrate(longArrayOf(0, 700, 350, 700, 350, 1000))
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        task.dueAt?.let { ReminderLedger.markNotified(context, id, it) }
    }

    private fun buildCalendarMessage(context: Context, task: AssistantEntry, lead: Int): String {
        val start = task.dueAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }.orEmpty()
        val location = task.details.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
        val minutesLeft = task.dueAt?.let { ((it - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0L) } ?: lead.toLong()
        return if (minutesLeft > 0) ReminderText.pick(context, "بعد $minutesLeft دقيقة: $start$location", "In $minutesLeft min: $start$location", "Dans $minutesLeft min : $start$location")
            else ReminderText.pick(context, "حان الآن موعدك: $start$location", "Starting now: $start$location", "Maintenant : $start$location")
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val alarmSound = Settings.System.DEFAULT_ALARM_ALERT_URI
            val audio = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, ReminderText.pick(context, "منبّه المهام والمواعيد", "Task and event alarms", "Alarmes tâches et rendez-vous"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ReminderText.pick(context, "منبّه صوتي واهتزاز للمهام والمواعيد", "Sound and vibration for tasks and events", "Son et vibration pour tâches et rendez-vous")
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 350, 700, 350, 1000)
                setSound(alarmSound, audio)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        private const val CHANNEL_ID = "assistant_tasks_alarm_v3"
    }
}
