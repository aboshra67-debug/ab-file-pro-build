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

class HealthAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ENTRY_ID).orEmpty()
        val repo = AssistantRepository(context)
        val entry = repo.loadEntries().firstOrNull { it.id == id && it.module == AssistantModule.HEALTH } ?: return
        if (!entry.enabled || entry.completed) return
        val meta = HealthEntryCodec.parse(entry.secondary)

        // P18 (AS-01): the next daily dose is scheduled first, so a denied notification permission
        // (or any failure below) can never end the medicine reminder chain.
        if (meta.type == HealthEntryType.MEDICATION) {
            val latest = repo.loadEntries()
            val current = latest.firstOrNull { it.id == id } ?: entry
            val next = current.copy(dueAt = HealthEntryCodec.nextMedicationTrigger(meta, System.currentTimeMillis() + 1_000L))
            repo.update(latest, next)
            HealthAlarmScheduler.sync(context, next)
        }

        createChannel(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val openApp = PendingIntent.getActivity(
            context, ("health-open:" + id).hashCode(), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val medication = meta.type == HealthEntryType.MEDICATION
        val title = if (medication) ReminderText.pick(context, "موعد الدواء: ${entry.title}", "Medicine time: ${entry.title}", "Médicament : ${entry.title}")
            else ReminderText.pick(context, "موعد صحي: ${entry.title}", "Health appointment: ${entry.title}", "Rendez-vous santé : ${entry.title}")
        val body = if (medication) {
            entry.details.ifBlank { ReminderText.pick(context, "حان موعد الجرعة المسجلة", "It is time for your dose", "C'est l'heure de votre dose") }
        } else {
            val whenText = entry.dueAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }.orEmpty()
            listOf(whenText, entry.details).filter { it.isNotBlank() }.joinToString(" • ")
        }
        // P18: the medicine name is health data; the lock screen shows only a generic line.
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ab_logo_icon)
            .setContentTitle(ReminderText.pick(context, "تذكير صحي", "Health reminder", "Rappel santé"))
            .setContentText(ReminderText.pick(context, "افتح الهاتف لعرض التفاصيل", "Unlock to see details", "Déverrouillez pour voir"))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ab_logo_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
            .setVibrate(longArrayOf(0, 600, 250, 600))
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        NotificationManagerCompat.from(context).notify(("health-notify:" + id).hashCode(), notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, ReminderText.pick(context, "الأدوية والمواعيد الصحية", "Medicines and health appointments", "Médicaments et rendez-vous santé"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ReminderText.pick(context, "تنبيهات الجرعات والمواعيد الصحية المسجلة داخل AB File Pro", "Dose and appointment reminders saved in AB File Pro", "Rappels de doses et rendez-vous")
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                enableVibration(true)
                setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audio)
            })
        }
    }

    companion object {
        const val EXTRA_ENTRY_ID = "health_entry_id"
        private const val CHANNEL_ID = "assistant_health_v1"
    }
}
