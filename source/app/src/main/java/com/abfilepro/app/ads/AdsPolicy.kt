package com.abfilepro.app.ads

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * P26: where ads may appear. Only the home screen carries a banner. Every screen that handles
 * the person's documents, scans, health, money, family or reminders is deliberately NOT on the list,
 * and a new screen is ad-free until it is added here on purpose.
 */
object AdsPolicy {
    private val SHOW_ON = setOf("home")

    /**
     * [privateOverlayOpen] is true while a private surface (the personal assistant: money, health,
     * family, reminders) is drawn on top of an allowed route. The route name does not change then,
     * so the overlay has to say so itself.
     */
    fun canShowOn(route: String?, adsRemoved: Boolean, privateOverlayOpen: Boolean = false): Boolean {
        if (adsRemoved || privateOverlayOpen || route == null) return false
        return route.substringBefore('?') in SHOW_ON
    }
}

/** P32: set by a screen that opens a private overlay on the home route, read by the ad host. */
object AdsOverlayState {
    var privateOverlayOpen by mutableStateOf(false)
}

/** "Ads removed" is a local entitlement; a later Play Billing purchase will set it. */
object AdsSettings {
    const val PREFS = "ab_file_pro_ads"
    const val KEY_REMOVED = "ads_removed"

    fun removed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REMOVED, false)

    fun setRemoved(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_REMOVED, value).apply()
    }
}
