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
            ?.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ar")))
            .orEmpty()
        val days = DocumentRenewalCodec.daysUntilExpiry(entry)
        val body = when {
            days == null -> "راجع بيانات التجديد"
            days < 0 -> "انتهت صلاحية المستند — يرجى التجديد"
            days == 0L -> "تنتهي صلاحية المستند اليوم"
            else -> "متبقي " + days + " يوم — تاريخ الانتهاء " + dateText
        }
        val openApp = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ab_logo_icon)
            .setContentTitle("تجديد قريب: " + entry.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "تنبيهات تجديد المستندات", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "تنبيه قبل انتهاء المستندات والتجديدات"
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
