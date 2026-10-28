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
import com.abfilepro.app.assistant.FamilySyncState
import com.abfilepro.app.ui.i18n.AppLanguage
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.withContext
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
        // P17: names are saved locally; creating, joining, pairing and leaving are explicit actions.
        FamilySettingsDialog(
            profile = familyProfile,
            manager = pairingManager,
            onDismiss = { showFamilySettings = false; familyRevision++ },
            onSaveNames = {
                FamilyLinkStore.save(context, it)
                familyRevision++
            },
            onChanged = { familyRevision++ },
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
            title = { Text(L("رسالة على الطلب"), fontWeight = FontWeight.Black) },
            text = {
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it.take(220) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(L("مثال: هات النوع الكبير")) },
                    minLines = 3,
                )
            },
            confirmButton = {
                Button(onClick = {
                    FamilyCommentStore.save(context, target.id, commentText.trim())
                    commentTarget = null
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2635))) { Text(L("حفظ")) }
            },
            dismissButton = { TextButton(onClick = { commentTarget = null }) { Text(L("إلغاء")) } },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(adaptSurface(Color(0xFFFFFCFC)))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text(L("طلبات العائلة"), modifier = Modifier.align(Alignment.Center), fontSize = 28.sp, fontWeight = FontWeight.Black)
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).size(46.dp).clickable(onClick = onBack),
                shape = CircleShape,
                color = adaptSurface(Color(0xFFF9FAFB)),
            ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("›", fontSize = 30.sp, fontWeight = FontWeight.Bold) } }
        }

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = adaptSurface(Color(0xFFFFF3F5))),
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
                    Text("${familyProfile.selfName} و ${familyProfile.partnerName}", color = adaptInk(Color(0xFF6B7280)), fontSize = 14.sp)
                    Text(L("طلبات العائلة مرتبطة الآن بقائمة التسوق"), color = adaptInk(Color(0xFF0DAA60)), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when (familyProfile.syncState) {
                            FamilySyncState.ON -> L("كود العائلة: ${familyProfile.inviteCode}")
                            FamilySyncState.LEGACY_PAUSED -> L("المزامنة متوقفة - افتح الإعدادات للتأكيد")
                            FamilySyncState.OFF -> L("المزامنة غير مفعلة - البيانات على هذا الهاتف فقط")
                        },
                        color = adaptInk(Color(0xFF9CA3AF)), fontSize = 11.sp,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { showChat = true }) {
                        Icon(Icons.Default.Chat, contentDescription = L("شات العائلة"), tint = adaptInk(Color(0xFFFF2635)))
                    }
                    IconButton(onClick = { showFamilySettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = L("إعداد العائلة"), tint = adaptInk(Color(0xFF475569)))
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FamilyFilter.entries.forEach { item ->
                Surface(
                    modifier = Modifier.weight(1f).height(46.dp).clickable { filter = item },
                    shape = RoundedCornerShape(18.dp),
                    color = if (filter == item) adaptInk(Color(0xFFFF2635)) else adaptSurface(Color(0xFFF8FAFC)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(L(item.label), color = if (filter == item) Color.White else adaptInk(Color(0xFF111827)), fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White))) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(L("لا توجد طلبات في هذا القسم"), color = adaptInk(Color(0xFF94A3B8)))
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
            Text(L("إضافة طلب"), fontWeight = FontWeight.Black, fontSize = 20.sp)
        }
        Text(
            L("عند الضغط على «سأشتريها» ينتقل الطلب تلقائيًا لقائمة التسوق. إكماله من أي من الشاشتين يحدّث حالته في الأخرى."),
            color = adaptInk(Color(0xFF64748B)),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            L("المزامنة السحابية مفعلة. على الهاتف الثاني أدخل نفس كود العائلة، وسيتم تبادل الطلبات تلقائيًا عند توفر الإنترنت."),
            color = adaptInk(Color(0xFF9CA3AF)),
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
        entry.completed || meta.status == "done" -> L("تم الشراء")
        meta.status == "in_progress" -> L("قيد التنفيذ")
        else -> L("طلب جديد")
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
                    Text(L("الكمية: ${meta.quantity} • من ${meta.fromName} إلى ${meta.toName}"), color = adaptInk(Color(0xFF6B7280)), fontSize = 13.sp, textAlign = TextAlign.End)
                    if (entry.details.isNotBlank()) Text(entry.details, color = adaptInk(Color(0xFF6B7280)), fontSize = 13.sp, textAlign = TextAlign.End)
                    Text(status, color = if (entry.completed || meta.status == "done") adaptInk(Color(0xFF0DAA60)) else adaptInk(Color(0xFF2563EB)), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                    colors = ButtonDefaults.buttonColors(containerColor = adaptSurface(Color.White), contentColor = adaptInk(Color(0xFFD7192D))),
                    shape = RoundedCornerShape(16.dp),
                ) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text(L("إرسال")) }
                Button(
                    onClick = onComment,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = adaptSurface(Color.White), contentColor = adaptInk(Color(0xFF334155))),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(L("رسالة")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (!entry.completed && meta.status != "done") {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2635)),
                        shape = RoundedCornerShape(16.dp),
                    ) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(4.dp)); Text(L("سأشتريها")) }
                    Button(
                        onClick = onDone,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = adaptSurface(Color(0xFFEFFFF8)), contentColor = adaptInk(Color(0xFF0DAA60))),
                        shape = RoundedCornerShape(16.dp),
                    ) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(4.dp)); Text(L("تم")) }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, com.abfilepro.app.ui.i18n.a11yLabel(Icons.Default.DeleteOutline), tint = adaptInk(Color(0xFFB91C1C))) }
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
        title = { Text(L("اختيار طلب للعائلة"), fontWeight = FontWeight.Black) },
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
                                color = if (category == item) item.badge else adaptSurface(Color(0xFFF8FAFC)),
                            ) {
                                Text(L(item.arabicLabel), modifier = Modifier.padding(9.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                    label = { Text(L("أو إضافة صنف جديد")) },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { quantity = (quantity - 1).coerceAtLeast(1) }) { Icon(Icons.Default.Remove, null) }
                    Text("$quantity", fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.width(44.dp), textAlign = TextAlign.Center)
                    IconButton(onClick = { quantity = (quantity + 1).coerceAtMost(99) }) { Icon(Icons.Default.Add, null, tint = adaptInk(Color(0xFFE11D2E))) }
                    Spacer(Modifier.width(8.dp))
                    Text(L("الكمية"), fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = !senderIsPartner,
                        onClick = { senderIsPartner = false },
                        label = { Text(L("من ${profile.selfName}")) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = senderIsPartner,
                        onClick = { senderIsPartner = true },
                        label = { Text(L("من ${profile.partnerName}")) },
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(180) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(L("تفاصيل أو ملاحظة اختيارية")) },
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
            ) { Text(L("إرسال الطلب")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("إلغاء")) } },
    )
}

