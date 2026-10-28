package com.abfilepro.app.ui.screens.assistant

import android.app.DatePickerDialog
import android.content.Context
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.LocalGasStation
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.abfilepro.app.assistant.BillAlarmScheduler

internal data class BillProvider(
    val title: String,
    val packageName: String,
    val search: String,
)

private enum class BillType(val arabicLabel: String, val icon: ImageVector, val tint: Color, val bg: Color) {
    ELECTRICITY("الكهرباء", Icons.Default.Bolt, Color(0xFFF5B000), Color(0xFFFFF7DE)),
    WATER("المياه", Icons.Default.WaterDrop, Color(0xFF248BFF), Color(0xFFF2F8FF)),
    GAS("الغاز", Icons.Outlined.LocalGasStation, Color(0xFF3477F6), Color(0xFFFFF7F1)),
    INTERNET("الإنترنت", Icons.Default.Router, Color(0xFF0AB07B), Color(0xFFEFFFF9)),
    PHONE("الهاتف", Icons.Default.Phone, Color(0xFF5F5ACF), Color(0xFFF7F2FF)),
    MOBILE("المحمول", Icons.Default.Public, Color(0xFFF25462), Color(0xFFFFF1F4)),
    ;

    companion object {
        fun fromName(name: String): BillType? = entries.firstOrNull { it.name == name }
    }
}

private data class BillReminder(
    val id: Long,
    val type: BillType,
    val dueAtMillis: Long,
    val accountHint: String = "",
    val providerPackage: String = "",
)

private object BillReminderStore {
    private const val PREFS = "ab_file_pro_bill_reminders"
    private const val KEY = "items_v1"

