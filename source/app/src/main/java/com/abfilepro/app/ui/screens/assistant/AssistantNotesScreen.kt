package com.abfilepro.app.ui.screens.assistant

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.abfilepro.app.R
import com.abfilepro.app.assistant.AssistantCategory
import com.abfilepro.app.assistant.AssistantDomain
import com.abfilepro.app.assistant.AssistantEntry
import com.abfilepro.app.assistant.AssistantModule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val NotesRed = Color(0xFFFF3B35)
private val NotesText get() = adaptInk(Color(0xFF071124))
private val NotesSubText get() = adaptInk(Color(0xFF667085))
private val NotesCanvas get() = adaptSurface(Color(0xFFFCFCFD))
private val NotesMint get() = adaptSurface(Color(0xFFEAFBF4))
private val NotesPink get() = adaptSurface(Color(0xFFFFF0F3))
private val NotesBlue get() = adaptSurface(Color(0xFFF0F7FF))
private val NotesPurple get() = adaptSurface(Color(0xFFF5F0FF))
private val NotesCream get() = adaptSurface(Color(0xFFFFF8E8))

/**
 * Alpha54: operational Notes screen with full editor formatting based on page 9 of the approved visual guide.
 *
 * Unlike Alpha52's static visual reference, this screen renders actual stored entries while
 * preserving the guide's hierarchy: search, All/Personal/Work tabs, pinned cards, all notes,
 * overflow actions and the red "Add note" action.
 */
