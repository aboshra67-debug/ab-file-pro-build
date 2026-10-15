package com.abfilepro.app.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Small dependency-free client for the live XAU/USD spot reference.
 *
 * Source: https://api.gold-api.com/price/XAU (no API key required).
 * The latest successful response is cached locally so the market screen can keep
 * showing the last known reference value when the device is temporarily offline.
 */
data class GoldPriceSnapshot(
    val usdPerTroyOunce: Double,
    val providerUpdatedAtMillis: Long,
    val fetchedAtMillis: Long,
    val fromCache: Boolean,
    val providerName: String = "Gold API",
) {
    fun egpPerGram(egpPerUsd: Double, karat: Int): Double? {
        if (!usdPerTroyOunce.isFinite() || usdPerTroyOunce <= 0.0) return null
        if (!egpPerUsd.isFinite() || egpPerUsd <= 0.0) return null
        if (karat !in setOf(24, 21, 18)) return null
        val pureGram = usdPerTroyOunce * egpPerUsd / TROY_OUNCE_GRAMS
        return pureGram * (karat.toDouble() / 24.0)
    }

    companion object {
        private const val TROY_OUNCE_GRAMS = 31.1034768
    }
}

object GoldPriceService {
    private const val ENDPOINT = "https://api.gold-api.com/price/XAU"
    private const val PREFS = "ab_file_pro_gold_price"
    private const val KEY_JSON = "latest_json"
    private const val KEY_FETCHED = "fetched_at"
    private const val CACHE_MAX_AGE_MS = 15L * 60L * 1000L

    suspend fun load(context: Context, forceRefresh: Boolean = false): GoldPriceSnapshot = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_JSON, null)
        val cachedAt = prefs.getLong(KEY_FETCHED, 0L)
        val now = System.currentTimeMillis()

        if (!forceRefresh && cached != null && now - cachedAt < CACHE_MAX_AGE_MS) {
            return@withContext parse(cached, cachedAt, fromCache = true)
        }

        val live = runCatching { fetch(ENDPOINT) }
        if (live.isSuccess) {
            val body = live.getOrThrow()
            val snapshot = parse(body, now, fromCache = false)
            prefs.edit().putString(KEY_JSON, body).putLong(KEY_FETCHED, now).apply()
            return@withContext snapshot
        }

        if (cached != null) return@withContext parse(cached, cachedAt, fromCache = true)
        throw live.exceptionOrNull() ?: IllegalStateException("gold_network_error")
    }

    private fun fetch(endpoint: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AB-File-Pro-Markets/1.0")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: String, fetchedAt: Long, fromCache: Boolean): GoldPriceSnapshot {
        val root = JSONObject(json)
        val price = root.optDouble("price", Double.NaN)
        if (!price.isFinite() || price <= 0.0) error("gold_api_error")
        val providerUpdated = root.optString("updatedAt").takeIf { it.isNotBlank() }?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        } ?: 0L
        return GoldPriceSnapshot(
            usdPerTroyOunce = price,
            providerUpdatedAtMillis = providerUpdated,
            fetchedAtMillis = fetchedAt,
            fromCache = fromCache,
        )
    }
}
