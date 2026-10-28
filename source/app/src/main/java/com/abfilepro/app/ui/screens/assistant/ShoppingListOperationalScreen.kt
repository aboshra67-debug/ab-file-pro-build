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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abfilepro.app.assistant.ShoppingCloudItem
import com.abfilepro.app.assistant.ShoppingListSyncStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal enum class ShoppingCategory(
    val arabicLabel: String,
    val emoji: String,
    val background: Color,
    val badge: Color,
) {
    PRODUCE("خضروات وفواكه", "🍅🍌🥬", Color(0xFFEFFFF8), Color(0xFFD9F8EC)),
    DAIRY("ألبان", "🥛🧀🥣", Color(0xFFFFF8E7), Color(0xFFFFEDBF)),
    POULTRY("دواجن", "🍗🐔", Color(0xFFFFF3EA), Color(0xFFFFDFC5)),
    MEAT("لحوم", "🥩", Color(0xFFFFF0F1), Color(0xFFFFD9DE)),
    GROCERY("بقالة", "🛒🥫", Color(0xFFF7F5FF), Color(0xFFE7E0FF)),
    BAKERY("مخبوزات", "🥖🍞", Color(0xFFFFF8EC), Color(0xFFFFE8B8)),
    DRINKS("مشروبات", "🧃🥤", Color(0xFFF0FBFF), Color(0xFFD9F4FF)),
    FROZEN("مجمدات", "🧊", Color(0xFFF0F7FF), Color(0xFFDCEBFF)),
    PERSONAL("عناية شخصية", "🧴🪥", Color(0xFFFFF2FA), Color(0xFFFFDDF1)),
    PHARMACY("صيدلية", "💊", Color(0xFFF1FFF6), Color(0xFFDDF7E7)),
    HOUSEHOLD("مستلزمات منزلية", "🏠🧻", Color(0xFFF8FAFC), Color(0xFFE8EDF3)),
    CLEANING("منظفات", "🧴🧽", Color(0xFFF0F9FF), Color(0xFFDCEFFF)),
    OTHER("أخرى", "📦", Color(0xFFF8FAFC), Color(0xFFE5E7EB));

    companion object {
        fun fromName(value: String): ShoppingCategory = entries.firstOrNull { it.name == value } ?: PRODUCE
    }
}

private data class ShoppingItem(
    val id: Long,
    val name: String,
    val quantity: Int,
    val completed: Boolean,
    val category: ShoppingCategory,
    val familyRequestId: String? = null,
)

private object ShoppingListStore {
    fun listName(context: Context): String = ShoppingListSyncStore(context).listName()

    fun saveListName(context: Context, name: String) {
        ShoppingListSyncStore(context).saveListName(name)
    }

    fun load(context: Context): List<ShoppingItem> = ShoppingListSyncStore(context).load().map { item ->
        ShoppingItem(
            id = item.id,
            name = item.name,
            quantity = item.quantity,
            completed = item.completed,
            category = ShoppingCategory.fromName(item.category),
            familyRequestId = item.familyRequestId,
        )
    }

    fun save(context: Context, items: List<ShoppingItem>) {
        ShoppingListSyncStore(context).save(items.map { item ->
            ShoppingCloudItem(
                id = item.id,
                name = item.name,
                quantity = item.quantity,
                completed = item.completed,
                category = item.category.name,
                familyRequestId = item.familyRequestId,
            )
        })
    }
}

