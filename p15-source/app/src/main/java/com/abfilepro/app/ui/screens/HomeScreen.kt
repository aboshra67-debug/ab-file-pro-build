package com.abfilepro.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abfilepro.app.R
import com.abfilepro.app.core.DeviceStorageManager
import com.abfilepro.app.core.WorkspaceShortcutManager
import com.abfilepro.app.core.WorkspaceStorageManager
import com.abfilepro.app.ui.i18n.AppLanguage
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.abfilepro.app.ui.screens.assistant.PersonalAssistantScreen
import kotlinx.coroutines.delay
import java.io.File

private const val INTRO_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
private const val PREFS_NAME = "ab_file_pro"
private const val PREF_FIRST_LAUNCH = "first_launch_at"
private const val PREF_INTRO_DISABLED = "intro_disabled"
private const val PREF_SHOW_AI_HELP = "show_ai_help"

private object HomeIntroSessionGate {
    var shownThisProcess: Boolean = false
}

private data class DashboardTool(
    val route: String?,
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val accent: Color,
    val id: String,
    val comingSoon: Boolean = false
)

private enum class HomeSheet { HELP, AI }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNavigate: (String) -> Unit, onOpenPdf: (File) -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val prefs = remember(context) { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var languageDialog by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf<HomeSheet?>(null) }
    var showPersonalAssistant by remember { mutableStateOf(false) }

    val now = remember { System.currentTimeMillis() }
    val firstLaunchAt = remember {
        prefs.getLong(PREF_FIRST_LAUNCH, 0L).let { saved ->
            if (saved > 0L) saved else now.also { prefs.edit().putLong(PREF_FIRST_LAUNCH, it).apply() }
        }
    }
    var showIntro by remember {
        mutableStateOf(
            !HomeIntroSessionGate.shownThisProcess &&
                !prefs.getBoolean(PREF_INTRO_DISABLED, false) &&
                (now - firstLaunchAt) < INTRO_WINDOW_MS
        )
    }
    var introStep by remember { mutableIntStateOf(0) }
    val showRobot = prefs.getBoolean(PREF_SHOW_AI_HELP, true)

    // Alpha37: keep the external My Files shortcut in sync from the first app run.
    // Existing pinned shortcuts are updated in place; no duplicate is created.
    LaunchedEffect(Unit) {
        val workspaceName = if (DeviceStorageManager.hasAllFilesAccess()) {
            runCatching { WorkspaceStorageManager.ensureDefaultWorkspace(context)?.name }.getOrNull()
        } else null
        WorkspaceShortcutManager.requestPinOnce(context, workspaceName ?: "AB File Pro")
    }

    LaunchedEffect(showIntro) {
        if (showIntro) {
            HomeIntroSessionGate.shownThisProcess = true
            introStep = 0
            repeat(4) {
                delay(650)
                introStep = (introStep + 1).coerceAtMost(3)
            }
            delay(450)
            showIntro = false
        }
    }

    val tools = remember(language) {
        listOf(
            DashboardTool(
                "files",
                context.uiText(language, "home_file_manager"),
                context.uiText(language, "home_file_manager_desc"),
                R.drawable.ic_ab_files,
                Color(0xFF2563EB),
                "files"
            ),
            DashboardTool(
                "scan",
                context.uiText(language, "home_scanner"),
                context.uiText(language, "home_scanner_desc"),
                R.drawable.ic_ab_scanner,
                Color(0xFFE53935),
                "scanner"
            ),
            DashboardTool(
                "convert",
                context.uiText(language, "home_convert"),
                context.uiText(language, "home_convert_desc"),
                R.drawable.ic_ab_convert,
                Color(0xFF7C3AED),
                "convert"
            ),
            DashboardTool(
                null,
                context.uiText(language, "home_personal_assistant"),
                context.uiText(language, "home_personal_assistant_desc"),
                R.drawable.ic_ab_assistant,
                Color(0xFF0F9D72),
                "assistant"
            )
        )
    }

    if (languageDialog) {
        AlertDialog(
            onDismissRequest = { languageDialog = false },
            icon = { Icon(Icons.Default.Language, null) },
            title = { Text(context.uiText(language, "home_choose_language"), fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppLanguage.entries.forEach { option ->
                        ListItem(
                            headlineContent = { Text(context.uiText(language, option.nativeNameKey), fontWeight = FontWeight.SemiBold) },
                            leadingContent = { RadioButton(selected = language == option, onClick = null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    prefs.edit().putString("language", option.code).apply()
                                    languageDialog = false
                                    context.findActivityCompat()?.recreate()
                                }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { languageDialog = false }) {
                    Text(context.uiText(language, "home_close"))
                }
            }
        )
    }

    activeSheet?.let { sheet ->
        ModalBottomSheet(onDismissRequest = { activeSheet = null }) {
            HomeAssistantSheet(sheet = sheet)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (language == AppLanguage.ARABIC && showRobot) {
            ReferenceArtworkHome(
                onSettings = { onNavigate("settings") },
                onLanguage = { languageDialog = true },
                onFiles = { onNavigate("files") },
                onScanner = { onNavigate("scan") },
                onConvert = { onNavigate("convert") },
                onAssistant = { showPersonalAssistant = true },
                onAi = { activeSheet = HomeSheet.AI },
                onHelp = { activeSheet = HomeSheet.HELP }
            )
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    HomeTopBar(
                        onLanguage = { languageDialog = true },
                        onSettings = { onNavigate("settings") }
                    )
                },
                floatingActionButton = {
                    if (showRobot) {
                        SmallFloatingActionButton(
                            onClick = { activeSheet = HomeSheet.HELP },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape
                        ) {
                            Icon(painterResource(R.drawable.ic_ab_help_robot), context.uiText(language, "home_ai_help"), modifier = Modifier.size(19.dp))
                        }
                    }
                }
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 18.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Spacer(Modifier.height(4.dp))
                    HeroBanner()

                    Text(
                        context.uiText(language, "home_main_tools"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    tools.chunked(2).forEach { rowTools ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowTools.forEach { tool ->
                                Box(Modifier.weight(1f)) {
                                    PremiumModuleCard(
                                        tool = tool,
                                        onClick = {
                                            when {
                                                tool.route != null -> onNavigate(tool.route)
                                                tool.id == "assistant" -> showPersonalAssistant = true
                                            }
                                        }
                                    )
                                }
                            }
                            if (rowTools.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }

                    PremiumAiBanner(onClick = { activeSheet = HomeSheet.AI })

                    Text(
                        context.uiText(language, "home_footer_note"),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 18.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showIntro,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            PremiumIntroOverlay(
                step = introStep,
                onSkip = { showIntro = false }
            )
        }

        if (showPersonalAssistant) {
            PersonalAssistantScreen(
                onBack = { showPersonalAssistant = false },
                onOpenScanner = {
                    showPersonalAssistant = false
                    onNavigate("scan")
                }
            )
        }
    }

    // Signature kept for navigation compatibility; opening PDFs remains owned by existing module screens.
    @Suppress("UNUSED_VARIABLE") val keepOpenPdfContract = onOpenPdf
}

/**
 * Pixel-faithful Arabic home shell based on the approved visual reference.
 *
 * The artwork is a single compressed drawable to keep the visual result stable and fast to
 * render. Transparent semantic hotspots sit over the visible controls, so navigation remains
 * real Compose behavior rather than being baked into the bitmap. English/French continue using
 * the live localized Compose home above, preserving full three-language behavior.
 */
@Composable
private fun ReferenceArtworkHome(
    onSettings: () -> Unit,
    onLanguage: () -> Unit,
    onFiles: () -> Unit,
    onScanner: () -> Unit,
    onConvert: () -> Unit,
    onAssistant: () -> Unit,
    onAi: () -> Unit,
    onHelp: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFFCFBFA))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val artworkWidth = maxWidth
            val artworkHeight = maxHeight
            Box(Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(R.drawable.ab_file_pro_home_ar),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(Modifier.fillMaxSize()) {
                    ReferenceHotspot(
                        label = "الإعدادات",
                        onClick = onSettings,
                        modifier = Modifier.referenceBounds(artworkWidth, artworkHeight, .035f, .024f, .115f, .073f)
                    )
                    ReferenceHotspot(
                        label = "اختيار اللغة",
                        onClick = onLanguage,
                        modifier = Modifier.referenceBounds(artworkWidth, artworkHeight, .835f, .024f, .125f, .075f)
                    )
    
                    ReferenceHotspot(
                        label = "ابدأ الآن",
                        onClick = onFiles,
                        modifier = Modifier.referenceBounds(artworkWidth, artworkHeight, .115f, .258f, .285f, .055f)
                    )
                    ReferenceHotspot(
                        label = "Scanner المسح الضوئي",
                        onClick = onScanner,
                        modifier = Modifier.referenceBounds(artworkWidth, artworkHeight, .025f, .375f, .465f, .210f)
                    )
                    ReferenceHotspot(
                        label = "مدير الملفات",
                        onClick = onFiles,
                        modifier = Modifier.referenceBounds(artworkWidth, artworkHeight, .505f, .375f, .470f, .210f)
                    )
                    ReferenceHotspot(
                        label = "تحويل الملفات",
                        onClick = onConvert,
                        modifier = Modifier.referenceBounds(artworkWidth, artworkHeight, .025f, .585f, .465f, .205f)
                    )
                    ReferenceHotspot(
                        label = "المساعد الشخصي",
                        onClick = onAssistant,
                        modifier = Modifier.referenceBounds(artworkWidth, artworkHeight, .505f, .585f, .470f, .205f)
                    )
    
                    ReferenceHotspot(
                        label = "AI قريبًا",
                        onClick = onAi,
                        modifier = Modifier.referenceBounds(artworkWidth, artworkHeight, .025f, .795f, .950f, .145f)
                    )
                    ReferenceHotspot(
                        label = "المساعد - كيف أساعدك اليوم؟",
                        onClick = onHelp,
                        modifier = Modifier.referenceBounds(artworkWidth, artworkHeight, .765f, .785f, .205f, .170f)
                    )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceHotspot(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    )
}

private fun Modifier.referenceBounds(
    canvasWidth: androidx.compose.ui.unit.Dp,
    canvasHeight: androidx.compose.ui.unit.Dp,
    x: Float,
    y: Float,
    width: Float,
    height: Float
): Modifier = this
    .absoluteOffset(x = canvasWidth * x, y = canvasHeight * y)
    .size(width = canvasWidth * width, height = canvasHeight * height)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(onLanguage: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildAnnotatedString {
                            append("AB File ")
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Pro") }
                        },
                        fontWeight = FontWeight.Black,
                        fontSize = 21.sp
                    )
                }
                Text(
                    context.uiText(language, "home_tagline_short"),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f)
                )
            }
        },
        navigationIcon = {
            FilledTonalIconButton(onClick = onLanguage, modifier = Modifier.padding(start = 6.dp)) {
                Icon(painterResource(R.drawable.ic_ab_language), context.uiText(language, "home_choose_language"), modifier = Modifier.size(23.dp))
            }
        },
        actions = {
            FilledTonalIconButton(onClick = onSettings, modifier = Modifier.padding(end = 6.dp)) {
                Icon(painterResource(R.drawable.ic_ab_settings), context.uiText(language, "b2_home_settings"), modifier = Modifier.size(23.dp))
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
private fun HeroBanner() {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.Transparent,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = .78f),
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(Modifier.fillMaxWidth(0.78f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    context.uiText(language, "home_hero_title"),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 27.sp
                )
                Text(
                    context.uiText(language, "home_hero_desc"),
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .86f),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).size(62.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .14f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = .18f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PremiumModuleCard(tool: DashboardTool, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(164.dp)
            .clickable(enabled = !tool.comingSoon, onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
    ) {
        Column(
            Modifier.fillMaxSize().padding(15.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(50.dp),
                    shape = RoundedCornerShape(17.dp),
                    color = tool.accent.copy(alpha = .12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(tool.iconRes), null, tint = tool.accent, modifier = Modifier.size(29.dp))
                    }
                }
                Icon(
                    Icons.Default.ArrowOutward,
                    null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .28f),
                    modifier = Modifier.size(19.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    tool.title,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    lineHeight = 18.sp,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    tool.subtitle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PremiumAiBanner(onClick: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .25f)),
        shadowElevation = 1.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_ab_ai), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(31.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(context.uiText(language, "home_ai_coming_soon"), fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(
                    context.uiText(language, "home_ai_desc"),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }
            Surface(shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)) {
                Text(
                    context.uiText(language, "home_soon_badge"),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun PremiumIntroOverlay(step: Int, onSkip: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val keys = listOf("intro_manage", "intro_scan", "intro_convert", "intro_organize")
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = .94f),
                        Color(0xFF08152F)
                    )
                )
            )
            .padding(24.dp)
    ) {
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.TopEnd)) {
            Text(context.uiText(language, "intro_skip"), color = Color.White.copy(alpha = .86f))
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(86.dp),
                shape = RoundedCornerShape(28.dp),
                color = Color.White.copy(alpha = .13f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Description, null, tint = Color.White, modifier = Modifier.size(44.dp))
                }
            }
            Row {
                Text("AB File ", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
                Text("Pro", color = Color(0xFFFFD54F), fontSize = 32.sp, fontWeight = FontWeight.Black)
            }
            Text(
                context.uiText(language, keys[step.coerceIn(keys.indices)]),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(
                context.uiText(language, "intro_desc"),
                color = Color.White.copy(alpha = .76f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keys.indices.forEach { index ->
                    Box(
                        Modifier
                            .width(if (index == step) 24.dp else 7.dp)
                            .height(7.dp)
                            .clip(CircleShape)
                            .background(if (index == step) Color(0xFFFFD54F) else Color.White.copy(alpha = .28f))
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeAssistantSheet(sheet: HomeSheet) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val titleKey: String
    val introKey: String
    val items: List<Pair<ImageVector, String>>

    when (sheet) {
        HomeSheet.HELP -> {
            titleKey = "home_help_title"
            introKey = "home_help_intro"
            items = listOf(
                Icons.Default.FolderOpen to "home_help_files",
                Icons.Default.DocumentScanner to "home_help_scanner",
                Icons.Default.SyncAlt to "home_help_convert",
                Icons.Default.EventNote to "home_help_assistant",
                Icons.Default.Psychology to "home_help_ai"
            )
        }
        HomeSheet.AI -> {
            titleKey = "home_ai_coming_soon"
            introKey = "ai_preview_intro"
            items = listOf(
                Icons.Default.AutoAwesome to "ai_preview_explain",
                Icons.Default.Search to "ai_preview_find",
                Icons.Default.Description to "ai_preview_documents"
            )
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(context.uiText(language, titleKey), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Text(
            context.uiText(language, introKey),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .64f),
            style = MaterialTheme.typography.bodyMedium
        )
        items.forEach { (icon, key) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(9.dp).size(20.dp))
                }
                Text(context.uiText(language, key), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, lineHeight = 20.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

private tailrec fun Context.findActivityCompat(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityCompat()
    else -> null
}
