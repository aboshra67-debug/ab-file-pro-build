package com.abfilepro.app.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Best-effort, dependency-free product search used by the price-comparison module.
 *
 * We only show a price when it is actually present in the retailer search response.
 * If a retailer blocks automated search or changes its markup, the result remains
 * available as a direct search link without inventing a price.
 */
enum class RetailStore(
    val arabicName: String,
    val host: String,
) {
    AMAZON("أمازون مصر", "https://www.amazon.eg"),
    NOON("نون", "https://www.noon.com"),
    JUMIA("جوميا", "https://www.jumia.com.eg");

    fun searchUrl(query: String): String {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.toString())
        return when (this) {
            AMAZON -> "$host/s?k=$encoded"
            NOON -> "$host/egypt-ar/search?q=$encoded"
            JUMIA -> "$host/catalog/?q=$encoded"
        }
    }
}

data class StorePriceResult(
    val store: RetailStore,
    val query: String,
    val title: String,
    val priceEgp: Double?,
    val deliveryText: String?,
    val deliveryDaysMax: Int?,
    val productUrl: String,
    val fetchedAtMillis: Long,
    val livePriceAvailable: Boolean,
    val errorCode: String? = null,
)

data class ProductPriceComparisonSnapshot(
    val query: String,
    val results: List<StorePriceResult>,
    val fetchedAtMillis: Long,
) {
    val cheapest: StorePriceResult?
        get() = results.filter { it.priceEgp != null }.minByOrNull { it.priceEgp ?: Double.MAX_VALUE }
}

object ProductPriceComparisonService {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000

    suspend fun search(query: String): ProductPriceComparisonSnapshot = coroutineScope {
        val normalized = query.trim().take(180)
        require(normalized.isNotBlank()) { "empty_query" }
        val now = System.currentTimeMillis()
        val results = RetailStore.entries.map { store ->
            async(Dispatchers.IO) { searchStore(store, normalized, now) }
        }.awaitAll()
        ProductPriceComparisonSnapshot(normalized, results, now)
    }

    private suspend fun searchStore(store: RetailStore, query: String, now: Long): StorePriceResult = withContext(Dispatchers.IO) {
        val searchUrl = store.searchUrl(query)
        val response = runCatching { fetch(searchUrl) }
        if (response.isFailure) {
            return@withContext StorePriceResult(
                store = store,
                query = query,
                title = query,
                priceEgp = null,
                deliveryText = null,
                deliveryDaysMax = null,
                productUrl = searchUrl,
                fetchedAtMillis = now,
                livePriceAvailable = false,
                errorCode = "network_or_blocked",
            )
        }
        val html = response.getOrThrow()
        val parsed = when (store) {
            RetailStore.AMAZON -> parseAmazon(html, query, searchUrl)
            RetailStore.NOON -> parseNoon(html, query, searchUrl)
            RetailStore.JUMIA -> parseJumia(html, query, searchUrl)
        }
        StorePriceResult(
            store = store,
            query = query,
            title = parsed.title.ifBlank { query },
            priceEgp = parsed.price,
            deliveryText = parsed.deliveryText,
            deliveryDaysMax = parsed.deliveryDaysMax,
            productUrl = parsed.url.ifBlank { searchUrl },
            fetchedAtMillis = now,
            livePriceAvailable = parsed.price != null,
            errorCode = if (parsed.price == null) "price_not_exposed" else null,
        )
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "ar-EG,ar;q=0.9,en-US;q=0.7,en;q=0.6")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36 ABFilePro/2")
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP_$code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private data class Parsed(
        val title: String,
        val price: Double?,
        val deliveryText: String?,
        val deliveryDaysMax: Int?,
        val url: String,
    )

