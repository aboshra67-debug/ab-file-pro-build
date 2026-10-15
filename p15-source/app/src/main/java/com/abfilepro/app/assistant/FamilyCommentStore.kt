package com.abfilepro.app.assistant

import android.content.Context

object FamilyCommentStore {
    private const val PREFS = "ab_file_pro_family_comments"

    fun load(context: Context, entryId: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(entryId, "") ?: ""

    fun save(context: Context, entryId: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(entryId, value.take(500))
            .apply()
        FamilySyncStateStore.markChanged(context, entryId)
    }

    fun delete(context: Context, entryId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(entryId).apply()
        FamilySyncStateStore.markChanged(context, entryId)
    }

    internal fun saveFromCloud(context: Context, entryId: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(entryId, value.take(500))
            .apply()
    }

    internal fun deleteFromCloud(context: Context, entryId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(entryId).apply()
    }
}
