package com.abfilepro.app.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Private offline-first store for assistant content.
 *
 * SharedPreferences is deliberate here: the feature stores small text records only and needs no
 * new database dependency. Writes replace one compact, versioned JSON document atomically.
 */
class AssistantRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadEntries(): List<AssistantEntry> = runCatching {
        val root = JSONObject(prefs.getString(KEY_DATA, null) ?: return emptyList())
        val items = root.optJSONArray("entries") ?: return emptyList()
        buildList {
            for (index in 0 until items.length()) {
                val value = items.optJSONObject(index) ?: continue
                val module = AssistantModule.fromStorageId(value.optString("module")) ?: continue
                val category = runCatching {
                    AssistantCategory.valueOf(value.optString("category", AssistantCategory.PERSONAL.name))
                }.getOrDefault(AssistantDomain.sanitizeCategory(module, AssistantCategory.PERSONAL))
                add(
                    AssistantEntry(
                        id = value.optString("id").ifBlank { UUID.randomUUID().toString() },
                        module = module,
                        title = value.optString("title"),
                        details = value.optString("details"),
                        category = AssistantDomain.sanitizeCategory(module, category),
                        createdAt = value.optLong("createdAt", System.currentTimeMillis()),
                        dueAt = value.optLongOrNull("dueAt"),
                        completed = value.optBoolean("completed", false),
                        amount = value.optDoubleOrNull("amount"),
                        secondary = value.optString("secondary"),
                        enabled = value.optBoolean("enabled", true),
                        pinned = value.optBoolean("pinned", false),
                    )
                )
            }
        }
    }.getOrElse { emptyList() }

    fun replaceEntries(entries: List<AssistantEntry>) {
        val array = JSONArray()
        entries.take(MAX_ENTRIES).forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("module", entry.module.storageId)
                put("title", entry.title.take(MAX_TEXT))
                put("details", entry.details.take(MAX_DETAILS))
                put("category", entry.category.name)
                put("createdAt", entry.createdAt)
                entry.dueAt?.let { put("dueAt", it) }
                put("completed", entry.completed)
                entry.amount?.let { put("amount", it) }
                put("secondary", entry.secondary.take(MAX_TEXT))
                put("enabled", entry.enabled)
                put("pinned", entry.pinned)
            })
        }
        val root = JSONObject().put("schema", SCHEMA_VERSION).put("entries", array)
        prefs.edit().putString(KEY_DATA, root.toString()).apply()
    }

    fun add(
        current: List<AssistantEntry>,
        module: AssistantModule,
        title: String,
        details: String = "",
        category: AssistantCategory = AssistantCategory.PERSONAL,
        dueAt: Long? = null,
        amount: Double? = null,
        secondary: String = "",
        enabled: Boolean = true,
    ): List<AssistantEntry> {
        val cleanTitle = title.trim().take(MAX_TEXT)
        require(cleanTitle.isNotEmpty()) { "Title is required" }
        val created = AssistantEntry(
            id = UUID.randomUUID().toString(),
            module = module,
            title = cleanTitle,
            details = details.trim().take(MAX_DETAILS),
            category = AssistantDomain.sanitizeCategory(module, category),
            dueAt = dueAt,
            amount = amount,
            secondary = secondary.trim().take(MAX_TEXT),
            enabled = enabled,
        )
        val updated = listOf(created) + current
        replaceEntries(updated)
        if (module == AssistantModule.FAMILY_REQUESTS || module == AssistantModule.HEALTH) {
            FamilySyncStateStore.markChanged(appContext, created.id)
        }
        return updated.take(MAX_ENTRIES)
    }

    fun update(current: List<AssistantEntry>, entry: AssistantEntry): List<AssistantEntry> {
        val updated = current.map { if (it.id == entry.id) entry else it }
        replaceEntries(updated)
        if (entry.module == AssistantModule.FAMILY_REQUESTS || entry.module == AssistantModule.HEALTH) {
            FamilySyncStateStore.markChanged(appContext, entry.id)
        }
        return updated
    }

    fun delete(current: List<AssistantEntry>, id: String): List<AssistantEntry> {
        val removed = current.firstOrNull { it.id == id }
        val updated = current.filterNot { it.id == id }
        replaceEntries(updated)
        if (removed?.module == AssistantModule.FAMILY_REQUESTS || removed?.module == AssistantModule.HEALTH) {
            FamilySyncStateStore.markDeleted(appContext, id)
        }
        return updated
    }

    fun loadPrayerSettings(): PrayerSettings = runCatching {
        val value = JSONObject(prefs.getString(KEY_PRAYER, null) ?: return PrayerSettings())
        val rawCityId = value.optString("cityId")
        val city = CityPreset.fromId(rawCityId)
        val adjustmentsObject = value.optJSONObject("adjustments") ?: JSONObject()
        val adjustments = PrayerName.entries.associateWith { prayer ->
            adjustmentsObject.optInt(prayer.storageId, 0).coerceIn(-30, 30)
        }.filterValues { it != 0 }
        val alertArray = value.optJSONArray("alerts") ?: JSONArray()
        val alerts = buildSet {
            for (index in 0 until alertArray.length()) {
                PrayerName.fromStorageId(alertArray.optString(index))?.let(::add)
            }
        }
        PrayerSettings(
            cityId = if (rawCityId == "device") "device" else city.id,
            latitude = value.optDouble("latitude", city.latitude),
            longitude = value.optDouble("longitude", city.longitude),
            zoneId = value.optString("zoneId", city.zoneId),
            cityName = value.optString("cityName", defaultPrayerCityName(rawCityId)),
            useDeviceLocation = value.optBoolean("useDeviceLocation", false),
            method = enumOrDefault(value.optString("method"), PrayerMethod.EGYPTIAN),
            asrSchool = enumOrDefault(value.optString("asrSchool"), AsrSchool.STANDARD),
            adjustmentsMinutes = adjustments,
            enabledAlerts = alerts,
            preAlertMinutes = value.optInt("preAlertMinutes", 10).coerceIn(0, 60),
            exactAlarms = value.optBoolean("exactAlarms", true),
            autoGps = value.optBoolean("autoGps", true),
            adhanVoice = enumOrDefault(value.optString("adhanVoice"), AdhanVoice.MINSHAWI),
            prayerCheckMinutes = value.optInt("prayerCheckMinutes", 20).coerceIn(5, 90),
            repeatNudgeMinutes = value.optInt("repeatNudgeMinutes", 10).coerceIn(5, 60),
        )
    }.getOrElse { PrayerSettings() }

    /** Alpha74: monthly budget values for the local Money module. */
    fun loadMonthlyBudget(monthKey: String): Double = runCatching {
        val root = JSONObject(prefs.getString(KEY_MONEY_BUDGETS, null) ?: return 0.0)
        root.optDouble(monthKey, 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    }.getOrDefault(0.0)

    fun saveMonthlyBudget(monthKey: String, amount: Double) {
        require(monthKey.matches(Regex("\\d{4}-\\d{2}"))) { "Invalid month key" }
        require(amount.isFinite() && amount >= 0.0) { "Invalid budget amount" }
        val root = runCatching {
            JSONObject(prefs.getString(KEY_MONEY_BUDGETS, null) ?: "{}")
        }.getOrElse { JSONObject() }
        root.put(monthKey, amount)
        prefs.edit().putString(KEY_MONEY_BUDGETS, root.toString()).apply()
    }

    fun savePrayerSettings(settings: PrayerSettings) {
        val adjustments = JSONObject()
        settings.adjustmentsMinutes.forEach { (prayer, minutes) ->
            adjustments.put(prayer.storageId, minutes.coerceIn(-30, 30))
        }
        val alerts = JSONArray()
        settings.enabledAlerts.forEach { alerts.put(it.storageId) }
        val value = JSONObject()
            .put("cityId", settings.cityId)
            .put("latitude", settings.latitude)
            .put("longitude", settings.longitude)
            .put("zoneId", settings.zoneId)
            .put("cityName", settings.cityName)
            .put("useDeviceLocation", settings.useDeviceLocation)
            .put("method", settings.method.name)
            .put("asrSchool", settings.asrSchool.name)
            .put("adjustments", adjustments)
            .put("alerts", alerts)
            .put("preAlertMinutes", settings.preAlertMinutes.coerceIn(0, 60))
            .put("exactAlarms", settings.exactAlarms)
            .put("autoGps", settings.autoGps)
            .put("adhanVoice", settings.adhanVoice.name)
            .put("prayerCheckMinutes", settings.prayerCheckMinutes.coerceIn(5, 90))
            .put("repeatNudgeMinutes", settings.repeatNudgeMinutes.coerceIn(5, 60))
        prefs.edit().putString(KEY_PRAYER, value.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "ab_personal_assistant"
        private const val KEY_DATA = "assistant_data_v1"
        private const val KEY_PRAYER = "prayer_settings_v1"
        private const val KEY_MONEY_BUDGETS = "money_budgets_v1"
        private const val SCHEMA_VERSION = 1
        private const val MAX_ENTRIES = 2_000
        private const val MAX_TEXT = 240
        private const val MAX_DETAILS = 4_000
    }
}

private fun defaultPrayerCityName(cityId: String): String = when (cityId) {
    "alexandria" -> "الإسكندرية"
    "giza" -> "الجيزة"
    "riyadh" -> "الرياض"
    "mecca" -> "مكة"
    "dubai" -> "دبي"
    "device" -> "موقعي الحالي"
    else -> "القاهرة"
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

private inline fun <reified T : Enum<T>> enumOrDefault(value: String, fallback: T): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