    private fun parseAmazon(html: String, query: String, searchUrl: String): Parsed {
        val scope = firstSegment(html, "data-component-type=\"s-search-result\"", 28_000)
            .ifBlank { firstSegment(html, "s-result-item", 28_000) }
            .ifBlank { html.take(120_000) }
        val title = firstCleanGroup(scope, listOf(
            Regex("<h2[^>]*>.*?<span[^>]*>(.*?)</span>", RegexOption.DOT_MATCHES_ALL),
            Regex("aria-label=\"([^\"]{4,220})\"", RegexOption.IGNORE_CASE),
        )) ?: query
        val price = extractCurrencyPrice(scope) ?: extractCurrencyPrice(html.take(160_000))
        val relative = Regex("href=\"([^\"]*(?:/dp/|/gp/product/)[^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(scope)?.groupValues?.getOrNull(1)?.let(::decodeEscapes)
        val url = absoluteUrl(RetailStore.AMAZON.host, relative) ?: searchUrl
        val delivery = extractDelivery(scope)
        return Parsed(title, price, delivery.first, delivery.second, url)
    }

    private fun parseJumia(html: String, query: String, searchUrl: String): Parsed {
        val scope = firstSegment(html, "<article", 24_000).ifBlank { html.take(120_000) }
        val title = firstCleanGroup(scope, listOf(
            Regex("name=\"([^\"]{3,220})\"", RegexOption.IGNORE_CASE),
            Regex("<h3[^>]*class=\"name\"[^>]*>(.*?)</h3>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            Regex("<div[^>]*class=\"name\"[^>]*>(.*?)</div>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        )) ?: query
        val price = extractCurrencyPrice(scope) ?: Regex("""class="prc"[^>]*>\s*([^<]+)""", RegexOption.IGNORE_CASE)
            .find(scope)?.groupValues?.getOrNull(1)?.let(::extractNumber)
        val relative = Regex("href=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(scope)?.groupValues?.getOrNull(1)?.let(::decodeEscapes)
        val url = absoluteUrl(RetailStore.JUMIA.host, relative) ?: searchUrl
        val delivery = extractDelivery(scope)
        return Parsed(title, price, delivery.first, delivery.second, url)
    }

    private fun parseNoon(html: String, query: String, searchUrl: String): Parsed {
        val scope = firstSegment(html, "salePrice", 36_000).ifBlank { html.take(180_000) }
        val title = firstCleanGroup(scope, listOf(
            Regex("""["](?:name|title|productName)["]\s*:\s*["]([^"]{3,240})["]""", RegexOption.IGNORE_CASE),
            Regex("<h2[^>]*>(.*?)</h2>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        )) ?: query
        val jsonPrice = listOf(
            Regex("""["]salePrice["]\s*:\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE),
            Regex("""["]priceNow["]\s*:\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE),
            Regex("""["]price["]\s*:\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE),
            Regex("""["]amount["]\s*:\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE),
        ).firstNotNullOfOrNull { rx -> rx.find(scope)?.groupValues?.getOrNull(1)?.toDoubleOrNull() }
        val price = jsonPrice ?: extractCurrencyPrice(scope)
        val relative = listOf(
            Regex("""["](?:url|productUrl)["]\s*:\s*["]([^"]+)["]""", RegexOption.IGNORE_CASE),
            Regex("href=\"([^\"]+)\"", RegexOption.IGNORE_CASE),
        ).firstNotNullOfOrNull { rx -> rx.find(scope)?.groupValues?.getOrNull(1) }?.let(::decodeEscapes)
        val url = absoluteUrl(RetailStore.NOON.host, relative) ?: searchUrl
        val delivery = extractDelivery(scope)
        return Parsed(title, price, delivery.first, delivery.second, url)
    }

    private fun firstSegment(html: String, marker: String, maxLength: Int): String {
        val idx = html.indexOf(marker, ignoreCase = true)
        if (idx < 0) return ""
        val start = (idx - 2_000).coerceAtLeast(0)
        val end = (idx + maxLength).coerceAtMost(html.length)
        return html.substring(start, end)
    }

    private fun firstCleanGroup(text: String, patterns: List<Regex>): String? = patterns.firstNotNullOfOrNull { regex ->
        regex.find(text)?.groupValues?.getOrNull(1)?.let(::cleanText)?.takeIf { it.isNotBlank() }
    }

    private fun extractCurrencyPrice(text: String): Double? {
        val patterns = listOf(
            Regex("(?:EGP|E£|ج\\.?\\s*م\\.?|جنيه(?:\\s+مصري)?)\\s*([0-9٠-٩][0-9٠-٩,.٬٫ ]{1,18})", RegexOption.IGNORE_CASE),
            Regex("([0-9٠-٩][0-9٠-٩,.٬٫ ]{1,18})\\s*(?:EGP|E£|ج\\.?\\s*م\\.?|جنيه(?:\\s+مصري)?)", RegexOption.IGNORE_CASE),
        )
        return patterns.firstNotNullOfOrNull { rx -> rx.find(text)?.groupValues?.getOrNull(1)?.let(::extractNumber) }
    }

    private fun extractNumber(raw: String): Double? {
        val normalizedDigits = buildString {
            raw.forEach { c ->
                append(when (c) {
                    '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'
                    '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'
                    '٬', ',' -> ','
                    '٫' -> '.'
                    else -> c
                })
            }
        }
        val cleaned = normalizedDigits.replace(" ", "").replace(",", "")
            .filter { it.isDigit() || it == '.' }
        val value = cleaned.toDoubleOrNull() ?: return null
        return value.takeIf { it.isFinite() && it > 0.0 && it < 100_000_000.0 }
    }

    private fun extractDelivery(text: String): Pair<String?, Int?> {
        val plain = cleanText(text)
        val range = Regex("(?:توصيل|delivery)[^0-9٠-٩]{0,24}([0-9٠-٩]{1,2})\\s*[-–إلىto]+\\s*([0-9٠-٩]{1,2})\\s*(?:يوم|أيام|days?)", RegexOption.IGNORE_CASE).find(plain)
        if (range != null) {
            val a = extractNumber(range.groupValues[1])?.toInt()
            val b = extractNumber(range.groupValues[2])?.toInt()
            val max = listOfNotNull(a, b).maxOrNull()
            return range.value.trim().take(100) to max
        }
        val single = Regex("(?:توصيل|delivery)[^0-9٠-٩]{0,24}([0-9٠-٩]{1,2})\\s*(?:يوم|أيام|days?)", RegexOption.IGNORE_CASE).find(plain)
        if (single != null) {
            val d = extractNumber(single.groupValues[1])?.toInt()
            return single.value.trim().take(100) to d
        }
        return null to null
    }

    private fun absoluteUrl(host: String, raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> host + value
            else -> "$host/$value"
        }
    }

    private fun decodeEscapes(value: String): String = value
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\/", "/")
        .replace("&amp;", "&")

    private fun cleanText(raw: String): String {
        return raw
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
