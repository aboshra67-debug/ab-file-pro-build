package com.abfilepro.app.ui.screens.assistant

import androidx.annotation.DrawableRes
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.abfilepro.app.R
import com.abfilepro.app.assistant.AssistantCategory
import com.abfilepro.app.assistant.AssistantEntry
import com.abfilepro.app.assistant.AssistantModule
import com.abfilepro.app.assistant.PrayerName
import com.abfilepro.app.assistant.PrayerSettings
import com.abfilepro.app.assistant.PrayerTimesCalculator
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.text.DecimalFormat
import com.abfilepro.app.assistant.AssistantDomain

/**
 * Alpha52 visual-match layer.
 *
 * The images in drawable-nodpi are direct crops from the user-approved
 * AB File Pro Personal Assistant Visual Implementation Guide v1.1 (pages 6-23).
 * This keeps the Arabic screen geometry, artwork, spacing, colours and typography
 * aligned with the approved reference instead of approximating them.
 *
 * Only invisible hit areas are layered above the approved reference. Existing
 * Alpha51 data, prayer, scanner and module logic remain below this visual layer.
 */
private const val REF_W = 644f
private const val REF_H = 1368f
private const val NO_NEXT_CUT_TOP = 368f
private const val NO_NEXT_CUT_BOTTOM = 456f
private const val NO_NEXT_CUT_H = NO_NEXT_CUT_BOTTOM - NO_NEXT_CUT_TOP
private const val REF_H_NO_NEXT = REF_H - NO_NEXT_CUT_H

private data class RefHit(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val action: () -> Unit,
)

@Composable
private fun ReferenceImage(
    @DrawableRes drawable: Int,
    refWidth: Float = REF_W,
    refHeight: Float = REF_H,
    modifier: Modifier = Modifier,
    hits: List<RefHit> = emptyList(),
) {
    // The PDF reference is a raster image whose X coordinates are always measured
    // from the physical LEFT edge. The app itself is RTL in Arabic, and Box's
    // TopStart origin becomes the physical right edge under RTL. absoluteOffset()
    // alone does not change that child origin. Force only this reference canvas
    // to LTR so every invisible hit area uses the same left-origin coordinate
    // system as the approved PDF image. The Arabic artwork remains unchanged.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier = modifier.background(adaptSurface(Color.White))) {
            Image(
                painter = painterResource(drawable),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            hits.forEach { hit ->
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .absoluteOffset(
                            x = maxWidth * (hit.x / refWidth),
                            y = maxHeight * (hit.y / refHeight),
                        )
                        .size(
                            width = maxWidth * (hit.w / refWidth),
                            height = maxHeight * (hit.h / refHeight),
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = hit.action,
                        )
                )
            }
        }
    }
}

