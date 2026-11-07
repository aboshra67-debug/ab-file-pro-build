package com.abfilepro.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
    val videoReady = RewardedAds.ready && PointsState.adsLeftToday() > 0
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.secondaryContainer.copy(alpha = .75f))))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(colors.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Stars, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    context.uiText(language, "p35_balance_label"),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onPrimaryContainer.copy(alpha = .75f)
                )
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        context.uiText(language, "p35_number", PointsState.balance),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.onPrimaryContainer
                    )
                    Text(
                        context.uiText(language, "p35_points_unit"),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onPrimaryContainer,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (RewardedAds.supported) {
                Button(
                    onClick = watch,
                    enabled = videoReady,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 52.dp)
                ) {
                    Icon(Icons.Filled.PlayCircle, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(watchVideoLabel(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (videoReady) {
                            Text(
                                context.uiText(language, "p35_video_reward", PointsPolicy.AD_REWARD),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { ProState.requestPaywall() },
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 52.dp)
            ) {
                Icon(Icons.Filled.WorkspacePremium, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(context.uiText(language, "p33_go_pro"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 2)
            }
        }
        Text(
            context.uiText(language, "p33_rules", PointsPolicy.COST_PER_PAGE, PointsPolicy.COST_TRANSLATE),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onPrimaryContainer.copy(alpha = .75f)
        )
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
