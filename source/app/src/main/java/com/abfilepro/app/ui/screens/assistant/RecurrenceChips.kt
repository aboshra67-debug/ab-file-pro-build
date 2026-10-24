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
import com.abfilepro.app.assistant.RecurrenceStore
import com.abfilepro.app.billing.ProFeature
import com.abfilepro.app.billing.ProState

internal fun recurrenceLabel(rule: Recurrence): String = when (rule) {
    Recurrence.NONE -> L("لا يتكرر")
    Recurrence.DAILY -> L("يوميًا")
    Recurrence.WEEKLY -> L("أسبوعيًا")
    Recurrence.MONTHLY -> L("شهريًا")
}

/** P27: how the task or appointment repeats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecurrenceChips(selected: Recurrence, currentId: String?, onSelect: (Recurrence) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Recurrence.entries.forEach { rule ->
            FilterChip(selected == rule, {
                // Free: a few repeating items. Turning repeat on for another one needs Pro; keeping or changing an existing one never does.
                val ids = RecurrenceStore.ids(context)
                val startsRepeating = rule != Recurrence.NONE && selected == Recurrence.NONE && (currentId == null || currentId !in ids)
                if (!startsRepeating || ProState.allowOrAsk(ProFeature.REPEATING_ITEMS, ids.size)) onSelect(rule)
            }, { Text(recurrenceLabel(rule), maxLines = 1) })
        }
    }
}