@Composable
internal fun ShoppingListOperationalScreen(
    entries: List<com.abfilepro.app.assistant.AssistantEntry>,
    onBack: () -> Unit,
    onUpdateFamilyRequest: (com.abfilepro.app.assistant.AssistantEntry) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var items by remember { mutableStateOf(ShoppingListStore.load(context)) }
    var listName by remember { mutableStateOf(ShoppingListStore.listName(context)) }
    var newItem by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(ShoppingCategory.entries.associateWith { true }) }
    var selectedCatalogCategory by remember { mutableStateOf(ShoppingCategory.POULTRY) }
    var showCustomItemDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(5_000)
            val remoteItems = ShoppingListStore.load(context)
            if (remoteItems != items) items = remoteItems
            val remoteName = ShoppingListStore.listName(context)
            if (remoteName != listName) listName = remoteName
        }
    }

    val completed = items.count { it.completed }
    val total = items.size
    val progress = if (total == 0) 0f else completed.toFloat() / total.toFloat()

    fun save(next: List<ShoppingItem>) {
        val previousById = items.associateBy { it.id }
        next.forEach { changed ->
            val requestId = changed.familyRequestId ?: return@forEach
            val previous = previousById[changed.id]
            if (previous != null && previous.completed != changed.completed) {
                entries.firstOrNull { it.id == requestId }?.let { request ->
                    val nextStatus = if (changed.completed) "done" else "in_progress"
                    onUpdateFamilyRequest(
                        request.copy(
                            completed = changed.completed,
                            secondary = com.abfilepro.app.assistant.FamilyLinkStore.withStatus(request.secondary, nextStatus),
                        )
                    )
                }
            }
        }
        items = next
        ShoppingListStore.save(context, next)
    }

    if (showCustomItemDialog) {
        AlertDialog(
            onDismissRequest = { showCustomItemDialog = false },
            title = { Text(L("إضافة صنف إلى ${L(selectedCatalogCategory.arabicLabel)}"), fontWeight = FontWeight.Black) },
            text = {
                OutlinedTextField(
                    value = newItem,
                    onValueChange = { newItem = it.take(60) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(L("اسم الصنف")) },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newItem.trim()
                        if (name.isNotBlank()) {
                            save(items + ShoppingItem(System.currentTimeMillis(), name, 1, false, selectedCatalogCategory))
                            newItem = ""
                            showCustomItemDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2635)),
                ) { Text(L("إضافة")) }
            },
            dismissButton = { TextButton(onClick = { showCustomItemDialog = false }) { Text(L("إلغاء")) } },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(adaptSurface(Color(0xFFFDFDFD)))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ShoppingHeader(onBack)

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(shape = CircleShape, color = adaptSurface(Color(0xFFF4F7FA))) {
                    Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = adaptInk(Color(0xFF111827)))
                    }
                }
                OutlinedTextField(
                    value = listName,
                    onValueChange = {
                        listName = it.take(40)
                        ShoppingListStore.saveListName(context, listName.ifBlank { "قائمة المنزل" })
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Black, fontSize = 21.sp, textAlign = endText),
                )
                Text("🏠", fontSize = 34.sp)
            }
        }

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = adaptSurface(Color(0xFFEFFFF8))),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("🛒🥬🍅🥛", fontSize = 44.sp)
                Column(modifier = Modifier.weight(1f), horizontalAlignment = endAlign, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(L("$completed من $total تم شراؤها"), fontWeight = FontWeight.Black, fontSize = 23.sp, textAlign = endText)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = adaptInk(Color(0xFF10B981)),
                        trackColor = Color(0xFFE2E8F0),
                    )
                    Text(L("${(progress * 100).toInt()}٪ مكتمل"), color = adaptInk(Color(0xFF475569)), fontSize = 14.sp)
                }
            }
        }

        val linkedFamilyRequests = entries.count {
            it.module == com.abfilepro.app.assistant.AssistantModule.FAMILY_REQUESTS && !it.completed
        }
        if (linkedFamilyRequests > 0) {
            Surface(shape = RoundedCornerShape(18.dp), color = adaptSurface(Color(0xFFFFF3F5))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("👩‍❤️‍👨", fontSize = 28.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = endAlign) {
                        Text(L("مرتبطة بطلبات العائلة"), fontWeight = FontWeight.Black)
                        Text(L("$linkedFamilyRequests طلبات عائلية معلقة"), color = adaptInk(Color(0xFF6B7280)), fontSize = 12.sp)
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L("إضافة سريعة من الفئات"), fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.fillMaxWidth(), textAlign = endText)
                Row(
                    modifier = Modifier.fillMaxWidth().height(286.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(0.42f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ShoppingCategory.entries.forEach { category ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { selectedCatalogCategory = category },
                                shape = RoundedCornerShape(14.dp),
                                color = if (selectedCatalogCategory == category) category.badge else Color(0xFFF8FAFC),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = endArrange,
                                ) {
                                    Text(L(category.arabicLabel), modifier = Modifier.weight(1f), textAlign = endText, fontWeight = if (selectedCatalogCategory == category) FontWeight.Black else FontWeight.Bold, fontSize = 13.sp)
                                    Spacer(Modifier.width(5.dp))
                                    Text(category.emoji.take(2), fontSize = 18.sp)
                                }
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.weight(0.58f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        catalogFor(selectedCatalogCategory).forEach { name ->
                            Surface(shape = RoundedCornerShape(14.dp), color = selectedCatalogCategory.background) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(
                                        onClick = { save(items + ShoppingItem(System.currentTimeMillis(), name, 1, false, selectedCatalogCategory)) },
                                        modifier = Modifier.size(36.dp),
                                    ) { Icon(Icons.Default.Add, null, tint = adaptInk(Color(0xFFE11D2E))) }
                                    Spacer(Modifier.weight(1f))
                                    Text(name, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = endText)
                                    Spacer(Modifier.width(5.dp))
                                    Text(itemEmoji(name, selectedCatalogCategory), fontSize = 22.sp)
                                }
                            }
                        }
                        Button(
                            onClick = { newItem = ""; showCustomItemDialog = true },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = adaptSurface(Color(0xFFFFEEF0)), contentColor = adaptInk(Color(0xFFD7192D))),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(5.dp))
                            Text(L("إضافة صنف"), fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }

        ShoppingCategory.entries.forEach { category ->
            val categoryItems = items.filter { it.category == category }
            ShoppingCategoryCard(
                category = category,
                items = categoryItems,
                expanded = expanded[category] == true,
                onToggleExpanded = { expanded = expanded.toMutableMap().apply { this[category] = !(expanded[category] == true) } },
                onChange = { changed ->
                    save(items.map { current -> changed.firstOrNull { it.id == current.id } ?: current })
                },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val share = buildShoppingShareText(listName.ifBlank { "قائمة المنزل" }, items)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, share)
                    }, L("مشاركة القائمة")))
                },
                modifier = Modifier.weight(1f).height(58.dp),
                colors = ButtonDefaults.buttonColors(containerColor = adaptSurface(Color(0xFFFFF2F3)), contentColor = adaptInk(Color(0xFFD7192D))),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Default.Share, null)
                Spacer(Modifier.width(7.dp))
                Text(L("مشاركة القائمة"), fontWeight = FontWeight.Black, fontSize = 16.sp)
            }

            Button(
                onClick = { save(items.filterNot { it.completed }) },
                modifier = Modifier.weight(1f).height(58.dp),
                colors = ButtonDefaults.buttonColors(containerColor = adaptSurface(Color.White), contentColor = adaptInk(Color(0xFF111827))),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Default.DeleteOutline, null)
                Spacer(Modifier.width(7.dp))
                Text(L("مسح المكتمل"), fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ShoppingHeader(onBack: () -> Unit) {
    HeaderMirror { Box(modifier = Modifier.fillMaxWidth()) {
        Text(L("قائمة التسوق"), modifier = Modifier.align(Alignment.Center), fontSize = 28.sp, fontWeight = FontWeight.Black)
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).size(46.dp).clickable(onClick = onBack),
            shape = CircleShape,
            color = adaptSurface(Color(0xFFF9FAFB)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (isLtrUi) "‹" else "›", fontSize = 30.sp, fontWeight = FontWeight.Bold) }
        }
    } }
}

