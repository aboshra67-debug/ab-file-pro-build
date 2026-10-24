package com.abfilepro.app.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

object MoneyRecurringStore {
    const val PREFS = "ab_file_pro_money_recurring"
    const val KEY = "rules_v1"
    private const val MAX_RULES = 30
    private val lock = Any()

    fun load(context: Context): List<MoneyRecurringRule> = synchronized(lock) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val amount = o.optDouble("amount", 0.0)
                if (!amount.isFinite() || amount <= 0.0) return@mapNotNull null
                MoneyRecurringRule(
                    id = o.optString("id").ifBlank { return@mapNotNull null },
                    categoryKey = o.optString("categoryKey", "other"),
                    categoryArabic = o.optString("categoryArabic", "أخرى"),
                    amount = amount,
                    note = o.optString("note", ""),
                    day = o.optInt("day", 1).coerceIn(1, 31),
                    lastMonth = o.optString("lastMonth", ""),
                    work = o.optBoolean("work", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, rules: List<MoneyRecurringRule>) {
        val array = JSONArray()
        rules.forEach {
            array.put(
                JSONObject().put("id", it.id).put("categoryKey", it.categoryKey).put("categoryArabic", it.categoryArabic)
                    .put("amount", it.amount).put("note", it.note).put("day", it.day).put("lastMonth", it.lastMonth).put("work", it.work)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    /** [firstRecorded] is the month the person just recorded by hand; the rule starts from the month after it. */
    fun add(context: Context, rule: MoneyRecurringRule): Boolean = synchronized(lock) {
        val rules = load(context)
        if (rules.size >= MAX_RULES) return false
        save(context, rules + rule.copy(id = rule.id.ifBlank { UUID.randomUUID().toString() }))
        true
    }

    fun remove(context: Context, id: String) = synchronized(lock) { save(context, load(context).filterNot { it.id == id }) }

    internal fun markRecorded(context: Context, id: String, month: YearMonth) = synchronized(lock) {
        save(context, load(context).map { if (it.id == id) it.copy(lastMonth = month.toString()) else it })
    }
}

object MoneyRecurringEngine {
    /** Adds the entries that are due. Safe to run any number of times; each month is recorded once. Returns how many were added. */
    fun run(context: Context, today: LocalDate = LocalDate.now()): Int {
        val repository = AssistantRepository(context)
        val zone = ZoneId.systemDefault()
        var added = 0
        MoneyRecurringStore.load(context).forEach { rule ->
            val months = MoneyRecurringPolicy.monthsDue(rule, today)
            months.forEach { month ->
                val at = MoneyRecurringPolicy.dateFor(rule, month).atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli()
                repository.add(
                    repository.loadEntries(), AssistantModule.MONEY, rule.categoryArabic, rule.note,
                    if (rule.work) AssistantCategory.WORK else AssistantCategory.PERSONAL, at, rule.amount, "money_category=${rule.categoryKey}",
                )
                MoneyRecurringStore.markRecorded(context, rule.id, month)
                added++
            }
        }
        return added
    }
}
