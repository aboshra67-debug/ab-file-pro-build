package com.abfilepro.app.ads

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abfilepro.app.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * P33 (Play channel only): an optional rewarded video the person starts on purpose to earn points.
 * It loads only after Google's consent form allowed ads (AdsHost.ready) and is never shown by itself.
 */
object RewardedAds {
    const val supported = true

    var ready by mutableStateOf(false)
        private set
    private var ad: RewardedAd? = null
    private var loading = false

    fun preload(context: Context) {
        if (!AdsHost.ready || loading || ad != null) return
        loading = true
        RewardedAd.load(
            context.applicationContext,
            BuildConfig.ADMOB_REWARDED_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(loaded: RewardedAd) {
                    ad = loaded
                    loading = false
                    ready = true
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    ad = null
                    loading = false
                    ready = false
                }
            },
        )
    }

    /** Shows the loaded video; [onEarned] runs only when Google says the reward was earned. */
    fun show(activity: Activity, onEarned: () -> Unit): Boolean {
        val current = ad ?: return false
        ad = null
        ready = false
        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = preload(activity)
            override fun onAdFailedToShowFullScreenContent(error: AdError) = preload(activity)
        }
        current.show(activity) { onEarned() }
        return true
    }
}
