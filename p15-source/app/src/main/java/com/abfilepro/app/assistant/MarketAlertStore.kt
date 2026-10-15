package com.abfilepro.app.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class MarketAsset(val storageId: String, val arabicName: String) {
    USD("usd", "الدولار الأمريكي"),
    SAR("sar", "الريال السعودي"),
    GOLD24("gold24", "الذهب عيار 24"),
    GOLD21("gold21", "الذهب عيار 21"),
    GOLD18("gold18", "الذهب عيار 18");

    companion object {
        fun fromStorageId(value: String): MarketAsset? = entries.firstOrNull { it.storageId == value }
    }
}

enum class MarketAlertDirection(val storageId: String, val arabicLabel: String) {
    ABOVE("above", "أعلى من أو يساوي"),
    BELOW("below", "أقل من أو يساوي");

    companion object {
        fun fromStorageId(value: String): MarketAlertDirection = entries.firstOrNull { it.storageId == value } ?: ABOVE
    }
}

data class MarketPriceAlert(
    val id: Long,
    val asset: MarketAsset,
    val targetEgp: Double,
    val direction: MarketAlertDirection,
    val enabled: Boolean = true,
    val triggered: Boolean = false,
) {
    fun conditionMet(currentEgp: Double): Boolean = when (direction) {
        MarketAlertDirection.ABOVE -> currentEgp >= targetEgp
        MarketAlertDirection.BELOW -> currentEgp <= targetEgp
    }
}

object MarketAlertStore {
    private const val PREFS = "ab_file_pro_market_alerts"
    private const val KEY_ALERTS = "alerts_v1"
    private const val KEY_FAVORITES = "favorites_v1"

    fun load(context: Context): List<MarketPriceAlert> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ALERTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val asset = MarketAsset.fromStorageId(obj.optString("asset")) ?: continue
                    val target = obj.optDouble("target", Double.NaN)
                    if (!target.isFinite() || target <= 0.0) continue
                    add(
                        MarketPriceAlert(
                            id = obj.optLong("id", 0L).takeIf { it > 0L } ?: System.currentTimeMillis() + i,
                            asset = asset,
                            targetEgp = target,
                            direction = MarketAlertDirection.fromStorageId(obj.optString("direction")),
                            enabled = obj.optBoolean("enabled", true),
                            triggered = obj.optBoolean("triggered", false),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, alerts: List<MarketPriceAlert>, schedule: Boolean = true) {
        val array = JSONArray()
        alerts.forEach { alert ->
            array.put(
                JSONObject()
                    .put("id", alert.id)
                    .put("asset", alert.asset.storageId)
                    .put("target", alert.targetEgp)
                    .put("direction", alert.direction.storageId)
                    .put("enabled", alert.enabled)
                    .put("triggered", alert.triggered)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ALERTS, array.toString()).apply()
        if (schedule) MarketAlertScheduler.sync(context)
    }

    fun upsert(context: Context, alert: MarketPriceAlert) {
        val current = load(context).toMutableList()
        val index = current.indexOfFirst { it.id == alert.id }
        if (index >= 0) current[index] = alert else current.add(alert)
        save(context, current)
    }

    fun delete(context: Context, id: Long) = save(context, load(context).filterNot { it.id == id })

    fun favorites(context: Context): Set<MarketAsset> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_FAVORITES)) return setOf(MarketAsset.USD, MarketAsset.SAR, MarketAsset.GOLD21)
        return prefs.getStringSet(KEY_FAVORITES, emptySet())
            .orEmpty()
            .mapNotNull(MarketAsset::fromStorageId)
            .toSet()
    }

    fun saveFavorites(context: Context, favorites: Set<MarketAsset>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_FAVORITES, favorites.map { it.storageId }.toSet())
            .apply()
    }
}

fun marketPriceEgp(
    asset: MarketAsset,
    currency: CurrencyRatesSnapshot?,
    gold: GoldPriceSnapshot?,
): Double? {
    return when (asset) {
        MarketAsset.USD -> currency?.convert(1.0, "USD", "EGP")
        MarketAsset.SAR -> currency?.convert(1.0, "SAR", "EGP")
        MarketAsset.GOLD24 -> gold?.egpPerGram(currency?.convert(1.0, "USD", "EGP") ?: return null, 24)
        MarketAsset.GOLD21 -> gold?.egpPerGram(currency?.convert(1.0, "USD", "EGP") ?: return null, 21)
        MarketAsset.GOLD18 -> gold?.egpPerGram(currency?.convert(1.0, "USD", "EGP") ?: return null, 18)
    }?.takeIf { it.isFinite() && it > 0.0 }
}
