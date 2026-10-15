package com.abfilepro.app.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class ShoppingCloudItem(
    val id: Long,
    val name: String,
    val quantity: Int,
    val completed: Boolean,
    val category: String,
    val familyRequestId: String? = null,
)

/**
 * Alpha88 shared shopping store.
 * Keeps the existing local preference format, removes only the untouched Alpha79 demo seed,
 * and adds conflict-safe change timestamps so the same generic family sync endpoint can
 * synchronize the household shopping list between paired devices.
 */
internal class ShoppingListSyncStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun listName(): String {
        val value = prefs.getString(KEY_LIST_NAME, DEFAULT_LIST_NAME).orEmpty().ifBlank { DEFAULT_LIST_NAME }
        if (value != DEFAULT_LIST_NAME && FamilySyncStateStore.timestampFor(appContext, META_ID, 0L) == 0L) {
            FamilySyncStateStore.markChanged(appContext, META_ID)
        }
        return value
    }

    fun saveListName(name: String) {
        val clean = name.trim().take(40).ifBlank { DEFAULT_LIST_NAME }
        if (listName() == clean) return
        prefs.edit().putString(KEY_LIST_NAME, clean).apply()
        FamilySyncStateStore.markChanged(appContext, META_ID)
    }

    fun load(): List<ShoppingCloudItem> {
        val items = loadRaw()
        if (!prefs.getBoolean(KEY_DEMO_MIGRATED, false) && isUntouchedLegacyDemo(items)) {
            prefs.edit().remove(KEY_ITEMS).putBoolean(KEY_DEMO_MIGRATED, true).apply()
            return emptyList()
        }
        if (!prefs.getBoolean(KEY_DEMO_MIGRATED, false)) {
            prefs.edit().putBoolean(KEY_DEMO_MIGRATED, true).apply()
        }
        return items
    }

    fun save(items: List<ShoppingCloudItem>) {
        val previous = load().associateBy { it.id }
        val next = items.distinctBy { it.id }.take(MAX_ITEMS)
        val nextById = next.associateBy { it.id }

        next.forEach { item ->
            if (previous[item.id] != item) FamilySyncStateStore.markChanged(appContext, cloudId(item.id))
        }
        previous.keys.filterNot { it in nextById }.forEach { removedId ->
            FamilySyncStateStore.markDeleted(appContext, cloudId(removedId))
        }
        writeRaw(next)
    }

    fun metaForCloud(): Pair<Long, JSONObject>? {
        val timestamp = FamilySyncStateStore.timestampFor(appContext, META_ID, 0L)
        if (timestamp <= 0L) return null
        return timestamp to JSONObject()
            .put("module", "shopping")
            .put("kind", "meta")
            .put("listName", listName())
    }

    fun itemPayload(item: ShoppingCloudItem): JSONObject = JSONObject()
        .put("module", "shopping")
        .put("kind", "item")
        .put("itemId", item.id)
        .put("name", item.name.take(60))
        .put("qty", item.quantity.coerceIn(1, 99))
        .put("done", item.completed)
        .put("category", item.category.take(40))
        .put("familyRequestId", item.familyRequestId ?: "")

    /** Returns true when local shopping state changed. */
    fun applyRemote(cloudId: String, payload: JSONObject, updatedAt: Long, deleted: Boolean): Boolean {
        val localChangedAt = maxOf(
            FamilySyncStateStore.timestampFor(appContext, cloudId, 0L),
            FamilySyncStateStore.deletedAt(appContext, cloudId),
        )
        if (updatedAt < localChangedAt) return false

        if (cloudId == META_ID) {
            if (deleted) {
                val changed = listName() != DEFAULT_LIST_NAME
                prefs.edit().putString(KEY_LIST_NAME, DEFAULT_LIST_NAME).apply()
                FamilySyncStateStore.acceptRemote(appContext, cloudId, updatedAt, deleted = true)
                return changed
            }
            val remoteName = payload.optString("listName").trim().take(40).ifBlank { DEFAULT_LIST_NAME }
            val changed = listName() != remoteName
            prefs.edit().putString(KEY_LIST_NAME, remoteName).apply()
            FamilySyncStateStore.acceptRemote(appContext, cloudId, updatedAt, deleted = false)
            return changed
        }

        val itemId = parseCloudId(cloudId) ?: return false
        val current = load().toMutableList()
        val index = current.indexOfFirst { it.id == itemId }
        if (deleted) {
            val changed = index >= 0
            if (index >= 0) current.removeAt(index)
            writeRaw(current)
            FamilySyncStateStore.acceptRemote(appContext, cloudId, updatedAt, deleted = true)
            return changed
        }

        val name = payload.optString("name").trim().take(60)
        if (name.isBlank()) return false
        val decoded = ShoppingCloudItem(
            id = itemId,
            name = name,
            quantity = payload.optInt("qty", 1).coerceIn(1, 99),
            completed = payload.optBoolean("done", false),
            category = payload.optString("category", "PRODUCE").take(40),
            familyRequestId = payload.optString("familyRequestId").takeIf { it.isNotBlank() },
        )
        val changed = index < 0 || current[index] != decoded
        if (index >= 0) current[index] = decoded else current.add(decoded)
        writeRaw(current)
        FamilySyncStateStore.acceptRemote(appContext, cloudId, updatedAt, deleted = false)
        return changed
    }

    private fun loadRaw(): List<ShoppingCloudItem> = runCatching {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim().take(60)
                if (name.isBlank()) continue
                add(
                    ShoppingCloudItem(
                        id = o.optLong("id", System.currentTimeMillis() + i),
                        name = name,
                        quantity = o.optInt("qty", 1).coerceIn(1, 99),
                        completed = o.optBoolean("done", false),
                        category = o.optString("category", "PRODUCE").take(40),
                        familyRequestId = o.optString("family_request_id").takeIf { it.isNotBlank() },
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun writeRaw(items: List<ShoppingCloudItem>) {
        val arr = JSONArray()
        items.take(MAX_ITEMS).forEach { item ->
            arr.put(JSONObject()
                .put("id", item.id)
                .put("name", item.name.take(60))
                .put("qty", item.quantity.coerceIn(1, 99))
                .put("done", item.completed)
                .put("category", item.category.take(40))
                .put("family_request_id", item.familyRequestId ?: ""))
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    private fun isUntouchedLegacyDemo(items: List<ShoppingCloudItem>): Boolean {
        if (items.size != LEGACY_DEMO.size) return false
        return items.sortedBy { it.id } == LEGACY_DEMO
    }

    companion object {
        private const val PREFS = "ab_file_pro_shopping_list"
        private const val KEY_ITEMS = "items_v2"
        private const val KEY_LIST_NAME = "list_name_v1"
        private const val KEY_DEMO_MIGRATED = "alpha88_demo_migrated"
        private const val DEFAULT_LIST_NAME = "قائمة المنزل"
        private const val MAX_ITEMS = 300
        const val ITEM_PREFIX = "shopping:item:"
        const val META_ID = "shopping:meta:list_name"

        fun cloudId(id: Long): String = "$ITEM_PREFIX$id"
        fun parseCloudId(value: String): Long? = value.takeIf { it.startsWith(ITEM_PREFIX) }
            ?.removePrefix(ITEM_PREFIX)?.toLongOrNull()
        fun isShoppingCloudId(value: String): Boolean = value == META_ID || value.startsWith(ITEM_PREFIX)

        private val LEGACY_DEMO = listOf(
            ShoppingCloudItem(1, "طماطم", 3, true, "PRODUCE"),
            ShoppingCloudItem(2, "خيار", 2, false, "PRODUCE"),
            ShoppingCloudItem(3, "حليب", 1, true, "DAIRY"),
            ShoppingCloudItem(4, "زبادي", 2, false, "DAIRY"),
            ShoppingCloudItem(5, "مسحوق غسيل", 1, false, "CLEANING"),
            ShoppingCloudItem(6, "منظف عام", 1, true, "CLEANING"),
            ShoppingCloudItem(7, "موز", 1, true, "PRODUCE"),
        )
    }
}
