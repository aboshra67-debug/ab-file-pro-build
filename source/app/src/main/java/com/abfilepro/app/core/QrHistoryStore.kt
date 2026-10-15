package com.abfilepro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class QrHistoryKind { SCANNED, GENERATED }

data class QrHistoryItem(
    val id: String,
    val value: String,
    val kind: QrHistoryKind,
    val favorite: Boolean,
    val createdAt: Long
)

object QrHistoryStore {
    private const val PREFS = "ab_qr_history"
    private const val KEY_ITEMS = "items"
    private const val MAX_NON_FAVORITES = 100

    @Synchronized
    fun list(context: Context): List<QrHistoryItem> = load(context)
        .sortedByDescending { it.createdAt }

    @Synchronized
    fun add(context: Context, value: String, kind: QrHistoryKind): List<QrHistoryItem> {
        val clean = value.trim()
        if (clean.isBlank()) return list(context)

        val current = load(context).toMutableList()
        val previous = current.firstOrNull { it.value == clean && it.kind == kind }
        current.removeAll { it.value == clean && it.kind == kind }
        current += QrHistoryItem(
            id = previous?.id ?: "${System.currentTimeMillis()}_${clean.hashCode()}",
            value = clean,
            kind = kind,
            favorite = previous?.favorite == true,
            createdAt = System.currentTimeMillis()
        )
        return saveAndReturn(context, trim(current))
    }

    @Synchronized
    fun toggleFavorite(context: Context, id: String): List<QrHistoryItem> {
        val updated = load(context).map {
            if (it.id == id) it.copy(favorite = !it.favorite) else it
        }
        return saveAndReturn(context, trim(updated))
    }

    @Synchronized
    fun delete(context: Context, id: String): List<QrHistoryItem> =
        saveAndReturn(context, load(context).filterNot { it.id == id })

    @Synchronized
    fun clearNonFavorites(context: Context): List<QrHistoryItem> =
        saveAndReturn(context, load(context).filter { it.favorite })

    private fun trim(items: List<QrHistoryItem>): List<QrHistoryItem> {
        val favorites = items.filter { it.favorite }
        val normal = items.filterNot { it.favorite }
            .sortedByDescending { it.createdAt }
            .take(MAX_NON_FAVORITES)
        return (favorites + normal).distinctBy { it.id }.sortedByDescending { it.createdAt }
    }

    private fun load(context: Context): List<QrHistoryItem> {
        val raw = SecureValueStore.get(
            context = context,
            key = "qr_history_items",
            legacyPrefsName = PREFS,
            legacyKey = KEY_ITEMS
        ).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val value = obj.optString("value").trim()
                    if (value.isBlank()) continue
                    val kind = runCatching {
                        QrHistoryKind.valueOf(obj.optString("kind", QrHistoryKind.SCANNED.name))
                    }.getOrDefault(QrHistoryKind.SCANNED)
                    add(
                        QrHistoryItem(
                            id = obj.optString("id").ifBlank { "${obj.optLong("createdAt")}_${value.hashCode()}" },
                            value = value,
                            kind = kind,
                            favorite = obj.optBoolean("favorite", false),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAndReturn(context: Context, items: List<QrHistoryItem>): List<QrHistoryItem> {
        val array = JSONArray()
        items.sortedByDescending { it.createdAt }.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("value", item.value)
                    .put("kind", item.kind.name)
                    .put("favorite", item.favorite)
                    .put("createdAt", item.createdAt)
            )
        }
        SecureValueStore.put(context, "qr_history_items", array.toString())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ITEMS).apply()
        return items.sortedByDescending { it.createdAt }
    }
}
