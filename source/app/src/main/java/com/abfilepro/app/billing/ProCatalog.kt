package com.abfilepro.app.billing

/**
 * P28: what can be bought and what Pro unlocks. Pure (no Android) so the rules are JVM-tested.
 *
 * Nothing that already worked is taken away: Pro lifts the limits of the newest assistant features
 * (repeats, family members, extra daily doses, fixed monthly expenses) and removes the home banner.
 */
object ProProducts {
    const val MONTHLY = "ab_pro_monthly"
    const val YEARLY = "ab_pro_yearly"
    const val LIFETIME = "ab_pro_lifetime"
    const val REMOVE_ADS = "ab_remove_ads"

    val SUBSCRIPTIONS = listOf(YEARLY, MONTHLY)
    val ONE_TIME = listOf(LIFETIME, REMOVE_ADS)
    val ALL = SUBSCRIPTIONS + ONE_TIME
}

enum class ProFeature(val freeLimit: Int) {
    /** Tasks or appointments that repeat. */
    REPEATING_ITEMS(3),
    /** Extra family members in the medicine screen. */
    FAMILY_MEMBERS(2),
    /** Fixed monthly expenses that add themselves. */
    MONEY_RECURRING(2),
    /** Doses per day for one medicine (free: up to 2). */
    DOSES_PER_MEDICINE(2),
}

data class Entitlement(val pro: Boolean, val adsRemoved: Boolean) {
    companion object { val NONE = Entitlement(pro = false, adsRemoved = false) }
}

object ProPolicy {
    /** Pro products (any subscription or the lifetime purchase) unlock everything and remove ads. */
    fun entitlementFor(owned: Set<String>): Entitlement {
        val pro = ProProducts.YEARLY in owned || ProProducts.MONTHLY in owned || ProProducts.LIFETIME in owned
        return Entitlement(pro = pro, adsRemoved = pro || ProProducts.REMOVE_ADS in owned)
    }

    /** May one more item be added when [currentCount] already exist? */
    fun allows(feature: ProFeature, currentCount: Int, pro: Boolean): Boolean = pro || currentCount < feature.freeLimit

    /** For a counted value such as "doses per day": is [wanted] within the free limit? */
    fun allowsValue(feature: ProFeature, wanted: Int, pro: Boolean): Boolean = pro || wanted <= feature.freeLimit
}
