package com.abfilepro.app.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsPolicyTest {
    @Test fun homeShowsTheBanner() {
        assertTrue(AdsPolicy.canShowOn("home", adsRemoved = false))
    }

    @Test fun privateScreensNeverShowAds() {
        listOf("scan", "smartscan", "cardscan", "ocr", "pdf", "convert", "viewer?path=x", "editor?uri=x", "cleanshare?uri=x", "receipt?uri=x", "settings", "files?dir=x", "splash")
            .forEach { assertFalse(it, AdsPolicy.canShowOn(it, adsRemoved = false)) }
    }

    @Test fun removedAdsNeverShowAnywhere() {
        assertFalse(AdsPolicy.canShowOn("home", adsRemoved = true))
    }

    @Test fun unknownRouteHasNoAds() {
        assertFalse(AdsPolicy.canShowOn(null, adsRemoved = false))
        assertFalse(AdsPolicy.canShowOn("something_new", adsRemoved = false))
    }
}
