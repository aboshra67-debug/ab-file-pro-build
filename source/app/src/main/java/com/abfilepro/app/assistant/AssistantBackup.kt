package com.abfilepro.app.assistant

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * P20: a user-controlled copy of the personal assistant (notes, tasks, money, health, documents,
 * prayer settings, budgets, shopping list). The file is written where the user chooses with the
 * system picker; nothing is uploaded. Importing never deletes or overwrites a record already on the
 * phone: records with a new id are added, settings are restored only when they are not set yet.
 */
object AssistantBackup {
    private const val FORMAT = "ab-file-pro-assistant-backup"
    private const val VERSION = 1
    private val settingPrefs = listOf(
        "ab_personal_assistant" to listOf("prayer_settings_v1", "money_budgets_v1"),
        "ab_file_pro_shopping_list" to listOf("items_v2", "list_name_v1"),
        // P25: bill reminders (due dates, accounts) are part of the backup.
        BillReminderRecords.PREFS to listOf(BillReminderRecords.KEY),
        // P25: price alerts and favourite markets used to be lost when the phone changed.
        "ab_file_pro_market_alerts" to listOf("alerts_v1", "favorites_v1"),
        "ab_product_price_alerts" to listOf("items"),
        FamilyMembersStore.PREFS to listOf(FamilyMembersStore.KEY),
    )

    /** P25: stores whose keys are dated (one per day/prayer); every text entry is carried over. */
    private val wholePrefs = listOf("ab_prayer_completion_v1")

    data class ImportResult(val added: Int, val alreadyPresent: Int, val settingsRestored: Int)

    fun export(context: Context, target: Uri): Int {
        val entries = AssistantRepository(context).loadEntries()
        val settings = JSONObject()
        settingPrefs.forEach { (prefsName, keys) ->
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            keys.forEach { key -> prefs.getString(key, null)?.let { settings.put("$prefsName/$key", it) } }
        }
        val whole = JSONObject()
        wholePrefs.forEach { name ->
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (value is String) values.put(key, value)
            }
            whole.put(name, values)
        }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("store", JSONObject(AssistantStore.encode(entries)))
            .put("settings", settings)
            .put("wholePrefs", whole)
        val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
        val output = requireNotNull(context.contentResolver.openOutputStream(target, "wt")) { "backup_target_unavailable" }
        output.use { it.write(bytes); it.flush() }
        return entries.size
    }

    fun import(context: Context, source: Uri): ImportResult {
        val text = requireNotNull(context.contentResolver.openInputStream(source)) { "backup_source_unavailable" }
            .use { it.readBytes() }.toString(Charsets.UTF_8)
        val root = JSONObject(text)
        require(root.optString("format") == FORMAT) { "backup_format_unknown" }
        require(root.optInt("version", 0) in 1..VERSION) { "backup_version_unsupported" }
        val incoming = AssistantStore.decode(root.optJSONObject("store")?.toString().orEmpty())
        var added = 0
        var present = 0
        val repository = AssistantRepository(context)
        val existing = repository.loadEntries().map { it.id }.toSet()
        val fresh = incoming.filter { it.id !in existing }.distinctBy { it.id }
        present = incoming.size - fresh.size
        if (fresh.isNotEmpty()) {
            AssistantStore.mutate(context) { list -> list + fresh }
            added = fresh.size
            fresh.forEach { entry ->
                when (entry.module) {
                    AssistantModule.TASKS, AssistantModule.CALENDAR -> TaskAlarmScheduler.sync(context, entry)
                    AssistantModule.HEALTH -> HealthAlarmScheduler.sync(context, entry)
                    AssistantModule.DOCUMENTS -> DocumentRenewalAlarmScheduler.sync(context, entry)
                    else -> Unit
                }
            }
        }
        var restored = 0
        val settings = root.optJSONObject("settings") ?: JSONObject()
        settingPrefs.forEach { (prefsName, keys) ->
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            keys.forEach { key ->
                val value = settings.optString("$prefsName/$key", "")
                if (value.isNotBlank() && prefs.getString(key, null).isNullOrBlank()) {
                    prefs.edit().putString(key, value).apply()
                    restored++
                }
            }
        }
        // Dated stores: add only the days that are not on this phone yet.
        val wholeIncoming = root.optJSONObject("wholePrefs") ?: JSONObject()
        wholePrefs.forEach { name ->
            val values = wholeIncoming.optJSONObject(name) ?: return@forEach
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            var count = 0
            values.keys().forEach { key ->
                if (!prefs.contains(key)) { editor.putString(key, values.optString(key)); count++ }
            }
            editor.apply()
            restored += count
        }
        if (restored > 0) runCatching { PrayerAlarmScheduler.reschedule(context) }
        if (restored > 0) runCatching { BillAlarmScheduler.rescheduleAll(context) }
        return ImportResult(added, present, restored)
    }
}
