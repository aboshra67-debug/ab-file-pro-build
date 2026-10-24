package com.abfilepro.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProPolicyTest {
    @Test fun nothingOwnedMeansNoProAndAds() {
        assertEquals(Entitlement.NONE, ProPolicy.entitlementFor(emptySet()))
    }

    @Test fun anySubscriptionOrLifetimeIsProAndRemovesAds() {
        listOf(ProProducts.MONTHLY, ProProducts.YEARLY, ProProducts.LIFETIME).forEach {
            val e = ProPolicy.entitlementFor(setOf(it))
            assertTrue(it, e.pro)
            assertTrue(it, e.adsRemoved)
        }
    }

    @Test fun removeAdsAloneIsNotPro() {
        val e = ProPolicy.entitlementFor(setOf(ProProducts.REMOVE_ADS))
        assertFalse(e.pro)
        assertTrue(e.adsRemoved)
    }

    @Test fun unknownProductsGiveNothing() {
        assertEquals(Entitlement.NONE, ProPolicy.entitlementFor(setOf("something_else")))
    }

    @Test fun freeLimitsAllowUpToTheLimitOnly() {
        assertTrue(ProPolicy.allows(ProFeature.REPEATING_ITEMS, 2, pro = false))
        assertFalse(ProPolicy.allows(ProFeature.REPEATING_ITEMS, 3, pro = false))
        assertTrue(ProPolicy.allows(ProFeature.REPEATING_ITEMS, 500, pro = true))
    }

    @Test fun dosesPerDayAreValueLimited() {
        assertTrue(ProPolicy.allowsValue(ProFeature.DOSES_PER_MEDICINE, 2, pro = false))
        assertFalse(ProPolicy.allowsValue(ProFeature.DOSES_PER_MEDICINE, 3, pro = false))
        assertTrue(ProPolicy.allowsValue(ProFeature.DOSES_PER_MEDICINE, 4, pro = true))
    }

    @Test fun everyProductIsListedOnce() {
        assertEquals(4, ProProducts.ALL.toSet().size)
        assertEquals(ProProducts.ALL.size, ProProducts.ALL.toSet().size)
    }
}
