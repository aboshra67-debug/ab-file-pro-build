package com.abfilepro.app.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dependency-free exchange-rate client for the calculator currency tab.
 *
 * Primary source: ExchangeRate.fun (hourly updates, no API key).
 * Fallback source: open ExchangeRate-API feed.
 *
 * The latest successful response is cached locally so conversion keeps working
 * offline. The calculator never blocks on a stale cache when a usable saved
 * snapshot exists.
 */
data class CurrencyRatesSnapshot(
    val rates: Map<String, Double>,
    val providerUpdatedAtMillis: Long,
    val fetchedAtMillis: Long,
    val fromCache: Boolean,
    val providerName: String,
    val providerDate: String? = null,
    val usedFallbackProvider: Boolean = false,
) {
    fun convert(amount: Double, from: String, to: String): Double? {
        if (!amount.isFinite()) return null
        if (from == to) return amount
        val fromRate = rates[from] ?: return null
        val toRate = rates[to] ?: return null
        if (fromRate <= 0.0 || toRate <= 0.0) return null
        return amount / fromRate * toRate
    }
}

object CurrencyRateService {
    private const val PRIMARY_ENDPOINT = "https://api.exchangerate.fun/latest?base=USD"
    private const val FALLBACK_ENDPOINT = "https://open.er-api.com/v6/latest/USD"
    private const val PRIMARY_PROVIDER = "ExchangeRate.fun"
    private const val FALLBACK_PROVIDER = "ExchangeRate-API"

    private const val PREFS = "ab_file_pro_currency_rates"
    private const val KEY_JSON = "latest_json"
    private const val KEY_FETCHED = "fetched_at"
    private const val KEY_PROVIDER = "provider"
    private const val CACHE_MAX_AGE_MS = 15L * 60L * 1000L // re-check every 15 minutes

    suspend fun load(context: Context, forceRefresh: Boolean = false): CurrencyRatesSnapshot = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_JSON, null)
        val cachedAt = prefs.getLong(KEY_FETCHED, 0L)
        val cachedProvider = prefs.getString(KEY_PROVIDER, null)
        val now = System.currentTimeMillis()

        if (!forceRefresh && cached != null && now - cachedAt < CACHE_MAX_AGE_MS) {
            return@withContext parseAuto(cached, cachedAt, fromCache = true, providerHint = cachedProvider)
        }

        val primary = runCatching { fetch(PRIMARY_ENDPOINT) }
        if (primary.isSuccess) {
            val body = primary.getOrThrow()
            val snapshot = parsePrimary(body, now, fromCache = false)
            prefs.edit()
                .putString(KEY_JSON, body)
                .putLong(KEY_FETCHED, now)
                .putString(KEY_PROVIDER, PRIMARY_PROVIDER)
                .apply()
            return@withContext snapshot
        }

        val fallback = runCatching { fetch(FALLBACK_ENDPOINT) }
        if (fallback.isSuccess) {
            val body = fallback.getOrThrow()
            val snapshot = parseFallback(body, now, fromCache = false)
            prefs.edit()
                .putString(KEY_JSON, body)
                .putLong(KEY_FETCHED, now)
                .putString(KEY_PROVIDER, FALLBACK_PROVIDER)
                .apply()
            return@withContext snapshot
        }

        if (cached != null) {
            return@withContext parseAuto(cached, cachedAt, fromCache = true, providerHint = cachedProvider)
        }

        throw primary.exceptionOrNull() ?: fallback.exceptionOrNull() ?: IllegalStateException("currency_network_error")
    }

    private fun fetch(endpoint: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AB-File-Pro-Currency/1.1")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAuto(json: String, fetchedAt: Long, fromCache: Boolean, providerHint: String?): CurrencyRatesSnapshot {
        val root = JSONObject(json)
        return when {
            providerHint == PRIMARY_PROVIDER || (root.has("base") && root.has("date") && root.has("rates") && !root.has("result")) ->
                parsePrimary(json, fetchedAt, fromCache)
            else -> parseFallback(json, fetchedAt, fromCache)
        }
    }

    private fun parsePrimary(json: String, fetchedAt: Long, fromCache: Boolean): CurrencyRatesSnapshot {
        val root = JSONObject(json)
        val base = root.optString("base", "USD").uppercase()
        if (!root.has("rates")) error("currency_api_error")
        val rates = parseRates(root.getJSONObject("rates"))
        if (base == "USD") rates.putIfAbsent("USD", 1.0)
        return CurrencyRatesSnapshot(
            rates = rates,
            providerUpdatedAtMillis = 0L,
            fetchedAtMillis = fetchedAt,
            fromCache = fromCache,
            providerName = PRIMARY_PROVIDER,
            providerDate = root.optString("date").takeIf { it.isNotBlank() },
            usedFallbackProvider = false,
        )
    }

    private fun parseFallback(json: String, fetchedAt: Long, fromCache: Boolean): CurrencyRatesSnapshot {
        val root = JSONObject(json)
        if (root.optString("result") != "success") error(root.optString("error-type", "currency_api_error"))
        val rates = parseRates(root.getJSONObject("rates"))
        rates.putIfAbsent("USD", 1.0)
        return CurrencyRatesSnapshot(
            rates = rates,
            providerUpdatedAtMillis = root.optLong("time_last_update_unix", 0L).let { if (it > 0L) it * 1000L else 0L },
            fetchedAtMillis = fetchedAt,
            fromCache = fromCache,
            providerName = FALLBACK_PROVIDER,
            providerDate = null,
            usedFallbackProvider = true,
        )
    }

    private fun parseRates(ratesObject: JSONObject): LinkedHashMap<String, Double> {
        val rates = LinkedHashMap<String, Double>()
        val iterator = ratesObject.keys()
        while (iterator.hasNext()) {
            val code = iterator.next().uppercase()
            val value = ratesObject.optDouble(code, Double.NaN)
            if (value.isFinite() && value > 0.0) rates[code] = value
        }
        return rates
    }
}
