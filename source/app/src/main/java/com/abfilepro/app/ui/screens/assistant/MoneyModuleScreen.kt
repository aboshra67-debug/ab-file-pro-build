package com.abfilepro.app.ui.screens.assistant

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abfilepro.app.R
import com.abfilepro.app.assistant.AssistantCategory
import com.abfilepro.app.assistant.AssistantEntry
import com.abfilepro.app.assistant.AssistantRepository
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import com.abfilepro.app.assistant.MoneyRecurringRule
import com.abfilepro.app.assistant.MoneyRecurringStore
import com.abfilepro.app.billing.ProFeature
import com.abfilepro.app.billing.ProState

/**
 * Alpha74 operational Money screen.
 *
 * The approved pa_ref_money visual remains the design source. This screen recreates that same
 * hierarchy with real Compose controls and persisted local data instead of a full-page raster.
 */
@Composable
internal fun MoneyModuleScreen(
    entries: List<AssistantEntry>,
    defaultCategory: AssistantCategory,
    onBack: () -> Unit,
    onAdd: (String, String, AssistantCategory, Long?, Double?, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { AssistantRepository(context) }
    val zone = remember { ZoneId.systemDefault() }
    val arabicLocale = remember { Locale("ar", "EG") }

    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    var budgetRevision by remember { mutableStateOf(0) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var showExpenseDialog by remember { mutableStateOf(false) }
    var showAllExpenses by remember { mutableStateOf(false) }
    var showCategoryDetails by remember { mutableStateOf(false) }
    var recurringRevision by remember { mutableStateOf(0) }
    val recurringRules = remember(recurringRevision) { MoneyRecurringStore.load(context) }

    val monthKey = selectedMonth.toString()
    val budget = remember(monthKey, budgetRevision) { repository.loadMonthlyBudget(monthKey) }
    val monthExpenses = remember(entries, selectedMonth) {
        entries
            .filter { it.amount != null && it.amount > 0.0 }
            .filter { entryMonth(it, zone) == selectedMonth }
            .sortedByDescending { it.dueAt ?: it.createdAt }
    }
    val spent = monthExpenses.sumOf { it.amount ?: 0.0 }
    val remaining = max(0.0, budget - spent)
    val remainingRatio = if (budget > 0.0) (remaining / budget).coerceIn(0.0, 1.0).toFloat() else 0f
    val today = LocalDate.now(zone)
    val todayExpenses = if (selectedMonth == YearMonth.from(today)) {
        monthExpenses.filter { entryDate(it, zone) == today }.take(3)
    } else {
        monthExpenses.take(3)
    }
    val categoryTotals = remember(monthExpenses) {
        MoneyCategory.entries.associateWith { category ->
            monthExpenses.filter { category.matches(it) }.sumOf { it.amount ?: 0.0 }
        }
    }

    if (showBudgetDialog) {
        BudgetDialog(
            month = selectedMonth,
            currentBudget = budget,
            onDismiss = { showBudgetDialog = false },
            onSave = { value ->
                repository.saveMonthlyBudget(monthKey, value)
                budgetRevision++
                showBudgetDialog = false
            },
        )
    }

    if (showExpenseDialog) {
        AddExpenseDialog(
            month = selectedMonth,
            defaultCategory = defaultCategory,
            onDismiss = { showExpenseDialog = false },
            onSave = { category, amount, note, date, repeatMonthly ->
                if (repeatMonthly) {
                    // The month recorded now counts; the rule adds the following months by itself.
                    MoneyRecurringStore.add(
                        context,
                        MoneyRecurringRule(
                            id = java.util.UUID.randomUUID().toString(), categoryKey = category.key, categoryArabic = category.arabic,
                            amount = amount, note = note, day = date.dayOfMonth, lastMonth = YearMonth.from(date).toString(),
                            work = defaultCategory == AssistantCategory.WORK,
                        ),
                    )
                    recurringRevision++
                }
                onAdd(
                    category.arabic,
                    note,
                    defaultCategory,
                    date.atTime(LocalTime.now()).atZone(zone).toInstant().toEpochMilli(),
                    amount,
                    "money_category=${category.key}",
                )
                showExpenseDialog = false
            },
        )
    }

    if (showAllExpenses) {
        ExpensesDialog(
            title = L("كل مصروفات ${monthLabel(selectedMonth, arabicLocale)}"),
            entries = monthExpenses,
            onDismiss = { showAllExpenses = false },
            onDelete = onDelete,
            zone = zone,
        )
    }

    if (showCategoryDetails) {
        CategoryDetailsDialog(
            totals = categoryTotals,
            totalSpent = spent,
            onDismiss = { showCategoryDetails = false },
        )
    }

    Scaffold(containerColor = adaptSurface(Color(0xFFF8FAFC))) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                MoneyHeader(
                    onBack = onBack,
                    onSettings = { showBudgetDialog = true },
                )
            }
            item {
                MonthSelector(
                    month = selectedMonth,
                    onPrevious = { selectedMonth = selectedMonth.minusMonths(1) },
                    onNext = { selectedMonth = selectedMonth.plusMonths(1) },
                )
            }
            item {
                BudgetHeroCard(
                    budget = budget,
                    spent = spent,
                    remaining = remaining,
                    remainingRatio = remainingRatio,
                )
            }
            item {
                RecentExpensesCard(
                    entries = todayExpenses,
                    selectedMonth = selectedMonth,
                    currentDate = today,
                    zone = zone,
                    onShowAll = { showAllExpenses = true },
                    onDelete = onDelete,
                )
            }
            item {
                CategoryBreakdownCard(
                    totals = categoryTotals,
                    totalSpent = spent,
                    onShowDetails = { showCategoryDetails = true },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { showBudgetDialog = true },
                        modifier = Modifier.weight(1f).height(62.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Icon(Icons.Default.CalendarMonth, null, tint = adaptInk(Color(0xFFFF2D35)))
                        Spacer(Modifier.width(8.dp))
                        Text(L("تحديد الميزانية"), fontWeight = FontWeight.ExtraBold, color = adaptInk(Color(0xFF111827)))
                    }
                    Button(
                        onClick = { showExpenseDialog = true },
                        modifier = Modifier.weight(1.05f).height(62.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2D35)),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(L("إضافة مصروف"), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = CircleShape, color = Color.White) {
                            Icon(Icons.Default.Add, null, tint = adaptInk(Color(0xFFFF2D35)), modifier = Modifier.padding(5.dp))
                        }
                    }
                }
            }
            if (recurringRules.isNotEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White))) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(L("مصروفات شهرية ثابتة"), fontWeight = FontWeight.Black, color = adaptInk(Color(0xFF111827)))
                            recurringRules.forEach { rule ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        L(rule.categoryArabic) + (if (rule.note.isNotBlank()) " • " + rule.note else "") + " • " + "%.0f".format(rule.amount) + " • " + rule.day,
                                        Modifier.weight(1f), fontSize = 13.sp, maxLines = 2,
                                    )
                                    TextButton(onClick = { MoneyRecurringStore.remove(context, rule.id); recurringRevision++ }) { Text(L("إيقاف")) }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun MoneyHeader(onBack: () -> Unit, onSettings: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(48.dp).clickable(onClick = onSettings),
                shape = CircleShape,
                color = Color(0xFFFFF0F0),
                shadowElevation = 3.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Settings, L("إعدادات"), tint = adaptInk(Color(0xFFB70F1B)))
                }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(L("المال والميزانية"), fontSize = 28.sp, fontWeight = FontWeight.Black, color = adaptInk(Color(0xFF111827)))
                Text(L("دفتر مصروفاتك الشخصي"), fontSize = 13.sp, color = adaptInk(Color(0xFF64748B)))
            }
            Surface(
                modifier = Modifier.size(48.dp).clickable(onClick = onBack),
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 4.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowForward, L("رجوع"), tint = adaptInk(Color(0xFF111827)))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = adaptSurface(Color(0xFFE3F8EF))) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp), tint = adaptInk(Color(0xFF0A9569)))
                Text(L("بياناتك خاصة ومحفوظة على جهازك"), color = adaptInk(Color(0xFF0A9569)), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MonthSelector(month: YearMonth, onPrevious: () -> Unit, onNext: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color.White,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious) { Icon(Icons.Default.KeyboardArrowRight, L("الشهر السابق")) }
            Text(
                monthLabel(month, Locale("ar", "EG")),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = adaptInk(Color(0xFF111827)),
            )
            IconButton(onClick = onNext) { Icon(Icons.Default.KeyboardArrowLeft, L("الشهر التالي")) }
        }
    }
}

