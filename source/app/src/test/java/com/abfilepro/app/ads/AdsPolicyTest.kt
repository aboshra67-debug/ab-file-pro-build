package com.abfilepro.app.ads

import org.junit.Assert.assertFalse
import org.junit.Test

class AdsPolicyTest {
    // P36: the home banner was removed on purpose; the only ad left is the optional rewarded video.
    @Test fun homeHasNoBannerAnymore() {
        assertFalse(AdsPolicy.canShowOn("home", adsRemoved = false))
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

    @Test fun assistantOverlayOnHomeNeverShowsAds() {
        assertFalse(AdsPolicy.canShowOn("home", adsRemoved = false, privateOverlayOpen = true))
        assertFalse(AdsPolicy.canShowOn("home", adsRemoved = false, privateOverlayOpen = false))
    }

    @Test fun overlayFlagDefaultsToClosedSoOldCallersKeepWorking() {
        assertFalse(AdsPolicy.canShowOn("home", adsRemoved = false))
    }
}
