package com.abfilepro.app.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TaskRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { RecurrenceEngine.advanceOverdue(context) }
        runCatching { MoneyRecurringEngine.run(context) }
        TaskAlarmScheduler.rescheduleAll(context)
        TodayBriefingScheduler.sync(context)
        HealthAlarmScheduler.rescheduleAll(context)
        DocumentRenewalAlarmScheduler.rescheduleAll(context)
        BillAlarmScheduler.rescheduleAll(context)
    }
}
