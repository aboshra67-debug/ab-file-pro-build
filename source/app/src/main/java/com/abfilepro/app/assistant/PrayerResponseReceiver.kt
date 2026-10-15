package com.abfilepro.app.assistant

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate

class PrayerResponseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prayer = PrayerName.fromStorageId(intent.getStringExtra(EXTRA_PRAYER).orEmpty()) ?: return
        val date = runCatching { LocalDate.parse(intent.getStringExtra(EXTRA_DATE)) }.getOrNull() ?: LocalDate.now()
        val prayed = intent.getBooleanExtra(EXTRA_PRAYED, false)
        PrayerCompletionStore(context).mark(date, prayer, prayed)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.cancel(PrayerAlarmReceiver.questionNotificationId(prayer))
        if (prayed) {
            PrayerAlarmScheduler.cancelQuestion(context, prayer, date)
        } else {
            val settings = AssistantRepository(context).loadPrayerSettings()
            PrayerAlarmScheduler.scheduleNudge(context, prayer, date, settings.repeatNudgeMinutes)
        }
    }

    companion object {
        const val EXTRA_PRAYER = "prayer_response_prayer"
        const val EXTRA_DATE = "prayer_response_date"
        const val EXTRA_PRAYED = "prayer_response_prayed"
    }
}