@Composable
private fun BudgetHeroCard(budget: Double, spent: Double, remaining: Double, remainingRatio: Float) {
    Card(
        colors = CardDefaults.cardColors(containerColor = adaptSurface(Color(0xFFECFCF6))),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier.weight(1f).height(150.dp).clip(RoundedCornerShape(20.dp)),
                ) {
                    MoneyWalletIllustration(Modifier.fillMaxSize())
                }
                Box(
                    modifier = Modifier.weight(1f).height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { remainingRatio },
                        modifier = Modifier.size(142.dp),
                        color = adaptInk(Color(0xFF08B889)),
                        trackColor = Color(0xFFE3E8EE),
                        strokeWidth = 13.dp,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(L("المتبقي"), fontWeight = FontWeight.Black, fontSize = 16.sp, color = adaptInk(Color(0xFF111827)))
                        Text(formatMoney(remaining), fontWeight = FontWeight.Black, fontSize = 27.sp, color = adaptInk(Color(0xFF04966E)))
                        Text(L("ج.م"), color = adaptInk(Color(0xFF04966E)), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(Modifier.height(2.dp))
                        Text("${(remainingRatio * 100).toInt()}٪", fontWeight = FontWeight.Bold, color = adaptInk(Color(0xFF111827)))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyMiniCard("المتبقي", remaining, Color(0xFFE1F8EE), Color(0xFF059669), Modifier.weight(1f))
                MoneyMiniCard("المصروفات", spent, Color(0xFFE6F2FF), Color(0xFF1677E8), Modifier.weight(1f))
                MoneyMiniCard("الميزانية", budget, Color(0xFFFFE8E9), Color(0xFFFF2D35), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MoneyWalletIllustration(modifier: Modifier = Modifier) {
    val reference = ImageBitmap.imageResource(R.drawable.pa_ref_money)
    Canvas(modifier = modifier) {
        drawImage(
            image = reference,
            srcOffset = IntOffset(55, 202),
            srcSize = IntSize(260, 216),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}

@Composable
private fun MoneyMiniCard(label: String, value: Double, background: Color, accent: Color, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(17.dp), color = background) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(8.dp).background(accent, CircleShape))
                Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = adaptInk(Color(0xFF1F2937)))
            }
            Text(formatMoney(value), color = accent, fontWeight = FontWeight.Black, fontSize = 19.sp, maxLines = 1)
            Text(L("ج.م"), color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RecentExpensesCard(
    entries: List<AssistantEntry>,
    selectedMonth: YearMonth,
    currentDate: LocalDate,
    zone: ZoneId,
    onShowAll: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onShowAll) {
                    Icon(Icons.Default.ArrowBack, null, tint = adaptInk(Color(0xFFFF2D35)), modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(L("عرض الكل"), color = adaptInk(Color(0xFFFF2D35)), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (selectedMonth == YearMonth.from(currentDate)) "مصروفات اليوم" else "أحدث مصروفات الشهر",
                        fontWeight = FontWeight.Black,
                        fontSize = 21.sp,
                    )
                    Text(
                        if (selectedMonth == YearMonth.from(currentDate)) dateLabel(currentDate) else monthLabel(selectedMonth, Locale("ar", "EG")),
                        color = adaptInk(Color(0xFF64748B)),
                        fontSize = 11.sp,
                    )
                }
            }
            if (entries.isEmpty()) {
                Surface(shape = RoundedCornerShape(16.dp), color = adaptSurface(Color(0xFFF8FAFC))) {
                    Text(L("لا توجد مصروفات مسجلة هنا"), modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center, color = adaptInk(Color(0xFF64748B)))
                }
            } else {
                entries.forEach { entry -> ExpenseRow(entry, zone, onDelete) }
            }
        }
    }
}

@Composable
private fun ExpenseRow(entry: AssistantEntry, zone: ZoneId, onDelete: (String) -> Unit) {
    val category = MoneyCategory.fromEntry(entry)
    Surface(shape = RoundedCornerShape(15.dp), color = adaptSurface(Color(0xFFFDFEFE))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatMoney(entry.amount ?: 0.0) + " ج.م", color = adaptInk(Color(0xFFFF2D35)), fontWeight = FontWeight.Black, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(category.arabic, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Text(timeLabel(entry, zone), color = adaptInk(Color(0xFF7C8797)), fontSize = 10.sp)
            }
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = category.tint) {
                Icon(category.icon, category.arabic, tint = category.color, modifier = Modifier.padding(9.dp).size(22.dp))
            }
            IconButton(onClick = { onDelete(entry.id) }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.DeleteOutline, L("حذف"), tint = adaptInk(Color(0xFFFF5A63)), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun CategoryBreakdownCard(
    totals: Map<MoneyCategory, Double>,
    totalSpent: Double,
    onShowDetails: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = adaptSurface(Color(0xFFFFF8E8))),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onShowDetails, shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Default.KeyboardArrowRight, null, modifier = Modifier.size(18.dp))
                    Text(L("عرض التفاصيل"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(L("المصروفات حسب الفئات"), fontWeight = FontWeight.Black, fontSize = 21.sp)
                    Text(L("هذا الشهر"), color = adaptInk(Color(0xFF7C8797)), fontSize = 11.sp)
                }
            }
            MoneyCategory.entries.forEach { category ->
                val amount = totals[category] ?: 0.0
                val fraction = if (totalSpent > 0.0) (amount / totalSpent).toFloat().coerceIn(0f, 1f) else 0f
                CategoryBarRow(category, amount, fraction)
            }
        }
    }
}

