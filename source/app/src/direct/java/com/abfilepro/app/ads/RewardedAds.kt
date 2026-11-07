package com.abfilepro.app.ads

import android.app.Activity
import android.content.Context

/** P33: the direct (APK) channel has no ads, so there are no rewarded videos and no points. */
object RewardedAds {
    const val supported = false
    val ready: Boolean get() = false
    fun preload(context: Context) = Unit
    fun show(activity: Activity, onEarned: () -> Unit): Boolean = false
}