@Composable
private fun FamilySettingsDialog(
    profile: FamilyProfile,
    manager: FamilyCloudSyncManager,
    onDismiss: () -> Unit,
    onSaveNames: (FamilyProfile) -> Unit,
    onChanged: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var familyName by remember { mutableStateOf(profile.familyName) }
    var selfName by remember { mutableStateOf(profile.selfName) }
    var partnerName by remember { mutableStateOf(profile.partnerName) }
    var state by remember { mutableStateOf(profile.syncState) }
    var linkedCode by remember { mutableStateOf(profile.inviteCode) }
    var shareHealth by remember { mutableStateOf(profile.shareHealth) }
    var joinInput by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    fun launchAction(action: () -> FamilyCloudSyncManager.LinkResult) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(action).getOrElse { FamilyCloudSyncManager.LinkResult.Failed(FamilyCloudSyncManager.Failure.NETWORK) } }
            busy = false
            when (result) {
                is FamilyCloudSyncManager.LinkResult.Linked -> {
                    state = FamilySyncState.ON; linkedCode = result.familyCode
                    invite = result.invite
                    message = if (result.invite != null) tr(language, "اكتب كود الدعوة ده على الهاتف التاني خلال 10 دقائق", "Enter this invite on the other phone within 10 minutes", "Saisissez cette invitation sur l'autre téléphone sous 10 minutes")
                        else tr(language, "تم الربط بالعائلة", "Linked to the family", "Famille liée")
                    onChanged()
                }
                is FamilyCloudSyncManager.LinkResult.Failed -> message = failureText(language, result.reason)
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmLeave = false },
            title = { Text(tr(language, "مغادرة العائلة وحذف بياناتي", "Leave family and delete my data", "Quitter la famille et supprimer mes données"), fontWeight = FontWeight.Black) },
            text = { Text(tr(language,
                "هيتشال ربط الهاتف ده، وكل اللي شاركته من الهاتف ده ورسائلك هيتمسحوا من الخدمة ومن أجهزة العائلة. بياناتك على الهاتف ده هتفضل زي ما هي.",
                "This phone will be unlinked. Everything it shared and your chat messages are removed from the service and from the family's phones. Your data on this phone stays.",
                "Ce téléphone sera dissocié. Tout ce qu'il a partagé et vos messages sont supprimés du service et des téléphones de la famille. Vos données restent sur ce téléphone.")) },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { manager.leaveFamily(deleteMyData = true) }.getOrDefault(false) }
                        busy = false; confirmLeave = false
                        if (ok) { state = FamilySyncState.OFF; linkedCode = ""; shareHealth = false; invite = null
                            message = tr(language, "تمت مغادرة العائلة وحذف بياناتك منها", "You left the family and your shared data was deleted", "Vous avez quitté la famille ; vos données partagées ont été supprimées")
                            onChanged()
                        } else message = tr(language, "تعذر الاتصال بالخدمة. ما اتغيرش حاجة، حاول تاني.", "Could not reach the service. Nothing changed; try again.", "Service injoignable. Rien n'a changé ; réessayez.")
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))) { Text(tr(language, "مغادرة وحذف", "Leave and delete", "Quitter et supprimer")) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmLeave = false }) { Text(tr(language, "إلغاء", "Cancel", "Annuler")) } },
        )
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr(language, "إعداد العائلة", "Family settings", "Paramètres famille"), fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(familyName, { familyName = it.take(40) }, label = { Text(tr(language, "اسم العائلة", "Family name", "Nom de famille")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(selfName, { selfName = it.take(30) }, label = { Text(tr(language, "اسمي", "My name", "Mon nom")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(partnerName, { partnerName = it.take(30) }, label = { Text(tr(language, "اسم الطرف الآخر", "Other member", "Autre membre")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                HorizontalDivider()
                when (state) {
                    FamilySyncState.OFF -> {
                        Text(tr(language,
                            "المزامنة مش مفعلة. طلبات العائلة وقائمة التسوق محفوظة على الهاتف ده بس. فعّلها لو عايز تشارك مع حد من عيلتك.",
                            "Sync is off. Family requests and the shopping list stay on this phone only. Turn it on to share with a family member.",
                            "La synchronisation est désactivée. Les demandes et la liste de courses restent sur ce téléphone."), fontSize = 13.sp)
                        Button(enabled = !busy, onClick = { launchAction { manager.createFamily() } }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2635))) {
                            Text(tr(language, "إنشاء عائلة جديدة", "Create a new family", "Créer une famille"))
                        }
                        OutlinedTextField(
                            value = joinInput,
                            onValueChange = { joinInput = it.take(24) },
                            label = { Text(tr(language, "كود الدعوة من الهاتف التاني", "Invite from the other phone", "Invitation de l'autre téléphone")) },
                            placeholder = { Text("123456-ABCD234567") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(enabled = !busy && joinInput.isNotBlank(), onClick = { launchAction { manager.joinFamily(joinInput) } }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr(language, "انضمام للعائلة", "Join family", "Rejoindre"))
                        }
                    }
                    FamilySyncState.LEGACY_PAUSED -> {
                        Text(tr(language,
                            "النسخ القديمة كانت بتفعّل المزامنة تلقائيًا. وقفناها لحد ما تختار. لو بتستخدمها مع حد من عيلتك اضغط «تكمّل المزامنة».",
                            "Older versions turned sync on automatically. It is paused until you choose. If you share with a family member, tap Keep syncing.",
                            "Les anciennes versions activaient la synchro automatiquement. Elle est en pause jusqu'à votre choix."), fontSize = 13.sp)
                        ShareHealthRow(language, shareHealth) { shareHealth = it }
                        Button(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                withContext(Dispatchers.IO) { manager.confirmLegacyFamily(shareHealth) }
                                busy = false; state = FamilySyncState.ON; onChanged()
                                message = tr(language, "المزامنة شغالة", "Sync is on", "Synchro activée")
                            }
                        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2635))) {
                            Text(tr(language, "تكمّل المزامنة", "Keep syncing", "Continuer la synchro"))
                        }
                        TextButton(enabled = !busy, onClick = { confirmLeave = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr(language, "مش بستخدمها - امسح بياناتي من الخدمة", "I don't use it - delete my data from the service", "Je ne l'utilise pas - supprimer mes données"), color = adaptInk(Color(0xFFDC2626)))
                        }
                    }
                    FamilySyncState.ON -> {
                        Text(tr(language, "مرتبط بعائلة كود $linkedCode", "Linked to family $linkedCode", "Famille liée $linkedCode"), fontWeight = FontWeight.Bold)
                        ShareHealthRow(language, shareHealth) { enabled ->
                            shareHealth = enabled
                            scope.launch { withContext(Dispatchers.IO) { manager.setShareHealth(enabled) } }
                        }
                        Button(enabled = !busy, onClick = { launchAction { manager.openPairingWindow() } }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr(language, "إضافة هاتف تاني للعائلة", "Add another phone", "Ajouter un téléphone"))
                        }
                        TextButton(enabled = !busy, onClick = { confirmLeave = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr(language, "مغادرة العائلة وحذف بياناتي", "Leave family and delete my data", "Quitter la famille et supprimer mes données"), color = adaptInk(Color(0xFFDC2626)))
                        }
                    }
                }
                invite?.let {
                    Surface(shape = RoundedCornerShape(14.dp), color = adaptSurface(Color(0xFFFFF3F5)), modifier = Modifier.fillMaxWidth()) {
                        Text(it, modifier = Modifier.padding(12.dp), fontSize = 22.sp, fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                message?.let { Text(it, fontSize = 13.sp, color = adaptInk(Color(0xFF475569))) }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                onSaveNames(profile.copy(familyName = familyName, selfName = selfName, partnerName = partnerName))
                onDismiss()
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2635))) { Text(tr(language, "حفظ", "Save", "Enregistrer")) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(tr(language, "إغلاق", "Close", "Fermer")) } },
    )
}

@Composable
private fun ShareHealthRow(language: AppLanguage, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(tr(language, "مشاركة الأدوية والمواعيد الصحية", "Share medicines and health appointments", "Partager médicaments et rendez-vous"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(tr(language, "مقفولة افتراضيًا. لو قفلتها، اللي شاركته قبل كده بيتشال من أجهزة العائلة.", "Off by default. Turning it off withdraws what you shared before.", "Désactivé par défaut. Le désactiver retire ce qui a été partagé."), fontSize = 11.sp, color = adaptInk(Color(0xFF6B7280)))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun failureText(language: AppLanguage, reason: FamilyCloudSyncManager.Failure): String = when (reason) {
    FamilyCloudSyncManager.Failure.INVALID_INVITE -> tr(language, "كود الدعوة غلط أو انتهى. اطلب كود جديد من الهاتف التاني.", "The invite is wrong or expired. Ask the other phone for a new one.", "Invitation invalide ou expirée.")
    FamilyCloudSyncManager.Failure.PAIRING_CLOSED -> tr(language, "وقت الربط خلص. من الهاتف المرتبط اضغط «إضافة هاتف تاني».", "Pairing has closed. On the linked phone tap Add another phone.", "L'appairage est fermé.")
    FamilyCloudSyncManager.Failure.NOT_FOUND -> tr(language, "مفيش عائلة بالكود ده.", "No family has this code.", "Aucune famille avec ce code.")
    FamilyCloudSyncManager.Failure.FAMILY_FULL -> tr(language, "العائلة وصلت للحد الأقصى من الأجهزة.", "This family has reached its device limit.", "Limite d'appareils atteinte.")
    FamilyCloudSyncManager.Failure.TOO_MANY_ATTEMPTS -> tr(language, "محاولات كتير. استنى دقيقة وجرب تاني.", "Too many attempts. Wait a minute and try again.", "Trop d'essais. Réessayez dans une minute.")
    FamilyCloudSyncManager.Failure.UNAUTHORIZED -> tr(language, "الهاتف ده مبقاش مرتبط. انضم تاني بكود دعوة جديد.", "This phone is no longer linked. Join again with a new invite.", "Ce téléphone n'est plus lié.")
    FamilyCloudSyncManager.Failure.NETWORK -> tr(language, "مفيش اتصال بالخدمة. اتأكد من الإنترنت.", "No connection to the service. Check the internet.", "Pas de connexion au service.")
    FamilyCloudSyncManager.Failure.NOT_CONFIGURED, FamilyCloudSyncManager.Failure.SERVER -> tr(language, "الخدمة مش متاحة دلوقتي. جرب بعدين.", "The service is unavailable. Try later.", "Service indisponible.")
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
    }, L("إرسال الطلب")))
}