@Composable
private fun CategoryBarRow(category: MoneyCategory, amount: Double, fraction: Float) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${(fraction * 100).toInt()}٪", modifier = Modifier.width(42.dp), fontWeight = FontWeight.Bold, color = adaptInk(Color(0xFF334155)), fontSize = 12.sp)
        Text(formatMoney(amount) + " ج.م", modifier = Modifier.width(80.dp), color = adaptInk(Color(0xFFE43A42)), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Box(
            modifier = Modifier.weight(1f).height(10.dp).clip(CircleShape).background(adaptSurface(Color(0xFFE7EBEF))),
        ) {
            if (fraction > 0f) {
                Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.03f)).height(10.dp).background(category.color, CircleShape))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(category.arabic, modifier = Modifier.width(92.dp), textAlign = TextAlign.End, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Surface(shape = RoundedCornerShape(10.dp), color = category.tint) {
            Icon(category.icon, null, tint = category.color, modifier = Modifier.padding(6.dp).size(18.dp))
        }
    }
}

@Composable
private fun BudgetDialog(month: YearMonth, currentBudget: Double, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by remember(month, currentBudget) { mutableStateOf(if (currentBudget > 0.0) currentBudget.toString().trimEnd('0').trimEnd('.') else "") }
    val value = text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تحديد ميزانية ${monthLabel(month, Locale("ar", "EG"))}", fontWeight = FontWeight.Black) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { raw -> text = sanitizeMoneyInput(raw) },
                label = { Text(L("قيمة الميزانية بالجنيه")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { value?.let(onSave) }, enabled = value != null, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2D35))) {
                Text(L("حفظ"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("إلغاء")) } },
    )
}

