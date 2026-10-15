package com.abfilepro.app.assistant

/**
 * P17: pure (JVM-testable) rules for family sharing. No Android types here.
 */
object FamilySyncPolicy {
    data class Invite(val code: String, val secret: String) {
        override fun toString(): String = "$code-$secret"
    }

    private const val SECRET_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Accepts "123456-ABCD234567", spaces, lower case, and Arabic/Persian digits. */
    fun parseInvite(raw: String): Invite? {
        val normalized = buildString {
            raw.trim().forEach { ch ->
                val digit = when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    else -> ch
                }
                append(digit.uppercaseChar())
            }
        }.filter { it.isLetterOrDigit() }
        if (normalized.length < 6 + 8) return null
        val code = normalized.take(6)
        val secret = normalized.drop(6)
        if (!code.all { it in '0'..'9' }) return null
        if (secret.length !in 8..20 || !secret.all { it in SECRET_ALPHABET }) return null
        return Invite(code, secret)
    }

    /**
     * Whether a local item may be sent to the family.
     *  - items that existed before joining someone else's family stay local (FAM-01);
     *  - health items leave the phone only when the user turned on health sharing (FAM-N02);
     *  - only changes newer than the last confirmed upload are re-sent.
     */
    fun shouldUpload(
        id: String,
        isHealth: Boolean,
        shareHealth: Boolean,
        excludedIds: Set<String>,
        changedAt: Long,
        pushedUpTo: Long,
    ): Boolean {
        if (id in excludedIds) return false
        if (isHealth && !shareHealth) return false
        return changedAt > pushedUpTo
    }

    /**
     * A remote change to a health entry that is this device's own is ignored while health sharing
     * is off, so withdrawing a share can never delete the user's own local health records.
     */
    fun acceptRemoteForLocalHealth(localIsOwnHealth: Boolean, shareHealth: Boolean): Boolean =
        !(localIsOwnHealth && !shareHealth)

    fun <T> chunks(items: List<T>, size: Int = 400): List<List<T>> = if (items.isEmpty()) emptyList() else items.chunked(size)
}
