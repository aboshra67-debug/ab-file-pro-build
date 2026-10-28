package com.abfilepro.app.ui.screens.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.abfilepro.app.assistant.CurrencyRateService
import com.abfilepro.app.assistant.CurrencyRatesSnapshot
import com.abfilepro.app.assistant.GoldPriceService
import com.abfilepro.app.assistant.GoldPriceSnapshot
import com.abfilepro.app.assistant.MarketAlertDirection
import com.abfilepro.app.assistant.MarketAlertStore
import com.abfilepro.app.assistant.MarketAsset
import com.abfilepro.app.assistant.MarketPriceAlert
import com.abfilepro.app.assistant.marketPriceEgp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Alpha77: native rebuild of the markets screen.
 * The old full-page raster background was removed; the screen is now a real Compose layout
 * while preserving the same content structure and most of the same visual language.
 */
@Composable
internal fun MarketsOperationalScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var selectedTab by remember { mutableStateOf(MarketTab.CURRENCIES) }
    var currency by remember { mutableStateOf<CurrencyRatesSnapshot?>(null) }
    var gold by remember { mutableStateOf<GoldPriceSnapshot?>(null) }
    var loading by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf(false) }
    var showAlertDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var favoriteRevision by remember { mutableStateOf(0) }
    var alertRevision by remember { mutableStateOf(0) }

    val favorites = remember(favoriteRevision) { MarketAlertStore.favorites(context) }
    val alerts = remember(alertRevision) { MarketAlertStore.load(context) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    suspend fun refresh(force: Boolean) {
        if (loading) return
        loading = true
        val currencyResult = runCatching { CurrencyRateService.load(context, forceRefresh = force) }
        val goldResult = runCatching { GoldPriceService.load(context, forceRefresh = force) }
        currencyResult.getOrNull()?.let { currency = it }
        goldResult.getOrNull()?.let { gold = it }
        lastError = currency == null || gold == null
        loading = false
        if (force && lastError) Toast.makeText(context, L("تعذر تحديث أحد المصادر — تم الاحتفاظ بآخر بيانات متاحة"), Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        refresh(force = false)
        while (isActive) {
            delay(15L * 60L * 1000L)
            refresh(force = false)
        }
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(L("بيانات توضيحية"), fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(L("العملات: سعر صرف مرجعي مباشر مقابل الجنيه المصري."))
                    Text(L("الذهب: سعر عالمي مرجعي يتم تحويله إلى الجنيه المصري حسب العيار."))
                    Text(L("الأسعار استرشادية وقد تختلف عن السوق المحلي. التحديث التلقائي يتم كل 15 دقيقة."), color = adaptInk(Color(0xFF64748B)), fontSize = 12.sp)
                    Text(L("عدد التنبيهات المحفوظة: ${alerts.size}"), fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = { TextButton(onClick = { showInfoDialog = false }) { Text(L("إغلاق")) } },
        )
    }

    if (showAlertDialog) {
        MarketAlertDialog(
            currency = currency,
            gold = gold,
            alerts = alerts,
            onDismiss = { showAlertDialog = false },
            onSave = { alert ->
                MarketAlertStore.upsert(context, alert)
                alertRevision++
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onDelete = { id ->
                MarketAlertStore.delete(context, id)
                alertRevision++
            },
        )
    }

    val usd = marketPriceEgp(MarketAsset.USD, currency, gold)
    val sar = marketPriceEgp(MarketAsset.SAR, currency, gold)
    val gold24 = marketPriceEgp(MarketAsset.GOLD24, currency, gold)
    val gold21 = marketPriceEgp(MarketAsset.GOLD21, currency, gold)
    val gold18 = marketPriceEgp(MarketAsset.GOLD18, currency, gold)

    val firstSectionIsCurrencies = selectedTab == MarketTab.CURRENCIES

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(adaptSurface(Color.White))
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeaderBar(onBack = onBack, onInfo = { showInfoDialog = true })

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = adaptSurface(Color.White),
            tonalElevation = 1.dp,
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(54.dp).clickable { scope.launch { refresh(force = true) } },
                    shape = RoundedCornerShape(14.dp),
                    color = adaptSurface(Color(0xFFFFF0F1)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Refresh, contentDescription = L("تحديث"), tint = adaptInk(Color(0xFFE11D2E)), modifier = Modifier.size(28.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(updateLabel(currency, gold, loading, lastError), color = adaptInk(Color(0xFF475569)), fontWeight = FontWeight.Medium, fontSize = 15.sp, textAlign = TextAlign.End)
                    if (lastError) {
                        Text(L("تم الاحتفاظ بآخر بيانات متاحة"), color = adaptInk(Color(0xFF94A3B8)), fontSize = 11.sp)
                    }
                }
            }
        }

        SegmentedTabs(selectedTab = selectedTab, onSelect = { selectedTab = it })

        if (firstSectionIsCurrencies) {
            MarketsCurrencySection(usd, sar, favorites) { asset ->
                toggleFavorite(context, favorites, asset)
                favoriteRevision++
            }
            MarketsGoldSection(gold24, gold21, gold18, favorites) { asset ->
                toggleFavorite(context, favorites, asset)
                favoriteRevision++
            }
        } else {
            MarketsGoldSection(gold24, gold21, gold18, favorites) { asset ->
                toggleFavorite(context, favorites, asset)
                favoriteRevision++
            }
            MarketsCurrencySection(usd, sar, favorites) { asset ->
                toggleFavorite(context, favorites, asset)
                favoriteRevision++
            }
        }

        InfoPanel()

        Button(
            onClick = { showAlertDialog = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2635)),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(L("إنشاء تنبيه سعر"), fontWeight = FontWeight.Black, fontSize = 20.sp)
        }

        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun HeaderBar(onBack: () -> Unit, onInfo: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(Alignment.CenterStart).size(44.dp).clickable(onClick = onInfo),
            shape = CircleShape,
            color = adaptSurface(Color(0xFFF8FAFC)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Info, contentDescription = L("معلومات"), tint = adaptInk(Color(0xFF475569)))
            }
        }
        Text(
            text = L("العملات والذهب"),
            modifier = Modifier.align(Alignment.Center),
            fontWeight = FontWeight.Black,
            fontSize = 24.sp,
            color = adaptInk(Color(0xFF111827)),
        )
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).size(44.dp).clickable(onClick = onBack),
            shape = CircleShape,
            color = adaptSurface(Color(0xFFF8FAFC)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("›", fontSize = 28.sp, color = adaptInk(Color(0xFF111827)), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SegmentedTabs(selectedTab: MarketTab, onSelect: (MarketTab) -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = adaptSurface(Color(0xFFF8FAFC)), shadowElevation = 1.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            SegTab(L("الذهب"), selectedTab == MarketTab.GOLD, Modifier.weight(1f)) { onSelect(MarketTab.GOLD) }
            SegTab(L("العملات"), selectedTab == MarketTab.CURRENCIES, Modifier.weight(1f)) { onSelect(MarketTab.CURRENCIES) }
        }
    }
}

