package com.abfilepro.app.ads

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
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

    /** Google requires a way to reopen the consent choices where the person's region asks for it. */
    fun privacyOptionsRequired(context: android.content.Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).privacyOptionsRequirementStatus ==
            com.google.android.ump.ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { }
    }

    private fun startSdk(activity: Activity) {
        if (!sdkStarted.compareAndSet(false, true)) return
        MobileAds.initialize(activity.applicationContext) { ready = true }
    }
}

/**
 * Takes no space until an ad has really loaded, so the screen never shows an empty gap.
 * P35: the banner sits in a rounded, bordered frame with side margins and breathing room above the
 * system buttons, with a small "Ad" label so it is never mistaken for app content.
 */
@Composable
fun AdSlot(modifier: Modifier = Modifier) {
    if (!AdsHost.ready) return
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var loaded by remember { mutableStateOf(false) }
    // Margins (12dp each side) and the 1dp border are taken out of the width Google sizes the ad to.
    val widthDp = LocalConfiguration.current.screenWidthDp - 2 * AD_SIDE_MARGIN_DP - 2
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (loaded) Modifier.padding(start = AD_SIDE_MARGIN_DP.dp, end = AD_SIDE_MARGIN_DP.dp, top = 4.dp, bottom = 10.dp) else Modifier.height(0.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loaded) {
            Text(
                context.uiText(language, "p35_ad_label"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            AndroidView(
                modifier = if (loaded) Modifier else Modifier.height(0.dp),
                factory = { ctx ->
                    AdView(ctx).apply {
                        setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, widthDp))
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
    }
}

private const val AD_SIDE_MARGIN_DP = 12
