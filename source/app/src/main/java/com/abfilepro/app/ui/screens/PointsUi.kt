package com.abfilepro.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abfilepro.app.ads.RewardedAds
import com.abfilepro.app.billing.PointsState
import com.abfilepro.app.billing.ProState
import com.abfilepro.app.core.PointsPolicy
import com.abfilepro.app.core.findActivity
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import kotlinx.coroutines.delay

/** P33: asks Google for the rewarded video a few times (it needs the consent form to finish first). */
@Composable
private fun PreloadRewardedVideo() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        repeat(12) {
            if (RewardedAds.ready) return@LaunchedEffect
            RewardedAds.preload(context)
            delay(5_000)
        }
    }
}

/** Starts the video; the reward is added only when Google confirms it was earned. */
@Composable
private fun rememberWatchVideo(): () -> Unit {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    return {
        val activity = context.findActivity()
        val started = activity != null && RewardedAds.show(activity) {
            PointsState.earnFromAd(context)
            Toast.makeText(context, context.uiText(language, "p33_earned", PointsPolicy.AD_REWARD), Toast.LENGTH_SHORT).show()
        }
        if (!started) Toast.makeText(context, context.uiText(language, "p33_video_not_ready"), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun watchVideoLabel(): String {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    return when {
        PointsState.adsLeftToday() <= 0 -> context.uiText(language, "p33_no_videos_left")
        !RewardedAds.ready -> context.uiText(language, "p33_video_loading")
        else -> context.uiText(language, "p33_watch_video", PointsPolicy.AD_REWARD)
    }
}

/** Balance + the two ways to get more points. Shows nothing for Pro, "remove ads" buyers or the direct APK. */
@Composable
fun PointsBalanceCard(modifier: Modifier = Modifier) {
    if (!PointsState.active) return
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    PreloadRewardedVideo()
    val watch = rememberWatchVideo()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .38f))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                context.uiText(language, "p33_balance", PointsState.balance),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                context.uiText(language, "p33_rules", PointsPolicy.COST_PER_PAGE, PointsPolicy.COST_TRANSLATE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (RewardedAds.supported) {
                    Button(
                        onClick = watch,
                        enabled = RewardedAds.ready && PointsState.adsLeftToday() > 0,
                        modifier = Modifier.weight(1f)
                    ) { Text(watchVideoLabel(), maxLines = 2) }
                }
                OutlinedButton(onClick = { ProState.requestPaywall() }, modifier = Modifier.weight(1f)) {
                    Text(context.uiText(language, "p33_go_pro"), maxLines = 2)
                }
            }
        }
    }
}

/** Shown instead of saving when the balance is too low. Closes by itself once enough points were earned. */
@Composable
fun PointsGateDialog(need: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    PreloadRewardedVideo()
    val watch = rememberWatchVideo()
    LaunchedEffect(PointsState.balance) { if (PointsState.balance >= need) onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.uiText(language, "p33_gate_title")) },
        text = { Text(context.uiText(language, "p33_gate_text", need, PointsState.balance)) },
        confirmButton = {
            if (RewardedAds.supported) {
                TextButton(onClick = watch, enabled = RewardedAds.ready && PointsState.adsLeftToday() > 0) {
                    Text(watchVideoLabel())
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { ProState.requestPaywall(); onDismiss() }) { Text(context.uiText(language, "p33_go_pro")) }
                TextButton(onClick = onDismiss) { Text(context.uiText(language, "p22_ok")) }
            }
        }
    )
}