    fun load(context: Context): List<BillReminder> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val type = BillType.fromName(o.optString("type")) ?: continue
                    val due = o.optLong("due", 0L)
                    if (due <= 0L) continue
                    add(
                        BillReminder(
                            id = o.optLong("id", System.currentTimeMillis() + i),
                            type = type,
                            dueAtMillis = due,
                            accountHint = o.optString("account", ""),
                            providerPackage = o.optString("provider", ""),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, items: List<BillReminder>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("type", item.type.name)
                    .put("due", item.dueAtMillis)
                    .put("account", item.accountHint)
                    .put("provider", item.providerPackage)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(context: Context, item: BillReminder) {
        val all = load(context).toMutableList()
        val idx = all.indexOfFirst { it.id == item.id }
        if (idx >= 0) all[idx] = item else all.add(item)
        save(context, all)
    }

    fun delete(context: Context, id: Long) = save(context, load(context).filterNot { it.id == id })
}

@Composable
internal fun BillPaymentOperationalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val providers = remember {
        listOf(
            BillProvider("إنستاباي", "com.egyptianbanks.instapay", "InstaPay"),
            BillProvider("سهل", "com.sahl.app", "Sahl Egypt"),
            BillProvider("فوري", "com.fawry.myfawry", "myFawry"),
        )
    }
    var selectedType by remember { mutableStateOf(BillType.ELECTRICITY) }
    var reminderRevision by remember { mutableStateOf(0) }
    val reminders = remember(reminderRevision) { BillReminderStore.load(context).sortedBy { it.dueAtMillis } }
    val upcoming = reminders.firstOrNull { it.dueAtMillis >= startOfToday() } ?: reminders.firstOrNull()
    var showDialog by remember { mutableStateOf(false) }
    var selectedProvider by remember { mutableStateOf<BillProvider?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(adaptSurface(Color.White))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeaderBills(onBack = onBack)

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = adaptSurface(Color(0xFFEFFFF8))),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(22.dp), color = Color.White.copy(alpha = .7f)) {
                    Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                        Icon(selectedType.icon, null, tint = selectedType.tint, modifier = Modifier.size(52.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = endAlign) {
                    Text(L("اختر نوع الفاتورة ثم افتح تطبيق الدفع المثبت لديك"), fontWeight = FontWeight.Black, fontSize = 24.sp, lineHeight = 28.sp, textAlign = endText)
                    Spacer(Modifier.height(6.dp))
                    Text(L("سيتم توجيهك إلى تطبيق الدفع الموجود على هاتفك لإتمام عملية الدفع."), color = adaptInk(Color(0xFF6B7280)), fontSize = 14.sp, textAlign = endText)
                }
                Surface(shape = CircleShape, color = adaptSurface(Color(0xFFDDF9E9))) {
                    Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Info, null, tint = adaptInk(Color(0xFF0DAA60)))
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            BillType.entries.toList().chunked(3).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    rowItems.forEach { type ->
                        BillTypeCard(type = type, selected = selectedType == type, modifier = Modifier.weight(1f)) {
                            selectedType = type
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(L("تطبيقات الدفع المتاحة"), fontWeight = FontWeight.Black, fontSize = 24.sp, modifier = Modifier.fillMaxWidth(), textAlign = endText)
            Text(L("اختر أحد التطبيقات المثبتة على هاتفك"), color = adaptInk(Color(0xFF6B7280)), fontSize = 14.sp, modifier = Modifier.fillMaxWidth(), textAlign = endText)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            providers.forEach { provider ->
                ProviderCard(provider = provider, selected = selectedProvider?.packageName == provider.packageName) {
                    selectedProvider = provider
                    launchPackageOrStore(context, provider.packageName, provider.search)
                }
            }
        }

        if (upcoming != null) {
            UpcomingReminderCard(
                reminder = upcoming,
                providerName = providers.firstOrNull { it.packageName == upcoming.providerPackage }?.title,
                onDelete = {
                    BillReminderStore.delete(context, upcoming.id)
                    BillAlarmScheduler.cancel(context, upcoming.id)
                    reminderRevision++
                    Toast.makeText(context, L("تم حذف التذكير"), Toast.LENGTH_SHORT).show()
                },
            )
        }

        Button(
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth().height(58.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2635)),
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(L("إضافة تذكير فاتورة"), fontWeight = FontWeight.Black, fontSize = 20.sp)
        }
    }

    if (showDialog) {
        AddBillReminderDialog(
            selectedType = selectedType,
            providers = providers,
            onDismiss = { showDialog = false },
            onSave = { item ->
                BillReminderStore.upsert(context, item)
                BillAlarmScheduler.sync(context, item.id, item.dueAtMillis)
                reminderRevision++
                showDialog = false
                Toast.makeText(context, L("تم حفظ التذكير"), Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun HeaderBills(onBack: () -> Unit) {
    HeaderMirror { Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(Alignment.CenterStart).size(46.dp),
            shape = CircleShape,
            color = adaptSurface(Color(0xFFF9FAFB)),
        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("⋮", fontSize = 28.sp) } }
        Text(L("دفع الفواتير"), modifier = Modifier.align(Alignment.Center), fontSize = 28.sp, fontWeight = FontWeight.Black)
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).size(46.dp).clickable(onClick = onBack),
            shape = CircleShape,
            color = adaptSurface(Color(0xFFF9FAFB)),
        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("≡", fontSize = 24.sp, fontWeight = FontWeight.Bold) } }
    } }
}

