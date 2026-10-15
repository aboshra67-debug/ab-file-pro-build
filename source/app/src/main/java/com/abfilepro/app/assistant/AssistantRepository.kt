package com.abfilepro.app.assistant

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * P20: one process-wide store for assistant records.
 *
 * Before P20 every screen and alarm receiver kept its own copy of the list and wrote the whole
 * list back (a newer change made elsewhere could be overwritten — AS-02/FAM-03), the list was
 * silently cut at 2,000 records (AS-15), and everything lived in one large SharedPreferences string.
 * Now records are kept in memory once, every change is applied to the latest data under a lock and
 * written atomically to app-private storage, and nothing is dropped. The pre-P20 SharedPreferences
 * copy is migrated once and left untouched as a fallback.
 */
internal object AssistantStore {
    private const val FILE_NAME = "assistant/entries_v2.json"
    private const val MIGRATED_KEY = "assistant_store_v2_migrated"
    private val lock = Any()
    @Volatile private var cache: List<AssistantEntry>? = null
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    private fun file(context: Context) = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME).apply { parentFile?.mkdirs() })

    fun entries(context: Context): List<AssistantEntry> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val loaded = readFile(context) ?: migrateFromPreferences(context)
            cache = loaded
            return loaded
        }
    }

    fun mutate(context: Context, change: (List<AssistantEntry>) -> List<AssistantEntry>): List<AssistantEntry> {
        synchronized(lock) {
            val current = entries(context)
            val next = change(current)
            if (next !== current) {
                write(context, next)
                cache = next
                mutableRevision.value = mutableRevision.value + 1
            }
            return next
        }
    }

    /** Test hook: forget the in-memory copy (the file stays). */
    internal fun resetCacheForTests() { synchronized(lock) { cache = null } }

    private fun readFile(context: Context): List<AssistantEntry>? {
        val atomic = file(context)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        return runCatching { decode(String(atomic.readFully(), Charsets.UTF_8)) }.getOrElse {
            // Never overwrite an unreadable store: keep it aside for recovery, then fall back to the
            // last pre-P20 copy instead of starting empty.
            runCatching { atomic.baseFile.copyTo(File(atomic.baseFile.parentFile, "entries_v2.unreadable-${System.currentTimeMillis()}.json")) }
            null
        }
    }

    private fun migrateFromPreferences(context: Context): List<AssistantEntry> {
        val prefs = context.applicationContext.getSharedPreferences(AssistantRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val legacy = runCatching { decode(prefs.getString(AssistantRepository.KEY_DATA, null) ?: "") }.getOrDefault(emptyList())
        write(context, legacy)
        prefs.edit().putBoolean(MIGRATED_KEY, true).apply()
        return legacy
    }

    private fun write(context: Context, entries: List<AssistantEntry>) {
        val atomic = file(context)
        val stream = atomic.startWrite()
        try {
            stream.write(encode(entries).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun encode(entries: List<AssistantEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("module", entry.module.storageId)
                put("title", entry.title.take(AssistantRepository.MAX_TEXT))
                put("details", entry.details.take(AssistantRepository.MAX_DETAILS))
                put("category", entry.category.name)
                put("createdAt", entry.createdAt)
                entry.dueAt?.let { put("dueAt", it) }
                put("completed", entry.completed)
                entry.amount?.let { put("amount", it) }
                put("secondary", entry.secondary.take(AssistantRepository.MAX_SECONDARY))
                put("enabled", entry.enabled)
                put("pinned", entry.pinned)
            })
        }
        return JSONObject().put("schema", 2).put("entries", array).toString()
    }

    fun decode(raw: String): List<AssistantEntry> {
        if (raw.isBlank()) return emptyList()
        val root = JSONObject(raw)
        val items = root.optJSONArray("entries") ?: return emptyList()
        return buildList {
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
    }
}

class AssistantRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Changes whenever any screen, alarm or sync changed the records. */
    val revision: StateFlow<Long> get() = AssistantStore.revision

    fun loadEntries(): List<AssistantEntry> = AssistantStore.entries(appContext)

    fun replaceEntries(entries: List<AssistantEntry>) {
        AssistantStore.mutate(appContext) { entries.distinctBy { it.id } }
    }

    /**
     * Adds a record. [current] is accepted for source compatibility only: the change is applied to
     * the latest stored list, which is returned, so a stale screen copy can never undo other changes.
     */
    @Suppress("UNUSED_PARAMETER")
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
            secondary = secondary.trim().take(MAX_SECONDARY),
            enabled = enabled,
        )
        val updated = AssistantStore.mutate(appContext) { listOf(created) + it }
        if (module == AssistantModule.FAMILY_REQUESTS || module == AssistantModule.HEALTH) {
            FamilySyncStateStore.markChanged(appContext, created.id)
        }
        return updated
    }

    /** Updates one record in the latest list (a record deleted meanwhile is not resurrected). */
    @Suppress("UNUSED_PARAMETER")
    fun update(current: List<AssistantEntry>, entry: AssistantEntry): List<AssistantEntry> {
        var found = false
        val updated = AssistantStore.mutate(appContext) { list ->
            if (list.none { it.id == entry.id }) list
            else list.map { if (it.id == entry.id) { found = true; entry } else it }
        }
        if (found && (entry.module == AssistantModule.FAMILY_REQUESTS || entry.module == AssistantModule.HEALTH)) {
            FamilySyncStateStore.markChanged(appContext, entry.id)
        }
        return updated
    }

    @Suppress("UNUSED_PARAMETER")
    fun delete(current: List<AssistantEntry>, id: String): List<AssistantEntry> {
        var removed: AssistantEntry? = null
        val updated = AssistantStore.mutate(appContext) { list ->
            removed = list.firstOrNull { it.id == id }
            if (removed == null) list else list.filterNot { it.id == id }
        }
        if (removed?.module == AssistantModule.FAMILY_REQUESTS || removed?.module == AssistantModule.HEALTH) {
            FamilySyncStateStore.markDeleted(appContext, id)
        }
        return updated
    }

    /** Family sync: applies only the changed records to the latest list. */
    internal fun applyRemote(upserts: List<AssistantEntry>, removedIds: Set<String>): List<AssistantEntry> {
        if (upserts.isEmpty() && removedIds.isEmpty()) return loadEntries()
        val byId = upserts.associateBy { it.id }
        return AssistantStore.mutate(appContext) { list ->
            val kept = list.filterNot { it.id in removedIds }.map { byId[it.id] ?: it }
            val existing = kept.map { it.id }.toSet()
            byId.values.filter { it.id !in existing }.sortedByDescending { it.createdAt } + kept
        }
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
        internal const val PREFS_NAME = "ab_personal_assistant"
        internal const val KEY_DATA = "assistant_data_v1"
        private const val KEY_PRAYER = "prayer_settings_v1"
        private const val KEY_MONEY_BUDGETS = "money_budgets_v1"
        internal const val MAX_TEXT = 240
        // P20: links (recording/attachment URIs) are not cut at 240 characters any more.
        internal const val MAX_SECONDARY = 2_000
        internal const val MAX_DETAILS = 4_000
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
