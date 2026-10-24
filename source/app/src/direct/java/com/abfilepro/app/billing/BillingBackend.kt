package com.abfilepro.app.billing

import android.app.Activity
import android.content.Context

/** P28: the direct (APK) channel has no purchases and no limits. */
object BillingBackend {
    const val available = false
    val offers: List<ProOffer> = emptyList()
    val message: String = ""

    fun start(context: Context) = Unit
    fun launch(activity: Activity, productId: String) = Unit
    fun restore() = Unit
}
