package com.abfilepro.app.assistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.abfilepro.app.MainActivity
import com.abfilepro.app.R

/**
 * P27: a home-screen widget with the count of what is left today and the next three tasks,
 * appointments or bills. Medicines and health appointments are deliberately not named on a screen
 * that can be seen without unlocking; they only add to the count.
 */
class TodayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, TodayWidget.views(context)) }
    }
}

object TodayWidget {
    /** Redraws every placed widget; called when the assistant opens and each morning. */
    fun refresh(context: Context) {
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
            ids.forEach { manager.updateAppWidget(it, views(context)) }
        }
    }

    internal fun views(context: Context): RemoteViews {
        val agenda = runCatching {
            TodayAgendaBuilder.build(AssistantRepository(context).loadEntries(), BillReminderRecords.load(context), System.currentTimeMillis())
        }.getOrNull()
        val views = RemoteViews(context.packageName, R.layout.widget_today)
        val title = ReminderText.pick(context, "يومي", "My day", "Ma journée")
        val pending = agenda?.let { it.overdue.size + it.pendingToday } ?: 0
        views.setTextViewText(
            R.id.widget_today_title,
            if (pending == 0) ReminderText.pick(context, "$title — مفيش حاجة النهارده", "$title — all clear", "$title — rien aujourd'hui")
            else ReminderText.pick(context, "$title — $pending مستني", "$title — $pending to do", "$title — $pending à faire"),
        )
        val named = agenda?.let { (it.overdue + it.today).filter { row -> !row.done && row.kind != TodayKind.MEDICINE && row.kind != TodayKind.HEALTH_APPOINTMENT } }.orEmpty()
        listOf(R.id.widget_today_line1, R.id.widget_today_line2, R.id.widget_today_line3).forEachIndexed { index, id ->
            views.setTextViewText(id, named.getOrNull(index)?.title?.let { "• $it" } ?: "")
        }
        val open = PendingIntent.getActivity(
            context, "today-widget".hashCode(), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_today_root, open)
        return views
    }
}
