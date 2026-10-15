package com.abfilepro.app.assistant

import android.content.Context
import java.security.SecureRandom

data class FamilyProfile(
    val familyName: String = "عائلتي",
    val selfName: String = "أنا",
    val partnerName: String = "زوجتي",
    val inviteCode: String = "",
)

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

    fun load(context: Context): FamilyProfile {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var code = prefs.getString(KEY_INVITE_CODE, "").orEmpty()
        if (code.length != 6 || code.any { !it.isDigit() }) {
            code = (SecureRandom().nextInt(900_000) + 100_000).toString()
            prefs.edit().putString(KEY_INVITE_CODE, code).apply()
        }
        return FamilyProfile(
            familyName = prefs.getString(KEY_FAMILY_NAME, "عائلتي")?.ifBlank { "عائلتي" } ?: "عائلتي",
            selfName = prefs.getString(KEY_SELF_NAME, "أنا")?.ifBlank { "أنا" } ?: "أنا",
            partnerName = prefs.getString(KEY_PARTNER_NAME, "زوجتي")?.ifBlank { "زوجتي" } ?: "زوجتي",
            inviteCode = code,
        )
    }

    fun save(context: Context, profile: FamilyProfile) {
        val previousCode = load(context).inviteCode
        val normalizedCode = profile.inviteCode.filter(Char::isDigit).take(6).takeIf { it.length == 6 }
            ?: previousCode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FAMILY_NAME, profile.familyName.trim().ifBlank { "عائلتي" })
            .putString(KEY_SELF_NAME, profile.selfName.trim().ifBlank { "أنا" })
            .putString(KEY_PARTNER_NAME, profile.partnerName.trim().ifBlank { "زوجتي" })
            .putString(KEY_INVITE_CODE, normalizedCode)
            .apply()
        FamilySyncStateStore.clearDeviceTokenIfFamilyChanged(context, previousCode, normalizedCode)
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