@Composable
private fun SegTab(title: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(52.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) adaptInk(Color(0xFFFF2635)) else Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(title, color = if (selected) Color.White else adaptInk(Color(0xFF111827)), fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
    }
}

@Composable
private fun MarketsCurrencySection(
    usd: Double?,
    sar: Double?,
    favorites: Set<MarketAsset>,
    onToggleFavorite: (MarketAsset) -> Unit,
) {
    SectionTitle(L("أسعار العملات"))
    CurrencyCard(
        title = L("الدولار الأمريكي"),
        code = "USD",
        asset = MarketAsset.USD,
        referenceValue = usd,
        accent = Color(0xFFE7FAF1),
        circle = Color(0xFFC9F1E0),
        favorites = favorites,
        onToggleFavorite = onToggleFavorite,
    )
    CurrencyCard(
        title = L("الريال السعودي"),
        code = "SAR",
        asset = MarketAsset.SAR,
        referenceValue = sar,
        accent = Color(0xFFEFF7FF),
        circle = Color(0xFFD6EAFE),
        favorites = favorites,
        onToggleFavorite = onToggleFavorite,
    )
}

@Composable
private fun CurrencyCard(
    title: String,
    code: String,
    asset: MarketAsset,
    referenceValue: Double?,
    accent: Color,
    circle: Color,
    favorites: Set<MarketAsset>,
    onToggleFavorite: (MarketAsset) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = adaptSurface(accent)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(104.dp).clip(RoundedCornerShape(18.dp)).background(adaptSurface(circle)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(code, fontSize = 28.sp, fontWeight = FontWeight.Black, color = adaptInk(Color(0xFF0F172A)))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { onToggleFavorite(asset) }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = if (asset in favorites) Icons.Default.Star else Icons.Outlined.StarOutline,
                                contentDescription = L("مفضلة"),
                                tint = if (asset in favorites) adaptInk(Color(0xFFF8B400)) else adaptInk(Color(0xFF94A3B8)),
                            )
                        }
                        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Black, color = adaptInk(Color(0xFF111827)), textAlign = TextAlign.End)
                    }
                }
            }
            PriceBox(
                label = L("سعر مرجعي"),
                value = referenceValue,
                modifier = Modifier.fillMaxWidth(),
                tint = Color(0xFFEFF6FF),
                valueColor = Color(0xFF173B65),
            )
        }
    }
}

