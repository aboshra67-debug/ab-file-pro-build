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
import org.json.JSONArray
import java.util.Calendar

/**
 * P25: bill reminders now really ring. Before P25 the bill screen only stored a due date and
 * showed it inside the screen; no alarm was ever set, so a bill could be missed with the app closed.
 *
 * Two alarms per bill, both at 09:00: the day before the due date ("tomorrow") and on the due date.
 * The same rules as every other reminder apply: exact alarm when allowed, otherwise an inexact one
 * (late, never missing), and a reminder whose time already passed fires once right away instead of never.
 */
object BillReminderPolicy {
    const val DAY_MS = 24L * 60 * 60 * 1000
    private const val REMIND_HOUR = 9
    /** The "due today" reminder is still useful until the evening of the due day. */
    private const val DUE_DAY_USEFUL_UNTIL_HOUR = 21

    enum class Kind(val key: String) { TOMORROW("pre"), TODAY("due") }

    /** 09:00 of the due date, in the phone's time zone. */
    fun reminderMoment(dueAt: Long): Long = Calendar.getInstance().apply {
        timeInMillis = dueAt
        set(Calendar.HOUR_OF_DAY, REMIND_HOUR)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * When to ring for [kind], or null when nothing should be scheduled. [notifiedFor] is the due
     * moment this kind already rang for (so a reboot never repeats it). Pure; JVM-tested.
     */
    fun triggerFor(kind: Kind, dueAt: Long, now: Long, notifiedFor: Long?): Long? {
        val moment = reminderMoment(dueAt)
        return when (kind) {
            Kind.TOMORROW -> ReminderPolicy.triggerFor(moment, DAY_MS, now, notifiedFor)
            Kind.TODAY -> {
                val wanted = moment
                val usefulUntil = Calendar.getInstance().apply {
                    timeInMillis = moment
                    set(Calendar.HOUR_OF_DAY, DUE_DAY_USEFUL_UNTIL_HOUR)
                }.timeInMillis
                when {
                    wanted > now -> wanted
                    now >= usefulUntil -> null
                    notifiedFor == moment -> null
                    else -> now + ReminderPolicy.IMMEDIATE_DELAY_MS
                }
            }
        }
    }
}

/** A stored bill reminder (same JSON as the bill screen has always written). */
data class BillReminderRecord(val id: Long, val type: String, val dueAt: Long, val account: String)

internal object BillReminderRecords {
    const val PREFS = "ab_file_pro_bill_reminders"
    const val KEY = "items_v1"

    fun load(context: Context): List<BillReminderRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val due = o.optLong("due", 0L)
                    if (due <= 0L) continue
                    add(BillReminderRecord(o.optLong("id", 0L), o.optString("type"), due, o.optString("account", "")))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun find(context: Context, id: Long): BillReminderRecord? = load(context).firstOrNull { it.id == id }
}

object BillAlarmScheduler {
    fun sync(context: Context, id: Long, dueAt: Long) {
        cancel(context, id)
        BillReminderPolicy.Kind.entries.forEach { kind ->
            val trigger = BillReminderPolicy.triggerFor(kind, dueAt, System.currentTimeMillis(), ReminderLedger.notifiedFor(context, ledgerKey(id, kind)))
                ?: return@forEach
            val operation = pendingIntent(context, id, kind, PendingIntent.FLAG_UPDATE_CURRENT) ?: return@forEach
            ReminderAlarms.set(context, trigger, operation)
        }
    }

    fun cancel(context: Context, id: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        BillReminderPolicy.Kind.entries.forEach { kind ->
            pendingIntent(context, id, kind, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
        }
    }

    fun rescheduleAll(context: Context) {
        BillReminderRecords.load(context).forEach { sync(context, it.id, it.dueAt) }
    }

    /** Test/diagnostic hook: is this kind of reminder pending for the bill? */
    internal fun isScheduled(context: Context, id: Long, kind: BillReminderPolicy.Kind): Boolean =
        pendingIntent(context, id, kind, PendingIntent.FLAG_NO_CREATE) != null

    internal fun ledgerKey(id: Long, kind: BillReminderPolicy.Kind) = "bill:$id:${kind.key}"

    private fun pendingIntent(context: Context, id: Long, kind: BillReminderPolicy.Kind, mode: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            "bill:$id:${kind.key}".hashCode(),
            Intent(context, BillAlarmReceiver::class.java)
                .putExtra(BillAlarmReceiver.EXTRA_ID, id)
                .putExtra(BillAlarmReceiver.EXTRA_KIND, kind.key),
            mode or PendingIntent.FLAG_IMMUTABLE,
        )
}

class BillAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val kind = BillReminderPolicy.Kind.entries.firstOrNull { it.key == intent.getStringExtra(EXTRA_KIND) } ?: return
        // A bill deleted after the alarm was set is simply gone.
        val bill = BillReminderRecords.find(context, id) ?: return
        createChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val label = typeLabel(context, bill.type)
        val title = when (kind) {
            BillReminderPolicy.Kind.TOMORROW -> ReminderText.pick(context, "فاتورة $label تستحق غدًا", "$label bill is due tomorrow", "Facture $label due demain")
            BillReminderPolicy.Kind.TODAY -> ReminderText.pick(context, "فاتورة $label مستحقة اليوم", "$label bill is due today", "Facture $label due aujourd'hui")
        }
        val body = bill.account.takeIf { it.isNotBlank() }
            ?: ReminderText.pick(context, "افتح تطبيق الدفع لإتمام الدفع", "Open your payment app to pay", "Ouvrez votre application de paiement")
        val openApp = PendingIntent.getActivity(
            context, id.hashCode(), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ab_logo_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        NotificationManagerCompat.from(context).notify(BillAlarmScheduler.ledgerKey(id, kind).hashCode(), notification)
        ReminderLedger.markNotified(context, BillAlarmScheduler.ledgerKey(id, kind), BillReminderPolicy.reminderMoment(bill.dueAt))
    }

    private fun typeLabel(context: Context, type: String): String = when (type) {
        "ELECTRICITY" -> ReminderText.pick(context, "الكهرباء", "electricity", "d'électricité")
        "WATER" -> ReminderText.pick(context, "المياه", "water", "d'eau")
        "GAS" -> ReminderText.pick(context, "الغاز", "gas", "de gaz")
        "INTERNET" -> ReminderText.pick(context, "الإنترنت", "internet", "internet")
        "PHONE" -> ReminderText.pick(context, "الهاتف", "phone", "de téléphone")
        "MOBILE" -> ReminderText.pick(context, "المحمول", "mobile", "mobile")
        else -> ReminderText.pick(context, "", "", "")
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ReminderText.pick(context, "تذكير الفواتير", "Bill reminders", "Rappels de factures"),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = ReminderText.pick(context, "تنبيه قبل موعد دفع الفاتورة", "Alert before a bill is due", "Alerte avant l'échéance d'une facture")
                }
            )
        }
    }

    companion object {
        const val EXTRA_ID = "bill_id"
        const val EXTRA_KIND = "bill_kind"
        private const val CHANNEL_ID = "assistant_bills_v1"
    }
}