@Composable
private fun AddExpenseDialog(
    month: YearMonth,
    defaultCategory: AssistantCategory,
    onDismiss: () -> Unit,
    onSave: (MoneyCategory, Double, String, LocalDate, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var repeatMonthly by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MoneyCategory.HOME) }
    var date by remember(month) {
        mutableStateOf(
            if (month == YearMonth.now()) LocalDate.now() else month.atDay(1)
        )
    }
    val amount = amountText.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("إضافة مصروف"), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = sanitizeMoneyInput(it) },
                    label = { Text(L("المبلغ")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(L("الفئة"), fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MoneyCategory.entries.forEach { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = { category = item },
                            label = { Text(item.shortArabic, fontSize = 10.sp) },
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(160) },
                    label = { Text(L("ملاحظة اختيارية")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        DatePickerDialog(
                            context,
                            { _, y, m, d -> date = LocalDate.of(y, m + 1, d) },
                            date.year,
                            date.monthValue - 1,
                            date.dayOfMonth,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.CalendarMonth, null)
                    Spacer(Modifier.width(6.dp))
                    Text(dateLabel(date))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(L("مصروف شهري ثابت (يتكرر كل شهر)"), Modifier.weight(1f), fontSize = 13.sp)
                    Switch(checked = repeatMonthly, onCheckedChange = { on -> repeatMonthly = on && ProState.allowOrAsk(ProFeature.MONEY_RECURRING, MoneyRecurringStore.load(context).size) })
                }
                Text(
                    if (defaultCategory == AssistantCategory.WORK) "سيُحفظ المصروف ضمن قسم العمل" else "سيُحفظ المصروف على جهازك فقط",
                    color = adaptInk(Color(0xFF64748B)),
                    fontSize = 10.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { amount?.let { onSave(category, it, note.trim(), date, repeatMonthly) } },
                enabled = amount != null,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2D35)),
            ) { Text(L("إضافة")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("إلغاء")) } },
    )
}

