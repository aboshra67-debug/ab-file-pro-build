package com.abfilepro.app.ads

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** P26: the direct (APK) channel has no ads at all. */
object AdsHost {
    fun start(activity: Activity) = Unit
}

@Composable
fun AdSlot(modifier: Modifier = Modifier) = Unit
