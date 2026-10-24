package com.abfilepro.app.billing

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abfilepro.app.BuildConfig
import com.abfilepro.app.ads.AdsSettings

/**
 * P28: the person's current Pro state, readable from any screen and observed by Compose.
 *
 * Only the Google Play channel has purchases and limits. The direct (APK) channel has no billing, so
 * nothing is limited there. Test builds (never the release build) can unlock Pro locally to try the
 * limits and the paywall without a Play Console product.
 */
object ProState {
    private const val PREFS = "ab_file_pro_entitlement"
    private const val KEY_PRO = "purchased_pro"
    private const val KEY_ADS = "purchased_ads_removed"
    private const val KEY_TRIAL = "trial_unlock"

    private var purchasedPro by mutableStateOf(false)
    private var purchasedAdsRemoved by mutableStateOf(false)
    var trialUnlock by mutableStateOf(false)
        private set

    /** Bumped when a limit stops the person, so the app can open the paywall. */
    var paywallRequests by mutableStateOf(0)
        private set

    val gatingActive: Boolean get() = BuildConfig.DISTRIBUTION_CHANNEL == "play"
    val trialUnlockAvailable: Boolean get() = BuildConfig.BUILD_TYPE != "release"

    val pro: Boolean get() = !gatingActive || purchasedPro || (trialUnlockAvailable && trialUnlock)
    val adsRemoved: Boolean get() = !gatingActive || purchasedAdsRemoved || purchasedPro || (trialUnlockAvailable && trialUnlock)

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        purchasedPro = p.getBoolean(KEY_PRO, false)
        purchasedAdsRemoved = p.getBoolean(KEY_ADS, false) || AdsSettings.removed(context)
        trialUnlock = trialUnlockAvailable && p.getBoolean(KEY_TRIAL, false)
    }

    /** Called by the billing backend with what Google Play says the person owns right now. */
    fun apply(context: Context, entitlement: Entitlement) {
        purchasedPro = entitlement.pro
        purchasedAdsRemoved = entitlement.adsRemoved
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PRO, entitlement.pro).putBoolean(KEY_ADS, entitlement.adsRemoved).apply()
        AdsSettings.setRemoved(context, entitlement.adsRemoved)
    }

    fun setTrialUnlock(context: Context, on: Boolean) {
        if (!trialUnlockAvailable) return
        trialUnlock = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TRIAL, on).apply()
    }

    /** True when one more item of [feature] may be added; otherwise the paywall is requested. */
    fun allowOrAsk(feature: ProFeature, currentCount: Int): Boolean {
        val ok = ProPolicy.allows(feature, currentCount, pro)
        if (!ok) paywallRequests++
        return ok
    }

    fun allowValueOrAsk(feature: ProFeature, wanted: Int): Boolean {
        val ok = ProPolicy.allowsValue(feature, wanted, pro)
        if (!ok) paywallRequests++
        return ok
    }

    fun requestPaywall() { paywallRequests++ }
}
