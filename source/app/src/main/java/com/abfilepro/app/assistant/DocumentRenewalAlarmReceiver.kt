package com.abfilepro.app.assistant

import android.Manifest
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
import java.time.format.DateTimeFormatter
import java.util.Locale

class DocumentRenewalAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_DOCUMENT_ID).orEmpty()
        val entry = AssistantRepository(context).loadEntries().firstOrNull {
            it.id == id && it.module == AssistantModule.DOCUMENTS
        } ?: return
        if (!entry.enabled || entry.completed) return
        createChannel(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val dateText = DocumentRenewalCodec.expiryDate(entry)
            ?.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale(ReminderText.language(context))))
            .orEmpty()
        val days = DocumentRenewalCodec.daysUntilExpiry(entry)
        val body = when {
            days == null -> ReminderText.pick(context, "راجع بيانات التجديد", "Check the renewal details", "Vérifiez le renouvellement")
            days < 0 -> ReminderText.pick(context, "انتهت صلاحية المستند — يرجى التجديد", "This document has expired — please renew", "Document expiré — à renouveler")
            days == 0L -> ReminderText.pick(context, "تنتهي صلاحية المستند اليوم", "This document expires today", "Le document expire aujourd'hui")
            else -> ReminderText.pick(context, "متبقي $days يوم — تاريخ الانتهاء $dateText", "$days days left — expires $dateText", "Encore $days jours — expire le $dateText")
        }
        val openApp = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ab_logo_icon)
            .setContentTitle(ReminderText.pick(context, "تجديد قريب: ", "Renewal due: ", "Renouvellement : ") + entry.title)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ab_logo_icon)
                    .setContentTitle(ReminderText.pick(context, "تذكير تجديد مستند", "Document renewal reminder", "Rappel de renouvellement"))
                    .build()
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        entry.dueAt?.let { ReminderLedger.markNotified(context, id, it) }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, ReminderText.pick(context, "تنبيهات تجديد المستندات", "Document renewals", "Renouvellement de documents"), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = ReminderText.pick(context, "تنبيه قبل انتهاء المستندات والتجديدات", "Reminder before documents expire", "Rappel avant expiration")
                    enableVibration(true)
                }
            )
        }
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "document_id"
        private const val CHANNEL_ID = "assistant_documents_renewal_v1"
    }
}
