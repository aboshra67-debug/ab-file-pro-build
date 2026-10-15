package com.abfilepro.app.ui.screens.assistant

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.abfilepro.app.assistant.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DocsRed = Color(0xFFFF2D32)
private val DocsGreen = Color(0xFF12A86B)
private val DocsAmber = Color(0xFFF2A800)
private val DocsInk = Color(0xFF111827)
private val DocsMuted = Color(0xFF667085)
private enum class DocumentFilter { ALL, SOON, EXPIRED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentsRenewalsScreen(
    entries: List<AssistantEntry>,
    onBack: () -> Unit,
    onAdd: (String, String, AssistantCategory, Long?, Double?, String) -> Unit,
    onUpdate: (AssistantEntry) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DocumentFilter.ALL) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AssistantEntry?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun ensureReminderAccess() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !DocumentRenewalAlarmScheduler.hasExactAlarmAccess(context)) {
            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }
        }
    }

    val docs = entries.sortedBy { it.dueAt ?: Long.MAX_VALUE }
    val visible = docs.filter { entry ->
        val meta = DocumentRenewalCodec.parse(entry.secondary)
        val q = query.isBlank() || entry.title.contains(query, true) || entry.details.contains(query, true) || meta.displayKindName().contains(query, true) || meta.country.contains(query, true)
        val status = DocumentRenewalCodec.status(entry)
        q && when (filter) {
            DocumentFilter.ALL -> true
            DocumentFilter.SOON -> status == DocumentExpiryStatus.SOON
            DocumentFilter.EXPIRED -> status == DocumentExpiryStatus.EXPIRED
        }
    }
    val near = docs.firstOrNull { DocumentRenewalCodec.status(it) == DocumentExpiryStatus.SOON }

    Scaffold(
        containerColor = Color(0xFFFCFCFD),
        topBar = {
            TopAppBar(
                title = { Text("المستندات والتجديدات", fontWeight = FontWeight.Black, fontSize = 26.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFE9FFF5))) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, tint = DocsGreen, modifier = Modifier.size(46.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text("محفوظة بأمان على جهازك", fontWeight = FontWeight.Black, fontSize = 20.sp, color = DocsInk)
                            Text("بياناتك تبقى في جهازك فقط ولا يتم مشاركتها مع أي جهة.", color = DocsMuted, textAlign = TextAlign.Right)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("ابحث عن مستند...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = RoundedCornerShape(28.dp),
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(DocumentFilter.EXPIRED to "منتهي", DocumentFilter.SOON to "قريب", DocumentFilter.ALL to "الكل").forEach { (value, label) ->
                        val active = value == filter
                        Surface(
                            modifier = Modifier.weight(1f).clickable { filter = value },
                            shape = RoundedCornerShape(22.dp),
                            color = if (active) DocsRed else Color(0xFFF5F5F6),
                        ) {
                            Text(label, modifier = Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = if (active) Color.White else DocsInk)
                        }
                    }
                }
            }
            near?.let { entry ->
                item {
                    Card(modifier = Modifier.fillMaxWidth().clickable { editing = entry }, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0F0))) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NotificationsActive, null, tint = DocsRed, modifier = Modifier.size(46.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                val days = DocumentRenewalCodec.daysUntilExpiry(entry) ?: 0
                                Text("تجديد قريب خلال $days يومًا", fontWeight = FontWeight.Black, fontSize = 20.sp, color = DocsRed)
                                Text(entry.title, fontWeight = FontWeight.Bold, color = DocsInk)
                                Text("يرجى التجديد في الوقت المناسب.", color = DocsMuted)
                            }
                        }
                    }
                }
            }
            if (visible.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Text(if (docs.isEmpty()) "لا توجد مستندات محفوظة" else "لا توجد نتائج مطابقة", modifier = Modifier.fillMaxWidth().padding(34.dp), textAlign = TextAlign.Center, color = DocsMuted)
                    }
                }
            } else {
                items(visible, key = { it.id }) { entry ->
                    val meta = DocumentRenewalCodec.parse(entry.secondary)
                    val status = DocumentRenewalCodec.status(entry)
                    val statusText = when (status) { DocumentExpiryStatus.VALID -> "ساري"; DocumentExpiryStatus.SOON -> "قريب"; DocumentExpiryStatus.EXPIRED -> "منتهي" }
                    val statusColor = when (status) { DocumentExpiryStatus.VALID -> DocsGreen; DocumentExpiryStatus.SOON -> DocsAmber; DocumentExpiryStatus.EXPIRED -> DocsRed }
                    Card(modifier = Modifier.fillMaxWidth().clickable { editing = entry }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Description, null, tint = statusColor, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                Text(entry.title, fontWeight = FontWeight.Black, fontSize = 19.sp, color = DocsInk)
                                val kindLine = buildString {
                                    append(meta.displayKindName())
                                    if (meta.country.isNotBlank()) append(" • ").append(meta.country)
                                }
                                Text(kindLine, color = DocsMuted, fontSize = 12.sp)
                                val date = DocumentRenewalCodec.expiryDate(entry)?.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ar"))).orEmpty()
                                if (date.isNotBlank()) Text("تاريخ الانتهاء: $date", color = DocsMuted, fontSize = 12.sp)
                                Text(statusText, color = statusColor, fontWeight = FontWeight.Black)
                            }
                            Icon(Icons.Default.ChevronLeft, null, tint = DocsInk)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { ensureReminderAccess(); showAdd = true },
                        modifier = Modifier.weight(1f).height(60.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DocsRed),
                    ) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("إضافة مستند", fontWeight = FontWeight.Black) }
                    OutlinedButton(
                        onClick = { ensureReminderAccess() },
                        enabled = docs.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(60.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) { Icon(Icons.Default.Alarm, null); Spacer(Modifier.width(6.dp)); Text("إضافة تنبيه تجديد", fontWeight = FontWeight.Black) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showAdd) {
        DocumentEditorDialog(null, { showAdd = false }) { title, details, meta, expiry, lead ->
            onAdd(title, details, AssistantCategory.PERSONAL, DocumentRenewalCodec.expiryAtNine(expiry), lead.toDouble(), DocumentRenewalCodec.encode(meta))
            showAdd = false
        }
    }
    editing?.let { entry ->
        DocumentEditorDialog(
            existing = entry,
            onDismiss = { editing = null },
            onDelete = { onDelete(entry.id); editing = null },
        ) { title, details, meta, expiry, lead ->
            onUpdate(entry.copy(title = title, details = details, dueAt = DocumentRenewalCodec.expiryAtNine(expiry), amount = lead.toDouble(), secondary = DocumentRenewalCodec.encode(meta), enabled = true, completed = false))
            editing = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentEditorDialog(
    existing: AssistantEntry?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (String, String, DocumentEntryMeta, LocalDate, Int) -> Unit,
) {
    val context = LocalContext.current
    val initialMeta = existing?.let { DocumentRenewalCodec.parse(it.secondary) } ?: DocumentEntryMeta()
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var details by remember(existing?.id) { mutableStateOf(existing?.details.orEmpty()) }
    var kind by remember(existing?.id) { mutableStateOf(initialMeta.kind) }
    var country by remember(existing?.id) { mutableStateOf(initialMeta.country) }
    var customType by remember(existing?.id) { mutableStateOf(initialMeta.customType) }
    var issuedDate by remember(existing?.id) {
        mutableStateOf(initialMeta.issuedDate.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
    }
    var expiry by remember(existing?.id) {
        mutableStateOf(existing?.let { DocumentRenewalCodec.expiryDate(it) } ?: LocalDate.now().plusYears(1))
    }
    var leadText by remember(existing?.id) {
        mutableStateOf((existing?.let { DocumentRenewalCodec.leadDays(it) } ?: 30).toString())
    }
    var kindMenuExpanded by remember { mutableStateOf(false) }

    val lead = leadText.toIntOrNull()
    val customTypeValid = kind != DocumentKind.OTHER || customType.isNotBlank()
    val dateOrderValid = issuedDate == null || !issuedDate!!.isAfter(expiry)
    val valid = title.isNotBlank() && lead != null && lead in 0..365 && customTypeValid && dateOrderValid

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
            tonalElevation = 8.dp,
            shadowElevation = 14.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "إغلاق", tint = DocsMuted) }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (existing == null) "إضافة مستند" else "تعديل المستند", fontWeight = FontWeight.Black, fontSize = 22.sp, color = DocsInk)
                        Text("احفظ بيانات المستند وحدد موعد التجديد", color = DocsMuted, fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = Color(0xFFEAECF0))

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                ) {
                    item {
                        Text("البيانات الأساسية", fontWeight = FontWeight.Black, color = DocsInk, fontSize = 15.sp)
                    }
                    item {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it.take(80) },
                            label = { Text("اسم المستند") },
                            placeholder = { Text("مثال: عقد إيجار الشقة") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                    item {
                        ExposedDropdownMenuBox(
                            expanded = kindMenuExpanded,
                            onExpandedChange = { kindMenuExpanded = !kindMenuExpanded },
                        ) {
                            OutlinedTextField(
                                value = kind.arabicName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("نوع المستند") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = kindMenuExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                shape = RoundedCornerShape(16.dp),
                            )
                            ExposedDropdownMenu(
                                expanded = kindMenuExpanded,
                                onDismissRequest = { kindMenuExpanded = false },
                            ) {
                                DocumentKind.entries.forEach { value ->
                                    DropdownMenuItem(
                                        text = { Text(value.arabicName, fontWeight = if (value == kind) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = {
                                            kind = value
                                            kindMenuExpanded = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = when (value) {
                                                    DocumentKind.NATIONAL_ID -> Icons.Default.Badge
                                                    DocumentKind.DRIVER_LICENSE, DocumentKind.CAR_LICENSE -> Icons.Default.DirectionsCar
                                                    DocumentKind.PASSPORT, DocumentKind.VISA -> Icons.Default.Public
                                                    DocumentKind.CAR_INSURANCE -> Icons.Default.Security
                                                    DocumentKind.RENTAL_CONTRACT -> Icons.Default.Home
                                                    DocumentKind.OTHER -> Icons.Default.Description
                                                },
                                                contentDescription = null,
                                                tint = if (value == kind) DocsRed else DocsMuted,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (kind == DocumentKind.OTHER) {
                        item {
                            OutlinedTextField(
                                value = customType,
                                onValueChange = { customType = it.take(60) },
                                label = { Text("اسم نوع المستند") },
                                placeholder = { Text("مثال: شهادة أو تصريح") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                isError = customType.isBlank(),
                            )
                        }
                    }
                    if (kind == DocumentKind.VISA) {
                        item {
                            OutlinedTextField(
                                value = country,
                                onValueChange = { country = it.take(60) },
                                label = { Text("الدولة") },
                                placeholder = { Text("مثال: السعودية") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                            )
                        }
                    }

                    item {
                        Text(
                            when (kind) {
                                DocumentKind.RENTAL_CONTRACT -> "مدة العقد"
                                DocumentKind.VISA -> "تواريخ التأشيرة"
                                else -> "التجديد والانتهاء"
                            },
                            fontWeight = FontWeight.Black,
                            color = DocsInk,
                            fontSize = 15.sp,
                        )
                    }

                    if (kind == DocumentKind.RENTAL_CONTRACT || kind == DocumentKind.VISA) {
                        item {
                            DocumentDateButton(
                                label = if (kind == DocumentKind.RENTAL_CONTRACT) "تاريخ بداية العقد" else "تاريخ الإصدار",
                                date = issuedDate,
                                emptyLabel = "اختر التاريخ",
                            ) {
                                val base = issuedDate ?: LocalDate.now()
                                DatePickerDialog(
                                    context,
                                    { _, y, m, d -> issuedDate = LocalDate.of(y, m + 1, d) },
                                    base.year,
                                    base.monthValue - 1,
                                    base.dayOfMonth,
                                ).show()
                            }
                        }
                    }
                    item {
                        DocumentDateButton(
                            label = if (kind == DocumentKind.RENTAL_CONTRACT) "تاريخ انتهاء العقد" else "تاريخ الانتهاء",
                            date = expiry,
                            emptyLabel = "اختر التاريخ",
                        ) {
                            DatePickerDialog(
                                context,
                                { _, y, m, d -> expiry = LocalDate.of(y, m + 1, d) },
                                expiry.year,
                                expiry.monthValue - 1,
                                expiry.dayOfMonth,
                            ).show()
                        }
                    }
                    if (!dateOrderValid) {
                        item { Text("تاريخ البداية أو الإصدار يجب أن يسبق تاريخ الانتهاء.", color = DocsRed, fontSize = 12.sp) }
                    }
                    item {
                        OutlinedTextField(
                            value = leadText,
                            onValueChange = { leadText = it.filter(Char::isDigit).take(3) },
                            label = { Text("التنبيه قبل الانتهاء") },
                            suffix = { Text("يوم") },
                            supportingText = { Text("من 0 إلى 365 يومًا") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            isError = lead == null || lead !in 0..365,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = details,
                            onValueChange = { details = it.take(240) },
                            label = { Text("ملاحظات") },
                            placeholder = { Text("أي بيانات إضافية عن المستند") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFFEAECF0))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onDelete != null) {
                        TextButton(onClick = onDelete) { Text("حذف", color = DocsRed, fontWeight = FontWeight.Bold) }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(14.dp)) { Text("إلغاء") }
                    Button(
                        enabled = valid,
                        onClick = {
                            onSave(
                                title.trim(),
                                details.trim(),
                                DocumentEntryMeta(
                                    kind = kind,
                                    issuedDate = issuedDate?.toString().orEmpty(),
                                    country = if (kind == DocumentKind.VISA) country.trim() else "",
                                    customType = if (kind == DocumentKind.OTHER) customType.trim() else "",
                                ),
                                expiry,
                                lead!!.coerceIn(0, 365),
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DocsRed),
                    ) {
                        Icon(Icons.Default.Check, null)
                        Spacer(Modifier.width(6.dp))
                        Text("حفظ", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentDateButton(
    label: String,
    date: LocalDate?,
    emptyLabel: String,
    onClick: () -> Unit,
) {
    val shown = date?.format(DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale("ar"))) ?: emptyLabel
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        Icon(Icons.Default.CalendarMonth, null, tint = DocsRed)
        Spacer(Modifier.width(10.dp))
        Text(shown, color = DocsInk, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(label, color = DocsMuted, fontSize = 12.sp)
    }
}
