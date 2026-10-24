package com.abfilepro.app.assistant

import android.content.Context
import org.json.JSONArray

/**
 * P25: extra family members (children, parents...) besides "me" and "my partner".
 * Names only; they are used to say whose medicine or appointment an entry is. Stored on this phone
 * and included in the assistant backup.
 */
object FamilyMembersStore {
    const val PREFS = "ab_file_pro_family_members"
    const val KEY = "names_v1"
    const val MAX_MEMBERS = 6

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { HealthEntryCodec.cleanName(array.optString(it)) }.filter { it.isNotEmpty() }.distinct()
        }.getOrDefault(emptyList())
    }

    /** Adds a member; returns the new list (unchanged when the name is blank, a duplicate or the list is full). */
    fun add(context: Context, name: String): List<String> {
        val clean = HealthEntryCodec.cleanName(name)
        val current = load(context)
        if (clean.isEmpty() || current.any { it.equals(clean, ignoreCase = true) } || current.size >= MAX_MEMBERS) return current
        return save(context, current + clean)
    }

    fun remove(context: Context, name: String): List<String> = save(context, load(context).filterNot { it == name })

    private fun save(context: Context, names: List<String>): List<String> {
        val array = JSONArray()
        names.forEach { array.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
        return names
    }
}
