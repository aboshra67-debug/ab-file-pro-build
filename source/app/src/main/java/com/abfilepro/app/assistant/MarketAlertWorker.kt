package com.abfilepro.app.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.abfilepro.app.MainActivity
import com.abfilepro.app.R
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MarketAlertWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val alerts = MarketAlertStore.load(applicationContext)
        if (alerts.none { it.enabled }) return Result.success()

        val currency = runCatching { CurrencyRateService.load(applicationContext, forceRefresh = true) }.getOrNull()
            ?: return Result.retry()
        val goldNeeded = alerts.any { it.asset in setOf(MarketAsset.GOLD24, MarketAsset.GOLD21, MarketAsset.GOLD18) }
        val gold = if (goldNeeded) runCatching { GoldPriceService.load(applicationContext, forceRefresh = true) }.getOrNull() else null
        if (goldNeeded && gold == null) return Result.retry()

        val now = System.currentTimeMillis()
        if (now - currency.fetchedAtMillis > MAX_STALE_MS) return Result.retry()
        if (goldNeeded && gold != null && now - gold.fetchedAtMillis > MAX_STALE_MS) return Result.retry()

        var changed = false
        val updated = alerts.map { alert ->
            if (!alert.enabled) return@map alert
            val current = marketPriceEgp(alert.asset, currency, gold) ?: return@map alert
            val met = alert.conditionMet(current)
            when {
                met && !alert.triggered -> {
                    // P18 (MKT-01): an alert counts as delivered only if it was really shown.
                    if (notifyAlert(alert, current)) {
                        changed = true
                        alert.copy(triggered = true)
                    } else alert
                }
                !met && alert.triggered -> {
                    changed = true
                    alert.copy(triggered = false)
                }
                else -> alert
            }
        }
        if (changed) MarketAlertStore.save(applicationContext, updated, schedule = false)
        return Result.success()
    }

    private fun notifyAlert(alert: MarketPriceAlert, current: Double): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return false
        ensureChannel()
        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            alert.id.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nf = NumberFormat.getNumberInstance(Locale("ar", "EG")).apply { maximumFractionDigits = 2 }
        val body = "${alert.asset.arabicName}: ${nf.format(current)} ج.م — ${alert.direction.arabicLabel} ${nf.format(alert.targetEgp)} ج.م"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ab_logo_icon)
            .setContentTitle("تنبيه سعر من AB File Pro")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(alert.id.hashCode(), notification)
        return true
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "تنبيهات أسعار العملات والذهب", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "تنبيه عند وصول السعر المرجعي إلى المستوى الذي حددته"
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "ab_market_price_alerts"
        private const val MAX_STALE_MS = 2L * 60L * 60L * 1000L
    }
}

object MarketAlertScheduler {
    private const val UNIQUE_WORK = "ab_market_price_alert_worker"

    fun sync(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (MarketAlertStore.load(context).none { it.enabled }) {
            manager.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<MarketAlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
