package com.abfilepro.app.ui.screens.assistant

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abfilepro.app.assistant.AssistantCategory
import com.abfilepro.app.assistant.AssistantEntry
import com.abfilepro.app.assistant.FamilyLinkStore
import com.abfilepro.app.assistant.FamilyCommentStore
import com.abfilepro.app.assistant.FamilyCloudSyncManager
import com.abfilepro.app.assistant.FamilyProfile
import com.abfilepro.app.assistant.AssistantRepository
import com.abfilepro.app.assistant.FamilyRequestMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class FamilyFilter(val label: String) { ALL("الكل"), NEW("طلبات جديدة"), PROGRESS("قيد التنفيذ"), DONE("تم الشراء") }


@Composable
internal fun FamilyRequestsOperationalScreen(
    entries: List<AssistantEntry>,
    defaultCategory: AssistantCategory,
    onBack: () -> Unit,
    onAdd: (String, String, AssistantCategory, Long?, Double?, String) -> Unit,
    onUpdate: (AssistantEntry) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    val pairingScope = rememberCoroutineScope()
    val pairingManager = remember(context) { FamilyCloudSyncManager(context, AssistantRepository(context)) }
    var filter by remember { mutableStateOf(FamilyFilter.ALL) }
    var showAdd by remember { mutableStateOf(false) }
    var showFamilySettings by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var familyRevision by remember { mutableStateOf(0) }
    val familyProfile = remember(familyRevision) { FamilyLinkStore.load(context) }
    var commentTarget by remember { mutableStateOf<AssistantEntry?>(null) }
    var commentText by remember(commentTarget?.id) { mutableStateOf(commentTarget?.let { FamilyCommentStore.load(context, it.id) }.orEmpty()) }

    if (showChat) {
        FamilyChatScreen(onBack = { showChat = false })
        return
    }

    val familyEntries = entries.filter { it.module == com.abfilepro.app.assistant.AssistantModule.FAMILY_REQUESTS }
    val filtered = familyEntries.filter { entry ->
        val status = FamilyLinkStore.parse(entry.secondary).status
        when (filter) {
            FamilyFilter.ALL -> true
            FamilyFilter.NEW -> !entry.completed && status == "new"
            FamilyFilter.PROGRESS -> !entry.completed && status == "in_progress"
            FamilyFilter.DONE -> entry.completed || status == "done"
        }
    }

    if (showFamilySettings) {
        FamilySettingsDialog(
            profile = familyProfile,
            onDismiss = { showFamilySettings = false },
            onSave = {
                FamilyLinkStore.save(context, it)
                pairingScope.launch(Dispatchers.IO) { pairingManager.openPairingWindow() }
                familyRevision++
                showFamilySettings = false
            },
        )
    }

    if (showAdd) {
        AddFamilyRequestDialog(
            profile = familyProfile,
            onDismiss = { showAdd = false },
            onSave = { title, details, category, quantity, fromName, toName ->
                val meta = FamilyRequestMeta(
                    status = "new",
                    category = category.name,
                    quantity = quantity,
                    fromName = fromName,
                    toName = toName,
                )
                onAdd(title, details, defaultCategory, null, null, FamilyLinkStore.encode(meta))
                showAdd = false
            },
        )
    }

    commentTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { commentTarget = null },
            title = { Text("رسالة على الطلب", fontWeight = FontWeight.Black) },
            text = {
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it.take(220) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("مثال: هات النوع الكبير") },
                    minLines = 3,
                )
            },
            confirmButton = {
                Button(onClick = {
                    FamilyCommentStore.save(context, target.id, commentText.trim())
                    commentTarget = null
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2635))) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { commentTarget = null }) { Text("إلغاء") } },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFFFCFC))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text("طلبات العائلة", modifier = Modifier.align(Alignment.Center), fontSize = 28.sp, fontWeight = FontWeight.Black)
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).size(46.dp).clickable(onClick = onBack),
                shape = CircleShape,
                color = Color(0xFFF9FAFB),
            ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("›", fontSize = 30.sp, fontWeight = FontWeight.Bold) } }
        }

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3F5)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("👩‍❤️‍👨", fontSize = 56.sp)
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(familyProfile.familyName, fontWeight = FontWeight.Black, fontSize = 24.sp)
                    Text("${familyProfile.selfName} و ${familyProfile.partnerName}", color = Color(0xFF6B7280), fontSize = 14.sp)
                    Text("طلبات العائلة مرتبطة الآن بقائمة التسوق", color = Color(0xFF0DAA60), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("كود العائلة: ${familyProfile.inviteCode}", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { showChat = true }) {
                        Icon(Icons.Default.Chat, contentDescription = "شات العائلة", tint = Color(0xFFFF2635))
                    }
                    IconButton(onClick = { showFamilySettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "إعداد العائلة", tint = Color(0xFF475569))
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FamilyFilter.entries.forEach { item ->
                Surface(
                    modifier = Modifier.weight(1f).height(46.dp).clickable { filter = item },
                    shape = RoundedCornerShape(18.dp),
                    color = if (filter == item) Color(0xFFFF2635) else Color(0xFFF8FAFC),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(item.label, color = if (filter == item) Color.White else Color(0xFF111827), fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("لا توجد طلبات في هذا القسم", color = Color(0xFF94A3B8))
                }
            }
        } else {
            filtered.sortedByDescending { it.createdAt }.forEach { entry ->
                val meta = FamilyLinkStore.parse(entry.secondary)
                FamilyRequestCard(
                    entry = entry,
                    meta = meta,
                    comment = FamilyCommentStore.load(context, entry.id),
                    onStart = {
                        addFamilyRequestToShopping(
                            context = context,
                            requestId = entry.id,
                            name = entry.title,
                            quantity = meta.quantity,
                            categoryName = meta.category,
                        )
                        onUpdate(entry.copy(completed = false, secondary = FamilyLinkStore.withStatus(entry.secondary, "in_progress")))
                    },
                    onDone = {
                        setFamilyRequestShoppingCompletion(context, entry.id, true)
                        onUpdate(entry.copy(completed = true, secondary = FamilyLinkStore.withStatus(entry.secondary, "done")))
                    },
                    onComment = {
                        commentTarget = entry
                        commentText = FamilyCommentStore.load(context, entry.id)
                    },
                    onShare = { shareFamilyRequest(context, entry, meta, FamilyCommentStore.load(context, entry.id)) },
                    onDelete = {
                        FamilyCommentStore.delete(context, entry.id)
                        removeFamilyRequestFromShopping(context, entry.id)
                        onDelete(entry.id)
                    },
                )
            }
        }

        Button(
            onClick = { showAdd = true },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2635)),
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("إضافة طلب", fontWeight = FontWeight.Black, fontSize = 20.sp)
        }
        Text(
            "عند الضغط على «سأشتريها» ينتقل الطلب تلقائيًا لقائمة التسوق. إكماله من أي من الشاشتين يحدّث حالته في الأخرى.",
            color = Color(0xFF64748B),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "المزامنة السحابية مفعلة. على الهاتف الثاني أدخل نفس كود العائلة، وسيتم تبادل الطلبات تلقائيًا عند توفر الإنترنت.",
            color = Color(0xFF9CA3AF),
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FamilyRequestCard(
    entry: AssistantEntry,
    meta: FamilyRequestMeta,
    comment: String,
    onStart: () -> Unit,
    onDone: () -> Unit,
    onComment: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = when {
        entry.completed || meta.status == "done" -> "تم الشراء"
        meta.status == "in_progress" -> "قيد التنفيذ"
        else -> "طلب جديد"
    }
    val cardColor = when {
        entry.completed || meta.status == "done" -> Color(0xFFEFFFF8)
        meta.status == "in_progress" -> Color(0xFFFFF7E7)
        else -> Color(0xFFF3FFF8)
    }
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(itemEmoji(entry.title, ShoppingCategory.entries.firstOrNull { it.name == meta.category } ?: inferCategory(entry.title)), fontSize = 46.sp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(entry.title, fontWeight = FontWeight.Black, fontSize = 22.sp, textAlign = TextAlign.End)
                    Text("الكمية: ${meta.quantity} • من ${meta.fromName} إلى ${meta.toName}", color = Color(0xFF6B7280), fontSize = 13.sp, textAlign = TextAlign.End)
                    if (entry.details.isNotBlank()) Text(entry.details, color = Color(0xFF6B7280), fontSize = 13.sp, textAlign = TextAlign.End)
                    Text(status, color = if (entry.completed || meta.status == "done") Color(0xFF0DAA60) else Color(0xFF2563EB), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            if (comment.isNotBlank()) {
                Surface(shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = .75f)) {
                    Text("💬 $comment", modifier = Modifier.fillMaxWidth().padding(10.dp), textAlign = TextAlign.End, fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFD7192D)),
                    shape = RoundedCornerShape(16.dp),
                ) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text("إرسال") }
                Button(
                    onClick = onComment,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF334155)),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("رسالة") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (!entry.completed && meta.status != "done") {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2635)),
                        shape = RoundedCornerShape(16.dp),
                    ) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(4.dp)); Text("سأشتريها") }
                    Button(
                        onClick = onDone,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEFFFF8), contentColor = Color(0xFF0DAA60)),
                        shape = RoundedCornerShape(16.dp),
                    ) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(4.dp)); Text("تم") }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, null, tint = Color(0xFFB91C1C)) }
            }
        }
    }
}