@Composable
private fun PriceBox(label: String, value: Double?, modifier: Modifier, tint: Color, valueColor: Color) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = adaptSurface(tint)) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = adaptInk(valueColor))
            Text(formatMarketNumber(value, 2), fontWeight = FontWeight.Black, fontSize = 22.sp, color = adaptInk(valueColor))
            Text(L("ج.م"), color = adaptInk(Color(0xFF64748B)), fontSize = 12.sp)
        }
    }
}

@Composable
private fun MarketsGoldSection(
    gold24: Double?,
    gold21: Double?,
    gold18: Double?,
    favorites: Set<MarketAsset>,
    onToggleFavorite: (MarketAsset) -> Unit,
) {
    SectionTitle(L("أسعار الذهب"))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        GoldCard(L("عيار 24"), gold24, MarketAsset.GOLD24, favorites, Modifier.weight(1f), onToggleFavorite)
        GoldCard(L("عيار 21"), gold21, MarketAsset.GOLD21, favorites, Modifier.weight(1f), onToggleFavorite)
        GoldCard(L("عيار 18"), gold18, MarketAsset.GOLD18, favorites, Modifier.weight(1f), onToggleFavorite)
    }
}

@Composable
private fun GoldCard(
    title: String,
    value: Double?,
    asset: MarketAsset,
    favorites: Set<MarketAsset>,
    modifier: Modifier = Modifier,
    onToggleFavorite: (MarketAsset) -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = adaptSurface(Color(0xFFFFFBEE))),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = { onToggleFavorite(asset) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (asset in favorites) Icons.Default.Star else Icons.Outlined.StarOutline,
                        contentDescription = L("مفضلة"),
                        tint = if (asset in favorites) adaptInk(Color(0xFFF8B400)) else adaptInk(Color(0xFF94A3B8)),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Box(
                modifier = Modifier.size(62.dp).clip(RoundedCornerShape(16.dp)).background(
                    Brush.verticalGradient(listOf(adaptSurface(Color(0xFFFFE69B)), adaptSurface(Color(0xFFF7C948))))
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text("Au", color = adaptInk(Color(0xFF7A4D00)), fontWeight = FontWeight.Black, fontSize = 22.sp)
            }
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = adaptInk(Color(0xFF1F2937)))
            Text(formatMarketNumber(value, 0), fontWeight = FontWeight.Black, fontSize = 22.sp, color = adaptInk(Color(0xFF6E3F00)))
            Text(L("ج.م للجرام"), fontSize = 11.sp, color = adaptInk(Color(0xFF64748B)), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(title, fontWeight = FontWeight.Black, fontSize = 20.sp, color = adaptInk(Color(0xFF111827)))
    }
}