@Composable
private fun BillTypeCard(type: BillType, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) type.bg.copy(alpha = 0.95f) else type.bg),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 4.dp else 1.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Surface(shape = CircleShape, color = Color.White.copy(alpha = .85f)) {
                    Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) { Text("›", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Surface(shape = CircleShape, color = Color.White.copy(alpha = .55f)) {
                Box(modifier = Modifier.size(78.dp), contentAlignment = Alignment.Center) {
                    Icon(type.icon, null, tint = type.tint, modifier = Modifier.size(48.dp))
                }
            }
            Text(L(type.arabicLabel), fontWeight = FontWeight.Black, fontSize = if (AssistantLang.language == com.abfilepro.app.ui.i18n.AppLanguage.ARABIC) 18.sp else 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ProviderCard(provider: BillProvider, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val installed = remember(provider.packageName) { context.packageManager.getLaunchIntentForPackage(provider.packageName) != null }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = adaptSurface(Color.White)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 3.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = CircleShape, color = adaptSurface(Color(0xFFF3F7FF))) {
                Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) { Text("›", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            }
            Surface(shape = RoundedCornerShape(14.dp), color = adaptSurface(Color(0xFFF8FAFC))) {
                Box(modifier = Modifier.size(width = 72.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                    Text(provider.title, fontWeight = FontWeight.Black, color = adaptInk(Color(0xFF111827)), textAlign = TextAlign.Center)
                }
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = endAlign) {
                Text(provider.title, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text(L(if (installed) "مثبت على الجهاز" else "غير مثبت — سيتم فتح المتجر"), color = if (installed) adaptInk(Color(0xFF0DAA60)) else adaptInk(Color(0xFF6B7280)), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun UpcomingReminderCard(reminder: BillReminder, providerName: String?, onDelete: () -> Unit) {
    val days = daysUntil(reminder.dueAtMillis)
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = adaptSurface(Color(0xFFFFF9E9))),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = adaptSurface(Color(0xFFFFF1C7))) {
                Box(modifier = Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    Icon(reminder.type.icon, null, tint = reminder.type.tint, modifier = Modifier.size(44.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = endAlign) {
                Text(L("التذكيرات القادمة"), color = adaptInk(Color(0xFF6B7280)), fontSize = 14.sp)
                Text(L("فاتورة ${L(reminder.type.arabicLabel)}"), fontWeight = FontWeight.Black, fontSize = 24.sp)
                Text(
                    when {
                        days < 0 -> L("متأخرة منذ ${-days} يوم")
                        days == 0L -> L("تستحق اليوم")
                        else -> L("تستحق خلال $days أيام")
                    },
                    color = adaptInk(Color(0xFFFF3B3B)),
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                )
                Text(formatDate(reminder.dueAtMillis) + (providerName?.let { " • $it" } ?: ""), color = adaptInk(Color(0xFF6B7280)), fontSize = 12.sp)
            }
            IconButton(onClick = onDelete) { Text("×", fontSize = 22.sp, color = adaptInk(Color(0xFFB91C1C))) }
        }
    }
}

@Composable
private fun AddBillReminderDialog(
    selectedType: BillType,
    providers: List<BillProvider>,
    onDismiss: () -> Unit,
    onSave: (BillReminder) -> Unit,
) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(selectedType) }
    var accountHint by remember { mutableStateOf("") }
    var dueAtMillis by remember { mutableStateOf(nextMonthMillis()) }
    var providerPackage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("إضافة تذكير فاتورة"), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BillType.entries.toList().chunked(3).forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            group.forEach { item ->
                                FilterChip(
                                    selected = type == item,
                                    onClick = { type = item },
                                    label = { Text(L(item.arabicLabel)) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = accountHint,
                    onValueChange = { accountHint = it.take(50) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(L("رقم الحساب / الاشتراك (اختياري)")) },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        val cal = Calendar.getInstance().apply { timeInMillis = dueAtMillis }
                        DatePickerDialog(
                            context,
                            { _, y, m, d ->
                                Calendar.getInstance().apply {
                                    set(Calendar.YEAR, y)
                                    set(Calendar.MONTH, m)
                                    set(Calendar.DAY_OF_MONTH, d)
                                    set(Calendar.HOUR_OF_DAY, 9)
                                    set(Calendar.MINUTE, 0)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                    dueAtMillis = timeInMillis
                                }
                            },
                            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = adaptSurface(Color(0xFFF3F7FF)), contentColor = adaptInk(Color(0xFF173B65))),
                ) { Text(L("تاريخ الاستحقاق: ${formatDate(dueAtMillis)}")) }
                Text(L("تطبيق الدفع (اختياري)"), fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    providers.chunked(3).forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            group.forEach { p ->
                                FilterChip(
                                    selected = providerPackage == p.packageName,
                                    onClick = { providerPackage = if (providerPackage == p.packageName) "" else p.packageName },
                                    label = { Text(p.title) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(BillReminder(System.currentTimeMillis(), type, dueAtMillis, accountHint.trim(), providerPackage))
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2635))) { Text(L("حفظ")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("إلغاء")) } },
    )
}

private fun formatDate(millis: Long): String = SimpleDateFormat("yyyy/MM/dd", assistantLocale(true)).format(Date(millis))

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun nextMonthMillis(): Long = Calendar.getInstance().apply { add(Calendar.MONTH, 1) }.timeInMillis

private fun daysUntil(target: Long): Long = ((target - startOfToday()) / (24L * 60L * 60L * 1000L))

private fun launchPackageOrStore(context: Context, packageName: String, search: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent != null) context.startActivity(intent)
    else {
        val url = "https://play.google.com/store/search?q=${Uri.encode(search)}&c=apps"
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
