package com.abfilepro.app.ads

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.abfilepro.app.BuildConfig
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P26 (Play channel only): Google's consent form first, ads SDK only after consent allows ads.
 * A person who removed ads never even starts the SDK.
 */
object AdsHost {
    private val sdkStarted = AtomicBoolean(false)
    private val consentAsked = AtomicBoolean(false)
    internal var ready by mutableStateOf(false)
        private set

    fun start(activity: Activity) {
        if (AdsSettings.removed(activity)) return
        if (!consentAsked.compareAndSet(false, true)) return
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        if (consent.canRequestAds()) startSdk(activity)
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    if (consent.canRequestAds()) startSdk(activity)
                }
            },
            { if (consent.canRequestAds()) startSdk(activity) },
        )
    }

    private fun startSdk(activity: Activity) {
        if (!sdkStarted.compareAndSet(false, true)) return
        MobileAds.initialize(activity.applicationContext) { ready = true }
    }
}

/** Takes no space until an ad has really loaded, so the screen never shows an empty gap. */
@Composable
fun AdSlot(modifier: Modifier = Modifier) {
    if (!AdsHost.ready) return
    var loaded by remember { mutableStateOf(false) }
    val widthDp = LocalConfiguration.current.screenWidthDp
    AndroidView(
        modifier = modifier.fillMaxWidth().then(if (loaded) Modifier else Modifier.height(0.dp)),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp))
                adUnitId = BuildConfig.ADMOB_BANNER_ID
                adListener = object : AdListener() {
                    override fun onAdLoaded() { loaded = true }
                    override fun onAdFailedToLoad(error: LoadAdError) { loaded = false }
                }
                loadAd(AdRequest.Builder().build())
            }
        },
        onRelease = { it.destroy() },
    )
}