@Composable
private fun AddFamilyRequestDialog(
    profile: FamilyProfile,
    onDismiss: () -> Unit,
    onSave: (String, String, ShoppingCategory, Int, String, String) -> Unit,
) {
    var category by remember { mutableStateOf(ShoppingCategory.POULTRY) }
    var selectedName by remember { mutableStateOf(catalogFor(category).firstOrNull().orEmpty()) }
    var customName by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf(1) }
    var senderIsPartner by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اختيار طلب للعائلة", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.weight(0.44f).height(230.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        ShoppingCategory.entries.forEach { item ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    category = item
                                    selectedName = catalogFor(item).firstOrNull().orEmpty()
                                    customName = ""
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (category == item) item.badge else Color(0xFFF8FAFC),
                            ) {
                                Text(item.arabicLabel, modifier = Modifier.padding(9.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.weight(0.56f).height(230.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        catalogFor(category).forEach { name ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { selectedName = name; customName = "" },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selectedName == name && customName.isBlank()) category.background else Color.White,
                            ) {
                                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(itemEmoji(name, category), fontSize = 20.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(name, modifier = Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it.take(70) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("أو إضافة صنف جديد") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { quantity = (quantity - 1).coerceAtLeast(1) }) { Icon(Icons.Default.Remove, null) }
                    Text("$quantity", fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.width(44.dp), textAlign = TextAlign.Center)
                    IconButton(onClick = { quantity = (quantity + 1).coerceAtMost(99) }) { Icon(Icons.Default.Add, null, tint = Color(0xFFE11D2E)) }
                    Spacer(Modifier.width(8.dp))
                    Text("الكمية", fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = !senderIsPartner,
                        onClick = { senderIsPartner = false },
                        label = { Text("من ${profile.selfName}") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = senderIsPartner,
                        onClick = { senderIsPartner = true },
                        label = { Text("من ${profile.partnerName}") },
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(180) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("تفاصيل أو ملاحظة اختيارية") },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val title = customName.trim().ifBlank { selectedName.trim() }
                    if (title.isNotBlank()) {
                        val from = if (senderIsPartner) profile.partnerName else profile.selfName
                        val to = if (senderIsPartner) profile.selfName else profile.partnerName
                        onSave(title, details.trim(), category, quantity, from, to)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2635)),
            ) { Text("إرسال الطلب") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun FamilySettingsDialog(profile: FamilyProfile, onDismiss: () -> Unit, onSave: (FamilyProfile) -> Unit) {
    var familyName by remember { mutableStateOf(profile.familyName) }
    var selfName by remember { mutableStateOf(profile.selfName) }
    var partnerName by remember { mutableStateOf(profile.partnerName) }
    var inviteCode by remember { mutableStateOf(profile.inviteCode) }
    val validCode = inviteCode.length == 6 && inviteCode.all(Char::isDigit)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إعداد العائلة", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(familyName, { familyName = it.take(40) }, label = { Text("اسم العائلة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(selfName, { selfName = it.take(30) }, label = { Text("اسمي") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(partnerName, { partnerName = it.take(30) }, label = { Text("اسم الطرف الآخر") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = inviteCode,
                    onValueChange = { inviteCode = it.filter(Char::isDigit).take(6) },
                    label = { Text("كود العائلة - 6 أرقام") },
                    supportingText = { Text("على الهاتف الأول اضغط حفظ وربط لفتح الربط، ثم أدخل نفس الكود على الهاتف الآخر") },
                    isError = inviteCode.isNotEmpty() && !validCode,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = validCode,
                onClick = {
                    onSave(
                        profile.copy(
                            familyName = familyName,
                            selfName = selfName,
                            partnerName = partnerName,
                            inviteCode = inviteCode,
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2635)),
            ) { Text("حفظ وربط") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

private fun shareFamilyRequest(context: Context, entry: AssistantEntry, meta: FamilyRequestMeta, comment: String) {
    val text = buildString {
        appendLine("طلب عائلي من ${meta.fromName} إلى ${meta.toName}: ${entry.title}")
        appendLine("الكمية: ${meta.quantity}")
        if (entry.details.isNotBlank()) appendLine(entry.details)
        if (comment.isNotBlank()) appendLine("رسالة: $comment")
    }.trim()
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }, "إرسال الطلب"))
}