@Composable
private fun ExpensesDialog(
    title: String,
    entries: List<AssistantEntry>,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit,
    zone: ZoneId,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Black) },
        text = {
            if (entries.isEmpty()) {
                Text(L("لا توجد مصروفات في هذا الشهر"))
            } else {
                LazyColumn(modifier = Modifier.height(360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries, key = { it.id }) { entry -> ExpenseRow(entry, zone, onDelete) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L("إغلاق")) } },
    )
}

@Composable
private fun CategoryDetailsDialog(
    totals: Map<MoneyCategory, Double>,
    totalSpent: Double,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("تفاصيل المصروفات حسب الفئات"), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MoneyCategory.entries.forEach { category ->
                    val amount = totals[category] ?: 0.0
                    val percent = if (totalSpent > 0.0) amount / totalSpent * 100.0 else 0.0
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(10.dp), color = category.tint) {
                            Icon(category.icon, null, tint = category.color, modifier = Modifier.padding(7.dp).size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(category.arabic, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text("${formatMoney(amount)} ج.م  •  ${percent.toInt()}٪", color = category.color, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L("إغلاق")) } },
    )
}

private enum class MoneyCategory(
    val key: String,
    val arabic: String,
    val shortArabic: String,
    val icon: ImageVector,
    val color: Color,
    val tint: Color,
) {
    HOME("home", "مشتريات المنزل", "المنزل", Icons.Default.Home, Color(0xFFFF3B3F), Color(0xFFFFE7E8)),
    TRANSPORT("transport", "مواصلات", "مواصلات", Icons.Default.DirectionsBus, Color(0xFF2489F5), Color(0xFFE8F2FF)),
    FOOD("food", "طعام ومشروبات", "طعام", Icons.Default.Restaurant, Color(0xFF07A879), Color(0xFFE1F8EF)),
    PHARMACY("pharmacy", "صيدلية", "صيدلية", Icons.Default.LocalPharmacy, Color(0xFFFFB01F), Color(0xFFFFF2CE)),
    OTHER("other", "أخرى", "أخرى", Icons.Default.MoreHoriz, Color(0xFFAA44F5), Color(0xFFF3E7FF));

    fun matches(entry: AssistantEntry): Boolean = fromEntry(entry) == this

    companion object {
        fun fromEntry(entry: AssistantEntry): MoneyCategory {
            val encoded = entry.secondary.substringAfter("money_category=", "").substringBefore(';')
            if (encoded.isNotBlank()) entries.firstOrNull { it.key == encoded }?.let { return it }
            val title = entry.title.trim()
            return entries.firstOrNull { title.contains(it.arabic, ignoreCase = true) } ?: OTHER
        }
    }
}

private fun entryDate(entry: AssistantEntry, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(entry.dueAt ?: entry.createdAt).atZone(zone).toLocalDate()

private fun entryMonth(entry: AssistantEntry, zone: ZoneId): YearMonth = YearMonth.from(entryDate(entry, zone))

private fun timeLabel(entry: AssistantEntry, zone: ZoneId): String {
    val instant = Instant.ofEpochMilli(entry.dueAt ?: entry.createdAt).atZone(zone)
    return instant.format(DateTimeFormatter.ofPattern("h:mm a", Locale("ar", "EG")))
}

private fun monthLabel(month: YearMonth, locale: Locale): String =
    month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))

private fun dateLabel(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("EEEE، d MMMM yyyy", Locale("ar", "EG")))

private fun formatMoney(value: Double): String = NumberFormat.getNumberInstance(Locale("ar", "EG")).apply {
    maximumFractionDigits = if (value % 1.0 == 0.0) 0 else 2
    minimumFractionDigits = 0
}.format(value.coerceAtLeast(0.0))

private fun sanitizeMoneyInput(raw: String): String {
    val latin = buildString {
        raw.forEach { ch ->
            append(
                when (ch) {
                    in '٠'..'٩' -> ('0'.code + (ch.code - '٠'.code)).toChar()
                    '٫', ',' -> '.'
                    else -> ch
                }
            )
        }
    }
    var dotSeen = false
    return buildString {
        latin.forEach { ch ->
            when {
                ch.isDigit() -> append(ch)
                ch == '.' && !dotSeen -> { append(ch); dotSeen = true }
            }
        }
    }.take(14)
}