@Composable
private fun ShoppingCategoryCard(
    category: ShoppingCategory,
    items: List<ShoppingItem>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onChange: (List<ShoppingItem>) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = category.background),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp).clickable(onClick = onToggleExpanded),
                    shape = CircleShape,
                    color = category.badge,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null)
                    }
                }
                Surface(shape = CircleShape, color = category.badge) {
                    Text("${items.size}", modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp), fontWeight = FontWeight.Black)
                }
                Text(L("أصناف"), fontWeight = FontWeight.Bold, color = adaptInk(Color(0xFF475569)))
                Spacer(Modifier.weight(1f))
                Text(L(category.arabicLabel), fontWeight = FontWeight.Black, fontSize = 21.sp)
                Text(category.emoji, fontSize = 28.sp)
            }

            if (expanded) {
                items.forEach { item ->
                    ShoppingItemRow(
                        item = item,
                        onMinus = {
                            onChange(items.map { if (it.id == item.id) it.copy(quantity = (it.quantity - 1).coerceAtLeast(1)) else it })
                        },
                        onPlus = {
                            onChange(items.map { if (it.id == item.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(99)) else it })
                        },
                        onToggle = {
                            onChange(items.map { if (it.id == item.id) it.copy(completed = !it.completed) else it })
                        },
                    )
                }
                if (items.isEmpty()) {
                    Text(L("لا توجد أصناف في هذا القسم"), modifier = Modifier.fillMaxWidth().padding(12.dp), textAlign = TextAlign.Center, color = adaptInk(Color(0xFF94A3B8)))
                }
            }
        }
    }
}