@Composable
internal fun AssistantNotesScreen(
    entries: List<AssistantEntry>,
    defaultCategory: AssistantCategory,
    onBack: () -> Unit,
    onAdd: (String, String, AssistantCategory, Long?, Double?, String) -> Unit,
    onUpdate: (AssistantEntry) -> Unit,
    onDelete: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(AssistantCategory.ALL) }
    var editing by remember { mutableStateOf<AssistantEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AssistantEntry?>(null) }
    var showAllPinned by remember { mutableStateOf(false) }

    val filtered = remember(entries, query, category) {
        val q = query.trim()
        entries
            .asSequence()
            .filter { category == AssistantCategory.ALL || it.category == category }
            .filter {
                q.isBlank() || it.title.contains(q, ignoreCase = true) ||
                    it.details.contains(q, ignoreCase = true)
            }
            .sortedWith(compareByDescending<AssistantEntry> { it.pinned }.thenByDescending { it.createdAt })
            .toList()
    }
    val pinned = filtered.filter { it.pinned }
    val regular = filtered.filterNot { it.pinned }

    if (adding) {
        NoteEditorDialog(
            initial = null,
            defaultCategory = defaultCategory,
            onDismiss = { adding = false },
            onSave = { title, details, noteCategory, styleData ->
                onAdd(title, details, noteCategory, null, null, styleData)
                adding = false
            },
        )
    }
    editing?.let { note ->
        NoteEditorDialog(
            initial = note,
            defaultCategory = note.category,
            onDismiss = { editing = null },
            onSave = { title, details, noteCategory, styleData ->
                onUpdate(note.copy(title = title, details = details, category = noteCategory, secondary = styleData))
                editing = null
            },
        )
    }
    deleting?.let { note ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(L("حذف الملاحظة"), fontWeight = FontWeight.Black) },
            text = { Text(L("هل تريد حذف «${note.title}»؟")) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(note.id)
                    deleting = null
                }) { Text(L("حذف"), color = adaptInk(Color(0xFFD92D20)), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(L("إلغاء")) } },
        )
    }

    Scaffold(
        containerColor = NotesCanvas,
        floatingActionButton = {
            AssistantExtendedFabButton(
                text = L("إضافة ملاحظة"),
                icon = Icons.Default.Add,
                onClick = { adding = true },
                containerColor = NotesRed,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { NotesHeader(onBack = onBack) }
            item { NotesSearch(query = query, onQueryChange = { query = it }) }
            item { NotesCategoryTabs(selected = category, onSelect = { category = it }) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        L(if (showAllPinned) "عرض أقل  ‹" else "عرض الكل  ‹"),
                        modifier = Modifier.clickable(enabled = pinned.size > 2) { showAllPinned = !showAllPinned },
                        color = if (pinned.size > 2) NotesText else NotesSubText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                    Text(L("📌 المثبتة"), color = NotesText, fontWeight = FontWeight.Black, fontSize = 23.sp)
                }
            }

            if (pinned.isEmpty()) {
                item { EmptyPinnedCard() }
            } else {
                item {
                    val visiblePinned = if (showAllPinned) pinned else pinned.take(2)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        visiblePinned.chunked(2).forEachIndexed { rowIndex, rowNotes ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                rowNotes.forEachIndexed { columnIndex, note ->
                                    PinnedNoteCard(
                                        note = note,
                                        index = rowIndex * 2 + columnIndex,
                                        modifier = Modifier.weight(1f),
                                        onEdit = { editing = note },
                                        onTogglePin = { onUpdate(note.copy(pinned = false)) },
                                        onDelete = { deleting = note },
                                    )
                                }
                                if (rowNotes.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    L("كل الملاحظات"),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = NotesText,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.End,
                )
            }

            if (regular.isEmpty()) {
                item { EmptyNotesCard(query = query, category = category) }
            } else {
                itemsIndexed(regular, key = { _, item -> item.id }) { index, note ->
                    NoteListCard(
                        note = note,
                        index = index,
                        onEdit = { editing = note },
                        onTogglePin = { onUpdate(note.copy(pinned = true)) },
                        onDelete = { deleting = note },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotesHeader(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(66.dp)) {
        AssistantHeaderCircleButton(
            icon = Icons.Default.Settings,
            contentDescription = L("الإعدادات"),
            onClick = {},
            modifier = Modifier.align(Alignment.CenterStart),
            accent = true,
        )
        Text(
            L("الملاحظات"),
            modifier = Modifier.align(Alignment.Center),
            color = NotesText,
            fontSize = 29.sp,
            fontWeight = FontWeight.Black,
        )
        AssistantHeaderCircleButton(
            icon = Icons.Default.ArrowBackIosNew,
            contentDescription = L("رجوع"),
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterEnd),
            accent = false,
        )
    }
}

@Composable
private fun NotesSearch(query: String, onQueryChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = { onQueryChange(it.take(120)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(L("ابحث في الملاحظات"), color = adaptInk(Color(0xFF737780)), fontSize = 16.sp) },
        trailingIcon = { Icon(Icons.Default.Search, null, tint = adaptInk(Color(0xFF475467)), modifier = Modifier.size(27.dp)) },
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

@Composable
private fun NotesCategoryTabs(selected: AssistantCategory, onSelect: (AssistantCategory) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listOf(
            AssistantCategory.WORK to L("عمل"),
            AssistantCategory.PERSONAL to L("شخصي"),
            AssistantCategory.ALL to L("الكل"),
        ).forEach { (value, label) ->
            AssistantPillTabButton(
                label = label,
                selected = selected == value,
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f),
                selectedContainerColor = NotesRed,
                selectedContentColor = Color.White,
                unselectedContainerColor = adaptSurface(Color.White),
                unselectedContentColor = NotesText,
            )
        }
    }
}

@Composable
private fun EmptyPinnedCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = adaptSurface(Color(0xFFFFF5F5))),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Text(
            L("يمكنك تثبيت أي ملاحظة من قائمة ⋮ لتظهر هنا."),
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            color = NotesSubText,
            textAlign = TextAlign.End,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun PinnedNoteCard(
    note: AssistantEntry,
    index: Int,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    val tint = if (index % 2 == 0) NotesPink else NotesMint
    Card(
        modifier = modifier.height(246.dp).clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = tint),
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, adaptSurface(Color(0xFFF1D8DA))),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            NoteMenu(
                modifier = Modifier.align(Alignment.TopStart),
                pinned = true,
                onEdit = onEdit,
                onTogglePin = onTogglePin,
                onDelete = onDelete,
            )
            Text("📌", modifier = Modifier.align(Alignment.TopEnd), fontSize = 22.sp)
            Image(
                painter = painterResource(if (index % 2 == 0) R.drawable.pa_notes_bulb else R.drawable.pa_notes_house),
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 17.dp).size(118.dp, 88.dp).clip(RoundedCornerShape(18.dp)),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(),
                horizontalAlignment = Alignment.End,
            ) {
                Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = NotesText, fontSize = 18.sp, fontWeight = FontWeight.Black)
                if (note.details.isNotBlank()) {
                    val noteStyle = decodeNoteStyle(note.secondary)
                    Text(
                        note.details,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = noteTextColor(noteStyle.colorKey),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontFamily = noteFontFamily(noteStyle.fontKey),
                        textAlign = TextAlign.End,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(formatNoteDate(note.createdAt), color = adaptInk(Color(0xFF7D8491)), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun NoteListCard(
    note: AssistantEntry,
    index: Int,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    val images = intArrayOf(
        R.drawable.pa_notes_group,
        R.drawable.pa_notes_cart,
        R.drawable.pa_notes_book,
        R.drawable.pa_notes_trip,
    )
    val tints = listOf(NotesBlue, NotesCream, NotesMint, NotesPurple)
    Card(
        modifier = Modifier.fillMaxWidth().height(126.dp).clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoteMenu(
                pinned = false,
                onEdit = onEdit,
                onTogglePin = onTogglePin,
                onDelete = onDelete,
            )
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
            ) {
                Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = NotesText, fontSize = 18.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.End)
                if (note.details.isNotBlank()) {
                    val noteStyle = decodeNoteStyle(note.secondary)
                    Text(
                        note.details,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = noteTextColor(noteStyle.colorKey),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontFamily = noteFontFamily(noteStyle.fontKey),
                        textAlign = TextAlign.End,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(formatNoteDate(note.createdAt), color = adaptInk(Color(0xFF8A909A)), fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = tints[index % tints.size]) {
                Image(
                    painter = painterResource(images[index % images.size]),
                    contentDescription = null,
                    modifier = Modifier.size(82.dp).padding(4.dp).clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@Composable
private fun NoteMenu(
    modifier: Modifier = Modifier,
    pinned: Boolean,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(shape = CircleShape, color = Color.White.copy(alpha = .78f)) {
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = L("خيارات"), tint = NotesText)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(L("تعديل")) },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { expanded = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text(L(if (pinned) "إلغاء التثبيت" else "تثبيت")) },
                leadingIcon = { Icon(Icons.Default.PushPin, null) },
                onClick = { expanded = false; onTogglePin() },
            )
            DropdownMenuItem(
                text = { Text(L("حذف"), color = adaptInk(Color(0xFFD92D20))) },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = adaptInk(Color(0xFFD92D20))) },
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}

@Composable
private fun EmptyNotesCard(query: String, category: AssistantCategory) {
    val message = when {
        query.isNotBlank() -> L("لا توجد نتائج مطابقة للبحث.")
        category == AssistantCategory.PERSONAL -> L("لا توجد ملاحظات شخصية بعد.")
        category == AssistantCategory.WORK -> L("لا توجد ملاحظات عمل بعد.")
        else -> L("لا توجد ملاحظات بعد. اضغط «إضافة ملاحظة» للبدء.")
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("📝", fontSize = 38.sp)
            Spacer(Modifier.height(8.dp))
            Text(message, color = NotesSubText, textAlign = TextAlign.Center, fontSize = 14.sp)
        }
    }
}

@Composable
private fun NoteEditorDialog(
    initial: AssistantEntry?,
    defaultCategory: AssistantCategory,
    onDismiss: () -> Unit,
    onSave: (String, String, AssistantCategory, String) -> Unit,
) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var details by remember(initial?.id) { mutableStateOf(initial?.details.orEmpty()) }
    var category by remember(initial?.id) {
        mutableStateOf(AssistantDomain.sanitizeCategory(AssistantModule.NOTES, initial?.category ?: defaultCategory))
    }
    var style by remember(initial?.id) { mutableStateOf(decodeNoteStyle(initial?.secondary.orEmpty())) }
    var fontMenuExpanded by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
            shape = RoundedCornerShape(26.dp),
            color = NotesCanvas,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(L("إلغاء"), color = NotesSubText, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        L(if (initial == null) "ملاحظة جديدة" else "تعديل الملاحظة"),
                        color = NotesText,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Button(
                        onClick = { onSave(title.trim(), details.trim(), category, encodeNoteStyle(style)) },
                        enabled = title.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = NotesRed),
                        shape = RoundedCornerShape(18.dp),
                    ) { Text(L("حفظ"), fontWeight = FontWeight.Bold) }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(240) },
                    label = { Text(L("عنوان الملاحظة")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(1.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(L("تنسيق الكتابة"), color = NotesText, fontWeight = FontWeight.Black, fontSize = 15.sp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                modifier = Modifier.height(40.dp).clickable {
                                    style = style.copy(sizeSp = (style.sizeSp - 2).coerceAtLeast(14))
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = adaptSurface(Color(0xFFF2F4F7)),
                            ) {
                                Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                                    Text("−", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Surface(
                                modifier = Modifier.height(40.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = NotesPink,
                            ) {
                                Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                                    Text("${style.sizeSp}", color = NotesText, fontWeight = FontWeight.Black)
                                }
                            }
                            Surface(
                                modifier = Modifier.height(40.dp).clickable {
                                    style = style.copy(sizeSp = (style.sizeSp + 2).coerceAtMost(30))
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = adaptSurface(Color(0xFFF2F4F7)),
                            ) {
                                Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                                    Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Box(Modifier.weight(1f)) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().height(40.dp).clickable { fontMenuExpanded = true },
                                    shape = RoundedCornerShape(14.dp),
                                    color = NotesBlue,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(noteFontLabel(style.fontKey), fontFamily = noteFontFamily(style.fontKey), fontWeight = FontWeight.Bold)
                                    }
                                }
                                DropdownMenu(
                                    expanded = fontMenuExpanded,
                                    onDismissRequest = { fontMenuExpanded = false },
                                ) {
                                    listOf("sans", "serif", "mono").forEach { key ->
                                        DropdownMenuItem(
                                            text = { Text(noteFontLabel(key), fontFamily = noteFontFamily(key)) },
                                            onClick = { style = style.copy(fontKey = key); fontMenuExpanded = false },
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(L("اللون"), color = NotesSubText, fontWeight = FontWeight.Bold)
                            noteColorOptions.forEach { (key, color) ->
                                Surface(
                                    modifier = Modifier.size(32.dp).clickable { style = style.copy(colorKey = key) },
                                    shape = CircleShape,
                                    color = color,
                                    border = BorderStroke(
                                        width = if (style.colorKey == key) 3.dp else 1.dp,
                                        color = if (style.colorKey == key) NotesRed else adaptSurface(Color(0xFFD0D5DD)),
                                    ),
                                ) {}
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(4000) },
                    label = { Text(L("اكتب ملاحظتك هنا")) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = TextStyle(
                        color = noteTextColor(style.colorKey),
                        fontSize = style.sizeSp.sp,
                        fontFamily = noteFontFamily(style.fontKey),
                        lineHeight = (style.sizeSp + 8).sp,
                        textAlign = TextAlign.End,
                    ),
                    minLines = 10,
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoteCategoryChoice(
                        L("شخصي"),
                        category == AssistantCategory.PERSONAL,
                        Modifier.weight(1f),
                    ) { category = AssistantCategory.PERSONAL }
                    NoteCategoryChoice(
                        L("عمل"),
                        category == AssistantCategory.WORK,
                        Modifier.weight(1f),
                    ) { category = AssistantCategory.WORK }
                }
            }
        }
    }
}

private data class NoteEditorStyle(
    val sizeSp: Int = 18,
    val fontKey: String = "sans",
    val colorKey: String = "dark",
)

private val noteColorOptions get() = listOf(
    "dark" to adaptInk(Color(0xFF071124)),
    "red" to Color(0xFFD92D20),
    "blue" to Color(0xFF175CD3),
    "green" to Color(0xFF067647),
    "purple" to Color(0xFF6938EF),
)

private fun noteTextColor(key: String): Color =
    noteColorOptions.firstOrNull { it.first == key }?.second ?: NotesText

private fun noteFontFamily(key: String): FontFamily = when (key) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

private fun noteFontLabel(key: String): String = when (key) {
    "serif" -> L("كلاسيكي")
    "mono" -> L("ثابت")
    else -> L("عادي")
}

private fun encodeNoteStyle(style: NoteEditorStyle): String =
    "abnote:v1|size=${style.sizeSp.coerceIn(14, 30)}|font=${style.fontKey}|color=${style.colorKey}"

private fun decodeNoteStyle(raw: String): NoteEditorStyle {
    if (!raw.startsWith("abnote:v1|")) return NoteEditorStyle()
    val values = raw.removePrefix("abnote:v1|")
        .split('|')
        .mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2) pair[0] to pair[1] else null
        }
        .toMap()
    return NoteEditorStyle(
        sizeSp = values["size"]?.toIntOrNull()?.coerceIn(14, 30) ?: 18,
        fontKey = values["font"].takeIf { it in setOf("sans", "serif", "mono") } ?: "sans",
        colorKey = values["color"].takeIf { key -> noteColorOptions.any { it.first == key } } ?: "dark",
    )
}

@Composable
private fun NoteCategoryChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(42.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = if (selected) NotesRed else adaptSurface(Color(0xFFF2F4F7)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) Color.White else NotesText, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatNoteDate(epochMillis: Long): String = runCatching {
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ofPattern("d MMMM yyyy", assistantLocale()))
}.getOrElse { "" }
