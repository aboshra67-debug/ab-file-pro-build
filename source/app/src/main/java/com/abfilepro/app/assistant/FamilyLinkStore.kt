package com.abfilepro.app.assistant

import android.content.Context

/**
 * P17: family sharing is opt-in.
 *  - OFF: nothing is registered or sent (default for every new install).
 *  - ON: the user created or joined a family from Family settings.
 *  - LEGACY_PAUSED: an install from before P17 that was registered automatically. Sync stays
 *    paused until the user confirms (keep, or leave and delete) from Family settings.
 */
enum class FamilySyncState { OFF, ON, LEGACY_PAUSED }

data class FamilyProfile(
    val familyName: String = "عائلتي",
    val selfName: String = "أنا",
    val partnerName: String = "زوجتي",
    val inviteCode: String = "",
    val syncState: FamilySyncState = FamilySyncState.OFF,
    val shareHealth: Boolean = false,
) {
    val isLinked: Boolean get() = inviteCode.length == 6 && inviteCode.all(Char::isDigit)
}

data class FamilyRequestMeta(
    val status: String = "new",
    val category: String = "OTHER",
    val quantity: Int = 1,
    val fromName: String = "زوجتي",
    val toName: String = "أنا",
)

object FamilyLinkStore {
    private const val PREFS = "ab_file_pro_family_link"
    private const val KEY_FAMILY_NAME = "family_name"
    private const val KEY_SELF_NAME = "self_name"
    private const val KEY_PARTNER_NAME = "partner_name"
    private const val KEY_INVITE_CODE = "invite_code"
    private const val KEY_SYNC_STATE = "sync_state_v17"
    private const val KEY_SHARE_HEALTH = "share_health_v17"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): FamilyProfile {
        val prefs = prefs(context)
        val storedCode = prefs.getString(KEY_INVITE_CODE, "").orEmpty()
            .takeIf { it.length == 6 && it.all(Char::isDigit) }.orEmpty()
        val state = if (prefs.contains(KEY_SYNC_STATE)) {
            runCatching { FamilySyncState.valueOf(prefs.getString(KEY_SYNC_STATE, "OFF").orEmpty()) }
                .getOrDefault(FamilySyncState.OFF)
        } else {
            // One-time migration. Before P17 every install generated a code and registered on the
            // first open of the assistant, so a stored code + device token only means "was registered".
            val migrated = if (storedCode.isNotEmpty() && FamilySyncStateStore.deviceToken(context, storedCode) != null) {
                FamilySyncState.LEGACY_PAUSED
            } else FamilySyncState.OFF
            val edit = prefs.edit().putString(KEY_SYNC_STATE, migrated.name)
            if (migrated == FamilySyncState.OFF) edit.remove(KEY_INVITE_CODE)
            edit.apply()
            migrated
        }
        return FamilyProfile(
            familyName = prefs.getString(KEY_FAMILY_NAME, "عائلتي")?.ifBlank { "عائلتي" } ?: "عائلتي",
            selfName = prefs.getString(KEY_SELF_NAME, "أنا")?.ifBlank { "أنا" } ?: "أنا",
            partnerName = prefs.getString(KEY_PARTNER_NAME, "زوجتي")?.ifBlank { "زوجتي" } ?: "زوجتي",
            inviteCode = if (state == FamilySyncState.OFF) "" else storedCode,
            syncState = state,
            shareHealth = prefs.getBoolean(KEY_SHARE_HEALTH, false),
        )
    }

    /** Saves display names only. Linking happens in [link] / [unlink] after the server agreed. */
    fun save(context: Context, profile: FamilyProfile) {
        prefs(context).edit()
            .putString(KEY_FAMILY_NAME, profile.familyName.trim().ifBlank { "عائلتي" })
            .putString(KEY_SELF_NAME, profile.selfName.trim().ifBlank { "أنا" })
            .putString(KEY_PARTNER_NAME, profile.partnerName.trim().ifBlank { "زوجتي" })
            .apply()
    }

    internal fun link(context: Context, familyCode: String) {
        require(familyCode.length == 6 && familyCode.all(Char::isDigit)) { "invalid family code" }
        prefs(context).edit()
            .putString(KEY_INVITE_CODE, familyCode)
            .putString(KEY_SYNC_STATE, FamilySyncState.ON.name)
            .apply()
    }

    internal fun confirmLegacy(context: Context) {
        if (load(context).syncState == FamilySyncState.LEGACY_PAUSED) {
            prefs(context).edit().putString(KEY_SYNC_STATE, FamilySyncState.ON.name).apply()
        }
    }

    internal fun unlink(context: Context) {
        prefs(context).edit()
            .remove(KEY_INVITE_CODE)
            .putString(KEY_SYNC_STATE, FamilySyncState.OFF.name)
            .putBoolean(KEY_SHARE_HEALTH, false)
            .apply()
    }

    internal fun setShareHealth(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHARE_HEALTH, enabled).apply()
    }

    fun encode(meta: FamilyRequestMeta): String = listOf(
        meta.status.ifBlank { "new" },
        "cat=${meta.category}",
        "qty=${meta.quantity.coerceIn(1, 99)}",
        "from=${escape(meta.fromName)}",
        "to=${escape(meta.toName)}",
    ).joinToString("|")

    fun parse(value: String): FamilyRequestMeta {
        if (value.isBlank()) return FamilyRequestMeta()
        val parts = value.split('|')
        val status = parts.firstOrNull()?.takeIf { it in setOf("new", "in_progress", "done") } ?: "new"
        val map = parts.drop(1).mapNotNull { token ->
            val idx = token.indexOf('=')
            if (idx <= 0) null else token.substring(0, idx) to token.substring(idx + 1)
        }.toMap()
        return FamilyRequestMeta(
            status = status,
            category = map["cat"].orEmpty().ifBlank { "OTHER" },
            quantity = map["qty"]?.toIntOrNull()?.coerceIn(1, 99) ?: 1,
            fromName = unescape(map["from"].orEmpty()).ifBlank { "زوجتي" },
            toName = unescape(map["to"].orEmpty()).ifBlank { "أنا" },
        )
    }

    fun withStatus(value: String, status: String): String = encode(parse(value).copy(status = status))

    private fun escape(value: String): String = value.replace("%", "%25").replace("|", "%7C").replace("=", "%3D")
    private fun unescape(value: String): String = value.replace("%3D", "=").replace("%7C", "|").replace("%25", "%")
}
