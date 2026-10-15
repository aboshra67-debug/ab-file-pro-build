package com.abfilepro.app.assistant

import android.Manifest
import android.app.Notification
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
import androidx.core.content.ContextCompat
import com.abfilepro.app.MainActivity
import com.abfilepro.app.R
import java.time.LocalDate

class PrayerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prayer = PrayerName.fromStorageId(intent.getStringExtra(EXTRA_PRAYER).orEmpty()) ?: return
        val event = intent.getStringExtra(EXTRA_EVENT).orEmpty()
        val date = runCatching { LocalDate.parse(intent.getStringExtra(EXTRA_DATE)) }.getOrNull() ?: LocalDate.now()
        when (event) {
            EVENT_PRE -> showPreAlert(context, prayer)
            EVENT_ADHAN -> showAdhan(context, prayer)
            EVENT_CHECK, EVENT_NUDGE -> showPrayerQuestion(context, prayer, date)
        }
    }

    private fun showPreAlert(context: Context, prayer: PrayerName) {
        if (!canNotify(context)) return
        val settings = AssistantRepository(context).loadPrayerSettings()
        createChannels(context, settings)
        val minutes = settings.preAlertMinutes.coerceAtLeast(1)
        val name = arabicPrayerName(prayer)
        val body = "فاضل على صلاة $name $minutes دقائق — استعد للصلاة"
        val notification = NotificationCompat.Builder(context, PRE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ab_logo_icon)
            .setContentTitle("اقترب موعد صلاة $name")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, 9_100 + prayer.ordinal))
            .build()
        (context.getSystemService(NotificationManager::class.java) ?: return).notify(NOTIFICATION_BASE + prayer.ordinal * 10, notification)
    }

    private fun showAdhan(context: Context, prayer: PrayerName) {
        if (!canNotify(context)) return
        val settings = AssistantRepository(context).loadPrayerSettings()
        createChannels(context, settings)
        val name = arabicPrayerName(prayer)
        val body = "حان الآن موعد صلاة $name"
        val notification = NotificationCompat.Builder(context, ADHAN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ab_logo_icon)
            .setContentTitle("حان الآن موعد صلاة $name")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(Settings.System.DEFAULT_ALARM_ALERT_URI)
            .setVibrate(longArrayOf(0, 1000, 350, 1000, 350, 1400))
            .setContentIntent(openAppIntent(context, 9_200 + prayer.ordinal))
            .setAutoCancel(true)
            .build()
        (context.getSystemService(NotificationManager::class.java) ?: return).notify(NOTIFICATION_BASE + prayer.ordinal * 10 + 1, notification)
    }

    private fun showPrayerQuestion(context: Context, prayer: PrayerName, date: LocalDate) {
        if (!canNotify(context)) return
        val store = PrayerCompletionStore(context)
        if (store.answer(date, prayer) == true) return
        val settings = AssistantRepository(context).loadPrayerSettings()
        createChannels(context, settings)
        val name = arabicPrayerName(prayer)
        val yesIntent = responseIntent(context, prayer, date, true)
        val noIntent = responseIntent(context, prayer, date, false)
        val body = "هل صليت $name؟ لازم تختار نعم أو لا. لو ما رديتش هفكّرك تاني."
        val notification = NotificationCompat.Builder(context, QUESTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ab_logo_icon)
            .setContentTitle("هل صليت $name؟")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(0, "نعم، صليت", yesIntent)
            .addAction(0, "لا", noIntent)
            .setContentIntent(openAppIntent(context, 9_300 + prayer.ordinal))
            .build()
        (context.getSystemService(NotificationManager::class.java) ?: return).notify(questionNotificationId(prayer), notification)
        PrayerAlarmScheduler.scheduleNudge(context, prayer, date, settings.repeatNudgeMinutes)
    }

    private fun responseIntent(context: Context, prayer: PrayerName, date: LocalDate, prayed: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            12_000 + prayer.ordinal * 2 + if (prayed) 1 else 0,
            Intent(context, PrayerResponseReceiver::class.java)
                .putExtra(PrayerResponseReceiver.EXTRA_PRAYER, prayer.storageId)
                .putExtra(PrayerResponseReceiver.EXTRA_DATE, date.toString())
                .putExtra(PrayerResponseReceiver.EXTRA_PRAYED, prayed),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openAppIntent(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannels(context: Context, settings: PrayerSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(PRE_CHANNEL_ID, "تنبيه قبل الصلاة", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "تنبيه قبل موعد الصلاة"
            enableVibration(true)
        })
        manager.createNotificationChannel(NotificationChannel(QUESTION_CHANNEL_ID, "متابعة الصلاة", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "سؤال هل تمت الصلاة مع تذكير متكرر"
            enableVibration(true)
        })
        val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        manager.createNotificationChannel(NotificationChannel(ADHAN_CHANNEL_ID, "تنبيه موعد الصلاة", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "تنبيه صوتي عند دخول وقت الصلاة باستخدام نغمة المنبّه في الهاتف"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 350, 1000, 350, 1400)
            setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, audio)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    private fun canNotify(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_PRAYER = "assistant_prayer"
        const val EXTRA_EVENT = "assistant_prayer_event"
        const val EXTRA_DATE = "assistant_prayer_date"
        const val EVENT_PRE = "pre"
        const val EVENT_ADHAN = "adhan"
        const val EVENT_CHECK = "check"
        const val EVENT_NUDGE = "nudge"
        private const val PRE_CHANNEL_ID = "assistant_prayer_pre_v2"
        private const val ADHAN_CHANNEL_ID = "assistant_prayer_time_v3"
        private const val QUESTION_CHANNEL_ID = "assistant_prayer_question_v2"
        private const val NOTIFICATION_BASE = 8_500

        fun questionNotificationId(prayer: PrayerName): Int = NOTIFICATION_BASE + prayer.ordinal * 10 + 2
        fun arabicPrayerName(prayer: PrayerName): String = when (prayer) {
            PrayerName.FAJR -> "الفجر"
            PrayerName.SUNRISE -> "الشروق"
            PrayerName.DHUHR -> "الظهر"
            PrayerName.ASR -> "العصر"
            PrayerName.MAGHRIB -> "المغرب"
            PrayerName.ISHA -> "العشاء"
        }
    }
}