@Composable
internal fun AssistantReferenceHome(
    prayerSettings: PrayerSettings,
    entries: List<AssistantEntry>,
    selectedCategory: AssistantCategory,
    scrollState: ScrollState,
    onCategoryChange: (AssistantCategory) -> Unit,
    onBack: () -> Unit,
    onOpenModule: (AssistantModule) -> Unit,
    onQuickAdd: () -> Unit,
    onOpenToday: () -> Unit,
    onUpdateEntry: (AssistantEntry) -> Unit,
) {
    val visibleEntries = remember(entries, selectedCategory) {
        if (selectedCategory == AssistantCategory.ALL) entries
        else entries.filter { it.category == selectedCategory }
    }
    val summary = remember(visibleEntries) { AssistantDomain.summary(visibleEntries) }
    val minuteBucket = System.currentTimeMillis() / 60_000L
    val nowMillis = System.currentTimeMillis()
    val nextEntry = remember(visibleEntries, minuteBucket) {
        visibleEntries
            .asSequence()
            .filter { !it.completed && it.dueAt != null && it.dueAt >= nowMillis - 60_000L }
            .minByOrNull { it.dueAt ?: Long.MAX_VALUE }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(adaptSurface(Color.White))
            .statusBarsPadding()
            .verticalScroll(scrollState)
    ) {
        // P27: «يومي» (today agenda, quick sentence add, search) sits above the approved artwork so its hit areas stay exact.
        TodayEntryBar(onOpenToday)
        // Alpha91: make the TOP CANVAS itself LTR so reference-image coordinates and
        // live overlays share the exact same physical-left origin. In Alpha90 only the
        // overlay children were switched to LTR while their parent Box remained RTL,
        // shifting the dynamic strip/summary/prayer overlay horizontally on Arabic UI.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val hasNextEntry = nextEntry != null
            val activeRefHeight = if (hasNextEntry) REF_H else REF_H_NO_NEXT
            fun yAfterOptionalStrip(originalY: Float): Float =
                if (hasNextEntry || originalY < NO_NEXT_CUT_BOTTOM) originalY else originalY - NO_NEXT_CUT_H

            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(REF_W / activeRefHeight)) {
                ReferenceImage(
                drawable = if (hasNextEntry) R.drawable.pa_ref_home_top else R.drawable.pa_ref_home_top_no_next,
                refHeight = activeRefHeight,
                modifier = Modifier.fillMaxSize(),
                hits = listOf(
                    RefHit(544f, 0f, 100f, 105f, onBack),
                    RefHit(20f, yAfterOptionalStrip(458f), 610f, 242f) { onOpenModule(AssistantModule.PRAYER) },
                    RefHit(322f, yAfterOptionalStrip(753f), 304f, 150f) { onOpenModule(AssistantModule.NOTES) },
                    RefHit(18f, yAfterOptionalStrip(753f), 295f, 150f) { onOpenModule(AssistantModule.TASKS) },
                    RefHit(322f, yAfterOptionalStrip(908f), 304f, 145f) { onOpenModule(AssistantModule.CALENDAR) },
                    RefHit(18f, yAfterOptionalStrip(908f), 295f, 145f) { onOpenModule(AssistantModule.MEETINGS) },
                    RefHit(322f, yAfterOptionalStrip(1057f), 304f, 145f) { onOpenModule(AssistantModule.CAMERA) },
                    RefHit(18f, yAfterOptionalStrip(1057f), 295f, 145f) { onOpenModule(AssistantModule.VOICE) },
                    RefHit(322f, yAfterOptionalStrip(1205f), 304f, 150f) { onOpenModule(AssistantModule.CALCULATOR) },
                    RefHit(18f, yAfterOptionalStrip(1205f), 295f, 150f) { onOpenModule(AssistantModule.MONEY) },
                ),
            )

                ClassicHomeButton(
                    onClick = onBack,
                    modifier = Modifier
                        .absoluteOffset(
                            x = maxWidth * (100f / REF_W),
                            y = maxHeight * (22f / activeRefHeight),
                        )
                        .size(
                            width = maxWidth * (108f / REF_W),
                            height = maxHeight * (50f / activeRefHeight),
                        ),
                )

                // Alpha91: same approved old artwork, now with correctly aligned live
                // content on top (filters, date/counters, next item and prayer status).
                ClassicCategoryStrip(
                    selectedCategory = selectedCategory,
                    onCategoryChange = onCategoryChange,
                    modifier = Modifier
                        .absoluteOffset(
                            x = maxWidth * (64f / REF_W),
                            y = maxHeight * (98f / activeRefHeight),
                        )
                        .size(
                            width = maxWidth * (516f / REF_W),
                            height = maxHeight * (62f / activeRefHeight),
                        ),
                )

                // Keep the original "ملخص اليوم" title and illustration, while replacing
                // the fixed date and the three fixed counters with live values.
                Box(
                    modifier = Modifier
                        .absoluteOffset(
                            x = maxWidth * (402f / REF_W),
                            y = maxHeight * (222f / activeRefHeight),
                        )
                        .size(
                            width = maxWidth * (196f / REF_W),
                            height = maxHeight * (36f / activeRefHeight),
                        )
                        .background(adaptSurface(Color(0xFFEAFBF5)).copy(alpha = .94f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE، d MMMM", Locale("ar"))),
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = adaptInk(Color(0xFF243247)),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }

                ClassicSummaryMetric(
                    x = maxWidth * (487f / REF_W),
                    y = maxHeight * (269f / activeRefHeight),
                    w = maxWidth * (114f / REF_W),
                    h = maxHeight * (92f / activeRefHeight),
                    count = summary.openTasks,
                    label = L("مهام"),
                    symbol = "✓",
                    background = adaptSurface(Color(0xFFDFF8E9)),
                    symbolColor = Color(0xFF16A879),
                )
                ClassicSummaryMetric(
                    x = maxWidth * (370f / REF_W),
                    y = maxHeight * (269f / activeRefHeight),
                    w = maxWidth * (110f / REF_W),
                    h = maxHeight * (92f / activeRefHeight),
                    count = summary.appointmentsToday,
                    label = L("مواعيد"),
                    symbol = "▣",
                    background = adaptSurface(Color(0xFFE4F1FF)),
                    symbolColor = Color(0xFF2C7BE5),
                )
                ClassicSummaryMetric(
                    x = maxWidth * (253f / REF_W),
                    y = maxHeight * (269f / activeRefHeight),
                    w = maxWidth * (110f / REF_W),
                    h = maxHeight * (92f / activeRefHeight),
                    count = summary.pendingFamilyRequests + summary.activeMedicationReminders,
                    label = L("تنبيهات"),
                    symbol = "●",
                    background = adaptSurface(Color(0xFFFFE6E8)),
                    symbolColor = Color(0xFFE93046),
                )

                if (hasNextEntry) {
                    ClassicNextItemStrip(
                        entry = nextEntry,
                        onUpdateEntry = onUpdateEntry,
                        modifier = Modifier
                            .absoluteOffset(
                                x = maxWidth * (34f / REF_W),
                                y = maxHeight * (370f / activeRefHeight),
                            )
                            .size(
                                width = maxWidth * (576f / REF_W),
                                height = maxHeight * (82f / activeRefHeight),
                            ),
                    )
                }

                PrayerHomeLiveOverlay(
                    settings = prayerSettings,
                    modifier = Modifier
                        .absoluteOffset(
                            x = maxWidth * (325f / REF_W),
                            y = maxHeight * (yAfterOptionalStrip(526f) / activeRefHeight),
                        )
                        .size(
                            width = maxWidth * (222f / REF_W),
                            height = maxHeight * (154f / activeRefHeight),
                        ),
                )
            }
        }

        // Page 7 in the approved guide is the continuation after scrolling.
        ReferenceImage(
            drawable = R.drawable.pa_ref_home_more,
            refWidth = 644f,
            refHeight = 1288f,
            modifier = Modifier.fillMaxWidth().aspectRatio(644f / 1288f),
            hits = listOf(
                RefHit(18f, 76f, 298f, 230f) { onOpenModule(AssistantModule.MARKETS) },
                RefHit(326f, 76f, 300f, 230f) { onOpenModule(AssistantModule.BILLS) },
                RefHit(18f, 321f, 298f, 228f) { onOpenModule(AssistantModule.COMPARE) },
                RefHit(326f, 321f, 300f, 228f) { onOpenModule(AssistantModule.SHOPPING) },
                RefHit(18f, 565f, 298f, 235f) { onOpenModule(AssistantModule.FAMILY_REQUESTS) },
                RefHit(326f, 565f, 300f, 235f) { onOpenModule(AssistantModule.DOCUMENTS) },
                RefHit(18f, 814f, 298f, 240f) { onOpenModule(AssistantModule.HEALTH) },
                RefHit(326f, 814f, 300f, 235f, onQuickAdd),
                RefHit(326f, 1100f, 300f, 160f, onBack),
            ),
        )
    }
}