@Composable
private fun ShoppingItemRow(
    item: ShoppingItem,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onToggle: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = adaptSurface(Color.White), shadowElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(shape = CircleShape, color = adaptSurface(Color(0xFFF5F7FA))) {
                IconButton(onClick = onMinus, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Remove, com.abfilepro.app.ui.i18n.a11yLabel(Icons.Default.Remove), tint = adaptInk(Color(0xFF475569))) }
            }
            Text("${item.quantity}", fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
            Surface(shape = CircleShape, color = adaptSurface(Color(0xFFFFEEF0))) {
                IconButton(onClick = onPlus, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Add, com.abfilepro.app.ui.i18n.a11yLabel(Icons.Default.Add), tint = adaptInk(Color(0xFFE11D2E))) }
            }
            Spacer(Modifier.weight(1f))
            Text(item.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = endText)
            Text(itemEmoji(item.name, item.category), fontSize = 27.sp)
            Surface(
                modifier = Modifier.size(38.dp).clickable(onClick = onToggle),
                shape = CircleShape,
                color = if (item.completed) adaptInk(Color(0xFF10B981)) else Color.White,
                border = androidx.compose.foundation.BorderStroke(2.dp, if (item.completed) Color(0xFF10B981) else Color(0xFFD5DBE3)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (item.completed) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

internal fun inferCategory(name: String): ShoppingCategory {
    val n = name.lowercase()
    return when {
        listOf("حليب", "لبن", "زبادي", "جبن", "جبنة", "قشطة").any { it in n } -> ShoppingCategory.DAIRY
        listOf("فرخة", "فراخ", "بانيه", "اوراك", "أوراك", "صدور", "اجنحة", "أجنحة").any { it in n } -> ShoppingCategory.POULTRY
        listOf("لحمة", "لحم", "كفتة", "مفروم", "ستيك").any { it in n } -> ShoppingCategory.MEAT
        listOf("عصير", "مياه", "ماء", "مشروب", "شاي", "قهوة").any { it in n } -> ShoppingCategory.DRINKS
        listOf("مسحوق", "منظف", "صابون", "كلور", "مبيض", "غسيل", "مطهر").any { it in n } -> ShoppingCategory.CLEANING
        else -> ShoppingCategory.PRODUCE
    }
}

internal fun itemEmoji(name: String, category: ShoppingCategory): String {
    val n = name.lowercase()
    return when {
        "طماطم" in n -> "🍅"
        "خيار" in n -> "🥒"
        "موز" in n -> "🍌"
        "حليب" in n || "لبن" in n -> "🥛"
        "زبادي" in n -> "🥣"
        "جبن" in n -> "🧀"
        "مسحوق" in n || "غسيل" in n -> "🧴"
        "منظف" in n -> "🧽"
        "بانيه" in n || "صدور" in n -> "🍗"
        "فرخة" in n || "فراخ" in n -> "🐔"
        "اوراك" in n || "أوراك" in n -> "🍗"
        "لحمة" in n || "لحم" in n || "كفتة" in n -> "🥩"
        "عيش" in n || "خبز" in n -> "🍞"
        "عصير" in n -> "🧃"
        category == ShoppingCategory.PRODUCE -> "🥬"
        category == ShoppingCategory.DAIRY -> "🥛"
        category == ShoppingCategory.POULTRY -> "🍗"
        category == ShoppingCategory.MEAT -> "🥩"
        category == ShoppingCategory.BAKERY -> "🍞"
        category == ShoppingCategory.DRINKS -> "🧃"
        category == ShoppingCategory.CLEANING -> "🧴"
        else -> "📦"
    }
}

internal fun catalogFor(category: ShoppingCategory): List<String> = when (category) {
    ShoppingCategory.PRODUCE -> listOf("طماطم", "خيار", "بطاطس", "بصل", "فلفل", "موز", "تفاح", "برتقال")
    ShoppingCategory.DAIRY -> listOf("حليب", "زبادي", "جبنة بيضاء", "جبنة رومي", "قشطة", "زبدة")
    ShoppingCategory.POULTRY -> listOf("فرخة كاملة", "بانيه", "أوراك", "صدور", "أجنحة", "كبد وقوانص")
    ShoppingCategory.MEAT -> listOf("لحمة مكعبات", "لحمة مفرومة", "كفتة", "ستيك", "كبدة")
    ShoppingCategory.GROCERY -> listOf("أرز", "مكرونة", "سكر", "زيت", "دقيق", "صلصة", "فول", "عدس")
    ShoppingCategory.BAKERY -> listOf("عيش بلدي", "توست", "فينو", "كرواسون")
    ShoppingCategory.DRINKS -> listOf("مياه", "عصير", "شاي", "قهوة", "مشروب غازي")
    ShoppingCategory.FROZEN -> listOf("خضار مجمد", "بطاطس مجمدة", "برجر", "سمك مجمد")
    ShoppingCategory.PERSONAL -> listOf("شامبو", "معجون أسنان", "صابون", "مزيل عرق")
    ShoppingCategory.PHARMACY -> listOf("مسكن", "فيتامينات", "مطهر", "شاش")
    ShoppingCategory.HOUSEHOLD -> listOf("مناديل", "أكياس قمامة", "ورق مطبخ", "فويل")
    ShoppingCategory.CLEANING -> listOf("مسحوق غسيل", "منظف عام", "كلور", "مطهر أرضيات", "سائل أطباق")
    ShoppingCategory.OTHER -> listOf("صنف آخر")
}

internal fun addFamilyRequestToShopping(
    context: Context,
    requestId: String,
    name: String,
    quantity: Int,
    categoryName: String,
) {
    val current = ShoppingListStore.load(context).toMutableList()
    val category = ShoppingCategory.entries.firstOrNull { it.name == categoryName } ?: inferCategory(name)
    val existingIndex = current.indexOfFirst { it.familyRequestId == requestId }
    val item = ShoppingItem(
        id = current.getOrNull(existingIndex)?.id ?: System.currentTimeMillis(),
        name = name.trim().ifBlank { "طلب عائلي" },
        quantity = quantity.coerceIn(1, 99),
        completed = false,
        category = category,
        familyRequestId = requestId,
    )
    if (existingIndex >= 0) current[existingIndex] = item else current.add(item)
    ShoppingListStore.save(context, current)
}

internal fun setFamilyRequestShoppingCompletion(context: Context, requestId: String, completed: Boolean) {
    val current = ShoppingListStore.load(context)
    ShoppingListStore.save(context, current.map { if (it.familyRequestId == requestId) it.copy(completed = completed) else it })
}

internal fun removeFamilyRequestFromShopping(context: Context, requestId: String) {
    ShoppingListStore.save(context, ShoppingListStore.load(context).filterNot { it.familyRequestId == requestId })
}

private fun buildShoppingShareText(listName: String, items: List<ShoppingItem>): String = buildString {
    appendLine(listName)
    ShoppingCategory.entries.forEach { category ->
        val rows = items.filter { it.category == category }
        if (rows.isNotEmpty()) {
            appendLine("\n${category.arabicLabel}")
            rows.forEach { item -> appendLine("${if (item.completed) "✓" else "○"} ${item.name} × ${item.quantity}") }
        }
    }
}.trim()
