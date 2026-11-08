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
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/**
 * P36: the only thing points show on ordinary screens. A small balance chip that sits in the
 * status-bar strip (so it never covers app content) and opens Settings when tapped.
 */
@Composable
fun PointsChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (!PointsState.active) return
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val colors = MaterialTheme.colorScheme
    val strip = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    if (strip < 18.dp) return
    val chipHeight = (strip - 4.dp).coerceIn(18.dp, 24.dp)
    val label = context.uiText(language, "p36_cost_points", PointsState.balance)
    val description = context.uiText(language, "p36_chip_desc")
    Box(modifier.fillMaxWidth().height(strip), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = if (PointsState.balance < 5) colors.errorContainer else colors.primaryContainer,
            modifier = Modifier.height(chipHeight).semantics { contentDescription = description }
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Filled.Stars, contentDescription = null, modifier = Modifier.size(14.dp),
                    tint = if (PointsState.balance < 5) colors.onErrorContainer else colors.primary
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (PointsState.balance < 5) colors.onErrorContainer else colors.onPrimaryContainer,
                    maxLines = 1
                )
            }
        }
    }
}

/** P36: the full points area in Settings: balance, video reward, how it works, upgrade. */
@Composable
fun PointsSettingsSection() {
    if (!PointsState.active) return
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    PreloadRewardedVideo()
    val watch = rememberWatchVideo()
    val videoReady = RewardedAds.ready && PointsState.adsLeftToday() > 0
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Balance
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.secondaryContainer.copy(alpha = .75f))))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(colors.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Stars, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(30.dp))
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

        // Watch a video
        Button(
            onClick = watch,
            enabled = videoReady,
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)
        ) {
            Icon(Icons.Filled.PlayCircle, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(videoButtonTitle(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                if (videoReady) {
                    Text(
                        context.uiText(language, "p35_video_reward", PointsPolicy.AD_REWARD),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }
            Text(
                context.uiText(language, "p36_videos_left", PointsState.adsLeftToday(), PointsPolicy.MAX_ADS_PER_DAY),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }

        // How points work
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(context.uiText(language, "p36_rules_title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                RuleRow(context.uiText(language, "p36_rule_page"), context.uiText(language, "p36_cost_points", PointsPolicy.COST_PER_PAGE))
                RuleRow(context.uiText(language, "p36_rule_translate"), context.uiText(language, "p36_cost_points", PointsPolicy.COST_TRANSLATE))
                RuleRow(context.uiText(language, "p36_rule_daily"), context.uiText(language, "p36_cost_points", PointsPolicy.DAILY_BONUS))
                Text(
                    context.uiText(language, "p36_free_always"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }

        // Upgrade
        OutlinedButton(
            onClick = { ProState.requestPaywall() },
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) {
            Icon(Icons.Filled.WorkspacePremium, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(context.uiText(language, "p33_go_pro"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun RuleRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

/** Short title for the video button; the reward and the "left today" counter are separate texts. */
@Composable
private fun videoButtonTitle(): String {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    return when {
        PointsState.adsLeftToday() <= 0 -> context.uiText(language, "p33_no_videos_left")
        !RewardedAds.ready -> context.uiText(language, "p33_video_loading")
        else -> context.uiText(language, "p35_watch_short")
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
