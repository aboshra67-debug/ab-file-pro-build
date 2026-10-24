package com.abfilepro.app.ui.screens.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abfilepro.app.assistant.Recurrence

internal fun recurrenceLabel(rule: Recurrence): String = when (rule) {
    Recurrence.NONE -> L("لا يتكرر")
    Recurrence.DAILY -> L("يوميًا")
    Recurrence.WEEKLY -> L("أسبوعيًا")
    Recurrence.MONTHLY -> L("شهريًا")
}

/** P27: how the task or appointment repeats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecurrenceChips(selected: Recurrence, onSelect: (Recurrence) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Recurrence.entries.forEach { rule ->
            FilterChip(selected == rule, { onSelect(rule) }, { Text(recurrenceLabel(rule), maxLines = 1) })
        }
    }
}