@Composable
private fun InfoPanel() {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = adaptSurface(Color(0xFFF8FAFC))),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Surface(shape = CircleShape, color = adaptSurface(Color.White)) {
                    Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = adaptInk(Color(0xFF64748B)), modifier = Modifier.size(18.dp))
                    }
                }
                Text(L("بيانات توضيحية"), fontWeight = FontWeight.Black, fontSize = 18.sp, color = adaptInk(Color(0xFF111827)))
            }
            Text(
                L("الأسعار المعروضة مرجعية واسترشادية وقد تختلف عن أسعار البنوك والصرافة والسوق المحلي."),
                color = adaptInk(Color(0xFF6B7280)),
                fontSize = 13.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MarketAlertDialog(
    currency: CurrencyRatesSnapshot?,
    gold: GoldPriceSnapshot?,
    alerts: List<MarketPriceAlert>,
    onDismiss: () -> Unit,
    onSave: (MarketPriceAlert) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var asset by remember { mutableStateOf(MarketAsset.USD) }
    var direction by remember { mutableStateOf(MarketAlertDirection.ABOVE) }
    var targetText by remember { mutableStateOf("") }
    val current = marketPriceEgp(asset, currency, gold)
    val target = normalizeMarketInput(targetText).toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("إنشاء تنبيه سعر"), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L("اختر الأصل والسعر الذي تريد متابعته"), color = adaptInk(Color(0xFF64748B)), fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(MarketAsset.USD, MarketAsset.SAR).forEach { item ->
                        FilterChip(
                            selected = asset == item,
                            onClick = { asset = item },
                            label = { Text(L(if (item == MarketAsset.USD) "دولار" else "ريال")) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(MarketAsset.GOLD24, MarketAsset.GOLD21, MarketAsset.GOLD18).forEach { item ->
                        FilterChip(
                            selected = asset == item,
                            onClick = { asset = item },
                            label = { Text(L(item.arabicName.substringAfter("عيار "))) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    L("السعر الحالي: ${formatMarketNumber(current, if (asset == MarketAsset.USD || asset == MarketAsset.SAR) 2 else 0)} ج.م"),
                    fontWeight = FontWeight.Bold,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MarketAlertDirection.entries.forEach { item ->
                        FilterChip(
                            selected = direction == item,
                            onClick = { direction = item },
                            label = { Text(L(if (item == MarketAlertDirection.ABOVE) "أعلى من" else "أقل من")) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = normalizeMarketInput(it) },
                    label = { Text(L("السعر المستهدف بالجنيه")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (alerts.isNotEmpty()) {
                    Text(L("التنبيهات المحفوظة"), fontWeight = FontWeight.ExtraBold)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        alerts.take(4).forEach { alert ->
                            Card(colors = CardDefaults.cardColors(containerColor = adaptSurface(Color(0xFFF8FAFC))), shape = RoundedCornerShape(12.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                        Text(alert.asset.arabicName, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            L("${if (alert.direction == MarketAlertDirection.ABOVE) "≥" else "≤"} ${formatMarketNumber(alert.targetEgp, 2)} ج.م"),
                                            fontSize = 11.sp,
                                            color = adaptInk(Color(0xFF64748B)),
                                        )
                                    }
                                    IconButton(onClick = { onDelete(alert.id) }, modifier = Modifier.size(34.dp)) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = L("حذف"), tint = adaptInk(Color(0xFFE53935)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    target?.let {
                        onSave(MarketPriceAlert(id = System.currentTimeMillis(), asset = asset, targetEgp = it, direction = direction))
                        targetText = ""
                    }
                },
                enabled = target != null && current != null,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2D35)),
            ) { Text(L("حفظ التنبيه")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("إغلاق")) } },
    )
}

private fun updateLabel(
    currency: CurrencyRatesSnapshot?,
    gold: GoldPriceSnapshot?,
    loading: Boolean,
    hasError: Boolean,
): String {
    if (loading) return L("جارِ تحديث الأسعار…")
    val ts = maxOf(currency?.fetchedAtMillis ?: 0L, gold?.fetchedAtMillis ?: 0L)
    if (ts <= 0L) return L(if (hasError) "تعذر التحديث" else "في انتظار أول تحديث")
    val time = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", assistantLocale(true)))
    val cached = currency?.fromCache == true || gold?.fromCache == true
    return L("آخر تحديث $time${if (cached) " • محفوظ" else ""}")
}

private fun formatMarketNumber(value: Double?, decimals: Int): String {
    if (value == null || !value.isFinite()) return "—"
    return NumberFormat.getNumberInstance(assistantLocale(true)).apply {
        maximumFractionDigits = decimals
        minimumFractionDigits = decimals
    }.format(value)
}

private fun normalizeMarketInput(raw: String): String {
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
                ch == '.' && !dotSeen -> {
                    append(ch)
                    dotSeen = true
                }
            }
        }
    }.take(14)
}

private fun toggleFavorite(context: android.content.Context, favorites: Set<MarketAsset>, asset: MarketAsset) {
    val updated = favorites.toMutableSet().apply { if (!add(asset)) remove(asset) }
    MarketAlertStore.saveFavorites(context, updated)
}

private enum class MarketTab { CURRENCIES, GOLD }
