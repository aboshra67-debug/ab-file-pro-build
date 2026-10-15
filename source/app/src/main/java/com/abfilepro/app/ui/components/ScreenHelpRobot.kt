package com.abfilepro.app.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abfilepro.app.R
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText

private const val PREFS_NAME = "ab_file_pro"
private const val PREF_SHOW_AI_HELP = "show_ai_help"

/**
 * Lightweight, screen-specific help entry point.
 * It is intentionally read-only: no screen action or processing logic is changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenHelpRobot(
    titleKey: String,
    bodyKey: String
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    val prefs = remember(context) { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val showRobot = prefs.getBoolean(PREF_SHOW_AI_HELP, true)
    var showHelp by remember { mutableStateOf(false) }

    if (!showRobot) return

    SmallFloatingActionButton(
        onClick = { showHelp = true },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = CircleShape
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ab_help_robot),
            contentDescription = context.uiText(language, "home_ai_help")
        )
    }

    if (showHelp) {
        ModalBottomSheet(onDismissRequest = { showHelp = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 8.dp)
            ) {
                Text(
                    context.uiText(language, titleKey),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 21.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    context.uiText(language, bodyKey),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp
                )
                Spacer(Modifier.height(14.dp))
                TextButton(
                    onClick = { showHelp = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(context.uiText(language, "screen_help_close"))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