@Composable
private fun ClassicHomeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Row(
            modifier = modifier
                .background(adaptSurface(Color(0xFFFFFFFF)), RoundedCornerShape(18.dp))
                .border(1.dp, adaptSurface(Color(0xFFE7EAF0)), RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = L("الرئيسية"),
                tint = adaptInk(Color(0xFF0B1D3A)),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = L("الرئيسية"),
                color = adaptInk(Color(0xFF0B1D3A)),
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ClassicCategoryStrip(
    selectedCategory: AssistantCategory,
    onCategoryChange: (AssistantCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(adaptSurface(Color(0xFFFCFCFC)), RoundedCornerShape(31.dp))
            .border(1.dp, adaptSurface(Color(0xFFE8E8E8)), RoundedCornerShape(31.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            AssistantCategory.WORK to L("عمل"),
            AssistantCategory.PERSONAL to L("شخصي"),
            AssistantCategory.ALL to L("الكل"),
        ).forEach { (category, label) ->
            val active = selectedCategory == category
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(
                        if (active) Color(0xFFFF413D) else Color.White,
                        RoundedCornerShape(27.dp),
                    )
                    .clickable { onCategoryChange(category) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (active) Color.White else adaptInk(Color(0xFF0B132B)),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ClassicSummaryMetric(
    x: androidx.compose.ui.unit.Dp,
    y: androidx.compose.ui.unit.Dp,
    w: androidx.compose.ui.unit.Dp,
    h: androidx.compose.ui.unit.Dp,
    count: Int,
    label: String,
    symbol: String,
    background: Color,
    symbolColor: Color,
) {
    Column(
        modifier = Modifier
            .absoluteOffset(x = x, y = y)
            .size(width = w, height = h)
            .background(background.copy(alpha = .98f), RoundedCornerShape(16.dp))
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = symbol,
            color = symbolColor,
            fontSize = 15.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
        Text(
            text = count.toString(),
            color = adaptInk(Color(0xFF0F172A)),
            fontSize = 19.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
        Text(
            text = label,
            color = adaptInk(Color(0xFF334155)),
            fontSize = 10.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ClassicNextItemStrip(
    entry: AssistantEntry?,
    onUpdateEntry: (AssistantEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(adaptSurface(Color.White), RoundedCornerShape(16.dp))
            .border(1.dp, adaptSurface(Color(0xFFF0F1F3)), RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        if (entry == null) {
            return@Box
        }

        val dueText = entry.dueAt?.let {
            Instant.ofEpochMilli(it)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("h:mm a", Locale("ar")))
        }.orEmpty()

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${entry.title}  —  $dueText",
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    color = adaptInk(Color(0xFF111827)),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .size(width = 70.dp, height = 36.dp)
                        .background(adaptSurface(Color(0xFFF4F4F6)), RoundedCornerShape(10.dp))
                        .clickable {
                            val due = entry.dueAt ?: return@clickable
                            onUpdateEntry(entry.copy(dueAt = due + 30 * 60 * 1000L))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(L("تأجيل"), color = adaptInk(Color(0xFF111827)), fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(width = 68.dp, height = 36.dp)
                        .background(Color(0xFFFF4545), RoundedCornerShape(10.dp))
                        .clickable { onUpdateEntry(entry.copy(completed = true)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(L("تم"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun PrayerHomeLiveOverlay(settings: PrayerSettings, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now()
            delay(1_000)
        }
    }
    val zone = remember(settings.zoneId) { runCatching { java.time.ZoneId.of(settings.zoneId) }.getOrDefault(now.zone) }
    val localNow = now.withZoneSameInstant(zone)
    val occurrence = remember(localNow.toLocalDate(), localNow.hour, localNow.minute, settings) {
        nextPrayerOccurrence(localNow, settings, zone)
    }
    val seconds = Duration.between(localNow, occurrence.second).seconds.coerceAtLeast(0)
    val justStarted = seconds == 0L || (localNow.isAfter(occurrence.second) && Duration.between(occurrence.second, localNow).seconds < 45)
    val hh = seconds / 3600
    val mm = (seconds % 3600) / 60
    val ss = seconds % 60
    val countdown = "%02d:%02d:%02d".format(hh, mm, ss)
    val prayerName = prayerArabicName(occurrence.first)
    val timeText = occurrence.second.format(DateTimeFormatter.ofPattern("h:mm a", Locale("ar")))

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = modifier
                .background(adaptSurface(Color(0xFFFFF5EC)).copy(alpha = .99f), RoundedCornerShape(15.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (justStarted) "حان الآن موعد صلاة" else "الصلاة القادمة",
                fontSize = 12.sp,
                color = adaptInk(Color(0xFF334155)),
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(prayerName, fontSize = 22.sp, color = adaptInk(Color(0xFF111827)), fontWeight = FontWeight.Black)
                Spacer(Modifier.width(7.dp))
                Text(timeText, fontSize = 18.sp, color = adaptInk(Color(0xFFE53935)), fontWeight = FontWeight.Black)
            }
            if (!justStarted) {
                Text(
                    "متبقي $countdown",
                    fontSize = 11.sp,
                    color = adaptInk(Color(0xFFE53935)),
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .background(adaptSurface(Color(0xFFD8F8E8)), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (occurrence.first in settings.enabledAlerts) "🔔 التنبيه مفعل" else "التنبيه غير مفعل",
                    fontSize = 10.sp,
                    color = if (occurrence.first in settings.enabledAlerts) adaptInk(Color(0xFF087F5B)) else adaptInk(Color(0xFF64748B)),
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}

private fun nextPrayerOccurrence(
    now: ZonedDateTime,
    settings: PrayerSettings,
    zone: java.time.ZoneId,
): Pair<PrayerName, ZonedDateTime> {
    val ordered = listOf(PrayerName.FAJR, PrayerName.DHUHR, PrayerName.ASR, PrayerName.MAGHRIB, PrayerName.ISHA)
    fun timesFor(date: LocalDate): Map<PrayerName, java.time.LocalTime> {
        val offset = zone.rules.getOffset(date.atStartOfDay(zone).toInstant()).totalSeconds / 3600.0
        return PrayerTimesCalculator.calculate(date, settings.latitude, settings.longitude, offset, settings.method, settings.asrSchool, settings.adjustmentsMinutes)
    }
    val today = now.toLocalDate()
    val todayTimes = timesFor(today)
    for (prayer in ordered) {
        val t = todayTimes[prayer] ?: continue
        val occurrence = ZonedDateTime.of(today, t, zone)
        if (!now.isAfter(occurrence.plusSeconds(44))) return prayer to occurrence
    }
    val tomorrow = today.plusDays(1)
    val fajr = timesFor(tomorrow)[PrayerName.FAJR] ?: java.time.LocalTime.of(5, 0)
    return PrayerName.FAJR to ZonedDateTime.of(tomorrow, fajr, zone)
}

private fun prayerArabicName(prayer: PrayerName): String = when (prayer) {
    PrayerName.FAJR -> "الفجر"
    PrayerName.DHUHR -> "الظهر"
    PrayerName.ASR -> "العصر"
    PrayerName.MAGHRIB -> "المغرب"
    PrayerName.ISHA -> "العشاء"
    PrayerName.SUNRISE -> "الشروق"
}


private data class CalculatorHistoryRow(val expression: String, val result: String)

private fun calcFormat(value: Double): String = DecimalFormat("#,##0.########").format(value)

/**
 * Alpha73 responsive calculator.
 * The calculator is now drawn with Compose instead of stretching the full-page raster reference.
 * This keeps the geometry stable across screen sizes, keeps western digits readable, and exposes
 * the currency converter as an explicit tab rather than a hidden hit area.
 */
@Composable
internal fun OperationalReferenceCalculator(
    onBack: () -> Unit,
    onOpenCurrency: () -> Unit,
) {
    val context = LocalContext.current
    var expression by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("0") }
    var history by remember { mutableStateOf<List<CalculatorHistoryRow>>(emptyList()) }

    fun visual(text: String): String = text
        .replace("*", "×")
        .replace("/", "÷")
        .replace("-", "−")

    fun recomputePreview() {
        if (expression.isBlank()) {
            result = "0"
            return
        }
        runCatching { AssistantDomain.evaluateExpression(expression) }
            .onSuccess { result = calcFormat(it) }
    }

    fun appendToken(token: String) {
        if (expression.length >= 120) return
        val operators = setOf('+', '-', '*', '/')
        if (token.length == 1 && token[0] in operators && expression.lastOrNull()?.let { it in operators } == true) {
            expression = expression.dropLast(1) + token
        } else {
            expression += token
        }
        recomputePreview()
    }

    fun clearAll() {
        expression = ""
        result = "0"
    }

    // Alpha98 calculator safe patch: separate backspace from clear-all.
    fun removeLastCharacter() {
        if (expression.isEmpty()) return
        expression = expression.dropLast(1)
        recomputePreview()
    }

    fun percent() {
        if (expression.isNotBlank() && expression.lastOrNull()?.isDigit() == true) {
            expression += "/100"
            recomputePreview()
        }
    }

    fun commitResult() {
        if (expression.isBlank()) return
        runCatching { AssistantDomain.evaluateExpression(expression) }
            .onSuccess {
                val formatted = calcFormat(it)
                result = formatted
                history = (listOf(CalculatorHistoryRow(expression, formatted)) + history).take(3)
            }
            .onFailure { Toast.makeText(context, "عملية غير صحيحة", Toast.LENGTH_SHORT).show() }
    }

    fun copyResult() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AB File Pro calculator result", result))
        Toast.makeText(context, "تم نسخ النتيجة", Toast.LENGTH_SHORT).show()
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            Modifier
                .fillMaxSize()
                .background(adaptSurface(Color(0xFFF8FAFD)))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            // Header: responsive instead of scaling the old page image.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .background(adaptSurface(Color(0xFFECE4FF)), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+ −\n× ÷", color = adaptInk(Color(0xFF5540B8)), fontWeight = FontWeight.Black, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(L("الآلة الحاسبة"), color = adaptInk(Color(0xFF07122F)), fontWeight = FontWeight.Black, fontSize = 28.sp)
                    Text(L("حاسبة سريعة مع سجل ونسخ النتيجة"), color = adaptInk(Color(0xFF7A8493)), fontSize = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(52.dp)
                        .background(adaptSurface(Color(0xFFF0E8FF)), RoundedCornerShape(26.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Text("←", color = adaptInk(Color(0xFF111827)), fontSize = 30.sp, fontWeight = FontWeight.Bold) }
            }

            // Explicit mode switch. Currency is no longer hidden behind the illustration.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(adaptSurface(Color.White), RoundedCornerShape(22.dp))
                    .border(1.dp, adaptSurface(Color(0xFFE3E8EF)), RoundedCornerShape(22.dp))
                    .padding(4.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(adaptSurface(Color(0xFFE9DDF8)), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(L("حاسبة"), color = adaptInk(Color(0xFF101827)), fontWeight = FontWeight.Black, fontSize = 18.sp) }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(adaptSurface(Color(0xFFF9FAFB)), RoundedCornerShape(18.dp))
                        .clickable(onClick = onOpenCurrency),
                    contentAlignment = Alignment.Center,
                ) { Text(L("تحويل العملات"), color = adaptInk(Color(0xFF334155)), fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            }

            // Display card.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(adaptSurface(Color.White), RoundedCornerShape(26.dp))
                    .border(1.dp, adaptSurface(Color(0xFFDDE7F0)), RoundedCornerShape(26.dp))
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    visual(expression.ifBlank { "0" }),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    color = adaptInk(Color(0xFF657080)),
                    fontSize = if (expression.length > 18) 17.sp else 22.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Color(0xFFE8EEF5))
                Spacer(Modifier.height(10.dp))
                Text(
                    result,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    color = adaptInk(Color(0xFF07122F)),
                    fontSize = when {
                        result.length > 15 -> 28.sp
                        result.length > 10 -> 36.sp
                        else -> 48.sp
                    },
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
            }

            // Controls: reference layout, but responsive and readable on every width.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                CalculatorKey("مسح", Modifier.weight(1f), Color(0xFFFFECEF), Color(0xFFC91D2E), ::clearAll)
                CalculatorKey("⌫", Modifier.weight(1f), Color(0xFFFFF4E4), Color(0xFF9A500A), ::removeLastCharacter)
                CalculatorKey("%", Modifier.weight(1f), Color(0xFFF2EAFE), Color(0xFF171827), ::percent)
                CalculatorKey("÷", Modifier.weight(1f), Color(0xFFEAF3FB), Color(0xFF0F172A)) { appendToken("/") }
                CalculatorKey("×", Modifier.weight(1f), Color(0xFFEAF3FB), Color(0xFF0F172A)) { appendToken("*") }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(3f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    listOf(listOf("7","8","9"), listOf("4","5","6"), listOf("1","2","3")).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            row.forEach { n -> CalculatorKey(n, Modifier.weight(1f), Color.White, Color(0xFF0F172A)) { appendToken(n) } }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        CalculatorKey("0", Modifier.weight(2f), Color.White, Color(0xFF0F172A)) { appendToken("0") }
                        CalculatorKey(".", Modifier.weight(1f), Color.White, Color(0xFF0F172A)) { appendToken(".") }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    CalculatorKey("−", Modifier.fillMaxWidth(), Color(0xFFEAF3FB), Color(0xFF0F172A)) { appendToken("-") }
                    CalculatorKey("+", Modifier.fillMaxWidth(), Color(0xFFEAF3FB), Color(0xFF0F172A)) { appendToken("+") }
                    CalculatorKey("=", Modifier.fillMaxWidth().height(152.dp), Color(0xFFFF343E), Color.White, ::commitResult)
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(adaptSurface(Color(0xFFF0FFF8)), RoundedCornerShape(24.dp))
                    .border(1.dp, adaptSurface(Color(0xFFDDF5E8)), RoundedCornerShape(24.dp))
                    .padding(16.dp),
            ) {
                Text(L("السجل"), color = adaptInk(Color(0xFF07122F)), fontWeight = FontWeight.Black, fontSize = 21.sp)
                Spacer(Modifier.height(8.dp))
                if (history.isEmpty()) {
                    Text(L("لا توجد عمليات بعد"), color = adaptInk(Color(0xFF7C8796)), fontSize = 14.sp)
                } else {
                    history.forEachIndexed { index, row ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.result, color = adaptInk(Color(0xFF07122F)), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.weight(1f))
                            Text(visual(row.expression), color = adaptInk(Color(0xFF64748B)), fontSize = 15.sp, maxLines = 1)
                        }
                        if (index != history.lastIndex) HorizontalDivider(color = Color(0xFFE5F0EA), modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                CalculatorAction("مسح السجل", Color(0xFFFFF1F2), Color(0xFFB91C1C), Modifier.weight(1f)) { history = emptyList() }
                CalculatorAction("نسخ النتيجة", Color(0xFFECFDF5), Color(0xFF059669), Modifier.weight(1f), ::copyResult)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CalculatorKey(
    label: String,
    modifier: Modifier,
    background: Color,
    foreground: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(66.dp)
            .background(background, RoundedCornerShape(20.dp))
            .border(1.dp, adaptSurface(Color(0xFFE2E8F0)), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = foreground, fontSize = if (label.length > 1) 17.sp else 28.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun CalculatorAction(
    label: String,
    background: Color,
    foreground: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(58.dp)
            .background(background, RoundedCornerShape(20.dp))
            .border(1.dp, foreground.copy(alpha = .14f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = foreground, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}

@Composable
internal fun AssistantReferenceModuleScreen(
    module: AssistantModule,
    entries: List<AssistantEntry>,
    defaultCategory: AssistantCategory,
    onBack: () -> Unit,
    onOpenScanner: () -> Unit,
    onAdd: (String, String, AssistantCategory, Long?, Double?, String) -> Unit,
    onOpenLegacy: () -> Unit,
) {
    var showEditor by remember(module) { mutableStateOf(false) }

    val editorSupported = module in setOf(
        AssistantModule.NOTES,
        AssistantModule.TASKS,
        AssistantModule.CALENDAR,
        AssistantModule.MEETINGS,
        AssistantModule.MONEY,
        AssistantModule.SHOPPING,
        AssistantModule.FAMILY_REQUESTS,
        AssistantModule.DOCUMENTS,
        AssistantModule.HEALTH,
    )
    val showDue = module in setOf(
        AssistantModule.TASKS,
        AssistantModule.CALENDAR,
        AssistantModule.MEETINGS,
        AssistantModule.DOCUMENTS,
        AssistantModule.HEALTH,
    )

    if (showEditor && editorSupported) {
        EntryEditorDialog(
            module = module,
            defaultCategory = defaultCategory,
            showDue = showDue,
            showAmount = module == AssistantModule.MONEY,
            onDismiss = { showEditor = false },
            onSave = { title, details, category, dueAt, amount, secondary ->
                onAdd(title, details, category, dueAt, amount, secondary)
                showEditor = false
            },
        )
    }

    val editAction = { if (editorSupported) showEditor = true else onOpenLegacy() }
    val backHit = RefHit(540f, 0f, 104f, 105f, onBack)

    val drawable = when (module) {
        AssistantModule.PRAYER -> R.drawable.pa_ref_prayer
        AssistantModule.NOTES -> R.drawable.pa_ref_notes
        AssistantModule.TASKS -> R.drawable.pa_ref_tasks
        AssistantModule.CALENDAR -> R.drawable.pa_ref_calendar
        AssistantModule.MEETINGS -> R.drawable.pa_ref_meetings
        AssistantModule.VOICE -> R.drawable.pa_ref_voice
        AssistantModule.CAMERA -> R.drawable.pa_ref_camera
        AssistantModule.CALCULATOR -> R.drawable.pa_ref_calculator
        AssistantModule.MONEY -> R.drawable.pa_ref_money
        AssistantModule.MARKETS -> R.drawable.pa_ref_currency
        AssistantModule.BILLS -> R.drawable.pa_ref_bills
        AssistantModule.COMPARE -> R.drawable.pa_ref_prices
        AssistantModule.SHOPPING -> R.drawable.pa_ref_shopping
        AssistantModule.FAMILY_REQUESTS -> R.drawable.pa_ref_family
        AssistantModule.DOCUMENTS -> R.drawable.pa_ref_documents
        AssistantModule.HEALTH -> R.drawable.pa_ref_health
    }

    val actionHits = when (module) {
        AssistantModule.PRAYER -> listOf(
            RefHit(20f, 1198f, 604f, 125f, onOpenLegacy),
            RefHit(20f, 95f, 604f, 105f, onOpenLegacy),
        )
        AssistantModule.NOTES -> listOf(RefHit(18f, 1240f, 272f, 125f, editAction))
        AssistantModule.TASKS -> listOf(
            RefHit(318f, 1160f, 307f, 122f, editAction),
            RefHit(18f, 1160f, 292f, 122f, editAction),
        )
        AssistantModule.CALENDAR -> listOf(RefHit(95f, 1220f, 500f, 118f, editAction))
        AssistantModule.MEETINGS -> listOf(
            RefHit(65f, 1230f, 520f, 120f, editAction),
            RefHit(58f, 330f, 530f, 90f, onOpenLegacy),
        )
        AssistantModule.VOICE -> listOf(
            RefHit(70f, 315f, 500f, 100f, onOpenLegacy),
            RefHit(70f, 1180f, 500f, 130f, onOpenLegacy),
        )
        AssistantModule.CAMERA -> listOf(
            RefHit(230f, 1110f, 190f, 180f, onOpenScanner),
            RefHit(20f, 1110f, 165f, 180f, onOpenLegacy),
        )
        AssistantModule.CALCULATOR -> listOf(RefHit(20f, 250f, 604f, 850f, onOpenLegacy))
        AssistantModule.MONEY -> listOf(RefHit(320f, 1210f, 305f, 125f, editAction))
        AssistantModule.MARKETS -> listOf(
            RefHit(30f, 1210f, 584f, 115f, onOpenLegacy),
            RefHit(20f, 70f, 90f, 80f, onOpenLegacy),
        )
        AssistantModule.BILLS -> listOf(
            RefHit(25f, 1210f, 590f, 115f, onOpenLegacy),
            RefHit(20f, 250f, 604f, 520f, onOpenLegacy),
        )
        AssistantModule.COMPARE -> listOf(
            RefHit(25f, 80f, 590f, 125f, onOpenLegacy),
            RefHit(25f, 1210f, 590f, 115f, onOpenLegacy),
        )
        AssistantModule.SHOPPING -> listOf(
            RefHit(20f, 220f, 604f, 155f, editAction),
            RefHit(20f, 1200f, 604f, 120f, onOpenLegacy),
        )
        AssistantModule.FAMILY_REQUESTS -> listOf(
            RefHit(35f, 1210f, 575f, 118f, editAction),
            RefHit(20f, 250f, 604f, 820f, onOpenLegacy),
        )
        AssistantModule.DOCUMENTS -> listOf(
            RefHit(318f, 1210f, 307f, 120f, editAction),
            RefHit(18f, 1210f, 292f, 120f, onOpenLegacy),
        )
        AssistantModule.HEALTH -> listOf(
            RefHit(320f, 1165f, 305f, 120f, editAction),
            RefHit(18f, 1165f, 292f, 120f, editAction),
        )
    }

    ReferenceImage(
        drawable = drawable,
        modifier = Modifier.fillMaxSize(),
        hits = listOf(backHit) + actionHits,
    )
}


@Composable
private fun TodayEntryBar(onClick: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .background(Color(0xFF173B65), RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = L("يومي") + " — " + L("مهامك ومواعيدك وأدويتك النهارده"),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
        }
    }
}
