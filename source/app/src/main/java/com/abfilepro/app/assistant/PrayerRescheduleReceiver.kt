package com.abfilepro.app.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PrayerRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PrayerAlarmScheduler.reschedule(context)
    }
}
