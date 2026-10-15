package com.abfilepro.app.ui.screens.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val AssistantPrimaryNavy = Color(0xFF173B65)
internal val AssistantAccentRed = Color(0xFFFF4D4F)
internal val AssistantBorderGray = Color(0xFFD9E2EC)
internal val AssistantTextDark = Color(0xFF071124)

@Composable
internal fun AssistantTopStripButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    Surface(
        modifier = modifier
            .height(52.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        color = if (primary) AssistantPrimaryNavy else Color.White,
        border = if (primary) null else BorderStroke(1.dp, AssistantBorderGray),
        shadowElevation = if (primary) 6.dp else 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (primary) Color.White else AssistantPrimaryNavy,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                color = if (primary) Color.White else AssistantPrimaryNavy,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun AssistantHeaderCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Surface(
        modifier = modifier
            .size(52.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.White,
        border = BorderStroke(1.dp, if (accent) Color(0xFFF4D9DC) else AssistantBorderGray),
        shadowElevation = 5.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (accent) Color(0xFF9B1C1C) else AssistantTextDark,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
internal fun AssistantPillTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedContainerColor: Color = Color(0xFFECE7F6),
    selectedContentColor: Color = AssistantTextDark,
    unselectedContainerColor: Color = Color.White,
    unselectedContentColor: Color = AssistantTextDark,
    showCheckmark: Boolean = false,
) {
    Surface(
        modifier = modifier
            .height(52.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        color = if (selected) selectedContainerColor else unselectedContainerColor,
        border = BorderStroke(1.dp, if (selected) selectedContainerColor else AssistantBorderGray),
        shadowElevation = if (selected) 3.dp else 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (selected && showCheckmark) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = selectedContentColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                color = if (selected) selectedContentColor else unselectedContentColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun AssistantExtendedFabButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = AssistantAccentRed,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier.height(58.dp),
        containerColor = containerColor,
        contentColor = Color.White,
        shape = RoundedCornerShape(22.dp),
        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
        text = { Text(text, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
    )
}
