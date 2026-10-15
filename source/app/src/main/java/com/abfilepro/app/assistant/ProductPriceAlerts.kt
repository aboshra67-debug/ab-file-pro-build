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
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ProductPriceAlert(
    val id: String = UUID.randomUUID().toString(),
    val query: String,
    val targetEgp: Double,
    val enabled: Boolean = true,
    val triggered: Boolean = false,
)

object ProductPriceAlertStore {
    private const val PREFS = "ab_product_price_alerts"
    private const val KEY_ITEMS = "items"

    fun load(context: Context): List<ProductPriceAlert> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val query = o.optString("query").trim()
                    val target = o.optDouble("targetEgp", Double.NaN)
                    if (query.isBlank() || !target.isFinite() || target <= 0.0) continue
                    add(ProductPriceAlert(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        query = query,
                        targetEgp = target,
                        enabled = o.optBoolean("enabled", true),
                        triggered = o.optBoolean("triggered", false),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, items: List<ProductPriceAlert>, schedule: Boolean = true) {
        val array = JSONArray()
        items.take(20).forEach { a ->
            array.put(JSONObject().apply {
                put("id", a.id)
                put("query", a.query)
                put("targetEgp", a.targetEgp)
                put("enabled", a.enabled)
                put("triggered", a.triggered)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, array.toString()).apply()
        if (schedule) ProductPriceAlertScheduler.sync(context)
    }

    fun add(context: Context, query: String, targetEgp: Double): ProductPriceAlert {
        val alert = ProductPriceAlert(query = query.trim().take(180), targetEgp = targetEgp)
        save(context, listOf(alert) + load(context).filterNot { it.query.equals(alert.query, true) && kotlin.math.abs(it.targetEgp - targetEgp) < 0.01 })
        return alert
    }

    fun delete(context: Context, id: String) = save(context, load(context).filterNot { it.id == id })
}

class ProductPriceAlertWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val alerts = ProductPriceAlertStore.load(applicationContext)
        if (alerts.none { it.enabled }) return Result.success()
        var changed = false
        val updated = alerts.map { alert ->
            if (!alert.enabled) return@map alert
            val snapshot = runCatching { ProductPriceComparisonService.search(alert.query) }.getOrNull() ?: return@map alert
            val cheapest = snapshot.cheapest?.priceEgp ?: return@map alert
            val met = cheapest <= alert.targetEgp
            when {
                met && !alert.triggered -> {
                    if (!notify(alert, cheapest, snapshot.cheapest?.store?.arabicName.orEmpty())) return@map alert
                    changed = true
                    alert.copy(triggered = true)
                }
                !met && alert.triggered -> {
                    changed = true
                    alert.copy(triggered = false)
                }
                else -> alert
            }
        }
        if (changed) ProductPriceAlertStore.save(applicationContext, updated, schedule = false)
        return Result.success()
    }

    /** P18 (MKT-01): returns false when the alert could not be shown, so it is retried later. */
    private fun notify(alert: ProductPriceAlert, current: Double, store: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return false
        ensureChannel()
        val pending = PendingIntent.getActivity(
            applicationContext,
            alert.id.hashCode(),
            Intent(applicationContext, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nf = NumberFormat.getNumberInstance(Locale("ar", "EG")).apply { maximumFractionDigits = 2 }
        val body = "${alert.query}: ${nf.format(current)} ج.م${if (store.isNotBlank()) " — $store" else ""}"
        NotificationManagerCompat.from(applicationContext).notify(
            alert.id.hashCode(),
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ab_logo_icon)
                .setContentTitle("وصل المنتج للسعر المطلوب")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build(),
        )
        return true
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "تنبيهات أسعار المنتجات", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "تنبيه عند ظهور سعر متاح أقل من أو يساوي السعر الذي حددته"
        })
    }

    companion object { private const val CHANNEL_ID = "ab_product_price_alerts" }
}

object ProductPriceAlertScheduler {
    private const val UNIQUE_WORK = "ab_product_price_alert_worker"

    fun sync(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (ProductPriceAlertStore.load(context).none { it.enabled }) {
            manager.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<ProductPriceAlertWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
