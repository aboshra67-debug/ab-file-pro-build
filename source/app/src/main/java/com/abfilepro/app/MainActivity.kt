package com.abfilepro.app

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavType
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.AppUpdateManager
import com.abfilepro.app.core.RuntimeSafety
import com.abfilepro.app.core.WorkspaceShortcutRules
import com.abfilepro.app.ui.screens.*
import com.abfilepro.app.ui.theme.ABFileProTheme
import com.abfilepro.app.ui.i18n.AppLanguage
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) { RuntimeSafety.cleanupTemporaryFiles(applicationContext) }

        setContent {
            val prefs = remember { getSharedPreferences("ab_file_pro", MODE_PRIVATE) }
            var darkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", false)) }
            var appLanguage by remember { mutableStateOf(AppLanguage.fromCode(prefs.getString("language", "ar"))) }
            val layoutDirection = if (appLanguage == AppLanguage.ARABIC) LayoutDirection.Rtl else LayoutDirection.Ltr

            CompositionLocalProvider(
                LocalLayoutDirection provides layoutDirection,
                LocalAppLanguage provides appLanguage
            ) {
                ABFileProTheme(darkTheme = darkMode) {
                    // P21: status/navigation bar icons follow the app's dark mode (were always dark).
                    SideEffect {
                        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                        controller.isAppearanceLightStatusBars = !darkMode
                        controller.isAppearanceLightNavigationBars = !darkMode
                        @Suppress("DEPRECATION")
                        if (android.os.Build.VERSION.SDK_INT < 35) window.navigationBarColor = if (darkMode) 0xFF101418.toInt() else 0xFFFFFFFF.toInt()
                    }
                    val nav = rememberNavController()
                    val updateScope = rememberCoroutineScope()
                    var availableUpdate by remember { mutableStateOf<AppUpdateManager.UpdateInfo?>(null) }
                    var updateDownloading by remember { mutableStateOf(false) }
                    var updateError by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        // P16: the Play channel never contacts the APK update endpoint.
                        if (!AppUpdateManager.isEnabled) return@LaunchedEffect
                        delay(1200)
                        availableUpdate = runCatching {
                            AppUpdateManager.checkForUpdate(this@MainActivity)
                        }.getOrNull()
                    }
                    val openPdf: (File) -> Unit = { file ->
                        nav.navigate("viewer?path=${Uri.encode(file.absolutePath)}")
                    }

                    val shortcutOpenWorkspace = remember {
                        intent?.getBooleanExtra(WorkspaceShortcutRules.EXTRA_OPEN_WORKSPACE, false) == true
                    }
                    NavHost(navController = nav, startDestination = if (shortcutOpenWorkspace) "files" else "splash") {
                        composable("splash") {
                            SplashScreen {
                                nav.navigate("home") { popUpTo("splash") { inclusive = true } }
                            }
                        }
                        composable("home") { HomeScreen(onNavigate = { nav.navigate(it) }, onOpenPdf = openPdf) }
                        composable("smartscan") { SmartScanScreen(onBack = { nav.popBackStack() }, onNavigate = { nav.navigate(it) }) }
                        composable("magicflow") { MagicFlowScreen(onBack = { nav.popBackStack() }, onOpenPdf = openPdf) }
                        composable("scan") {
                            ScanScreen(
                                onBack = { nav.popBackStack() },
                                onOpenPdf = openPdf,
                                onOpenBarcode = { nav.navigate("barcode") },
                                onOpenSavedLocation = { file -> nav.navigate("files?dir=${Uri.encode(file.parentFile?.absolutePath.orEmpty())}") }
                            )
                        }
                        composable("cardscan") { CardScanScreen(onBack = { nav.popBackStack() }, onOpenPdf = openPdf) }
                        composable(
                            route = "businesscard?uri={uri}",
                            arguments = listOf(navArgument("uri") { type = NavType.StringType; defaultValue = "" })
                        ) { entry ->
                            val uriText = Uri.decode(entry.arguments?.getString("uri").orEmpty())
                            BusinessCardScreen(onBack = { nav.popBackStack() }, initialUriText = uriText)
                        }
                        composable(
                            route = "receipt?uri={uri}",
                            arguments = listOf(navArgument("uri") { type = NavType.StringType; defaultValue = "" })
                        ) { entry ->
                            val uriText = Uri.decode(entry.arguments?.getString("uri").orEmpty())
                            ReceiptInvoiceScreen(onBack = { nav.popBackStack() }, initialUriText = uriText)
                        }
                        composable("pdf") { PdfToolsScreen(onBack = { nav.popBackStack() }, onEditPdf = { uri -> nav.navigate("editor?uri=${Uri.encode(uri.toString())}") }) }
                        composable("convert") {
                            ConvertScreen(
                                onBack = { nav.popBackStack() },
                                onHome = { nav.navigate("home") { popUpTo("home") { inclusive = false }; launchSingleTop = true } },
                                onOpenFiles = { file -> nav.navigate("files?dir=${Uri.encode(file.parentFile?.absolutePath.orEmpty())}") },
                                onOpenPdf = openPdf
                            )
                        }
                        composable("ocr") { OcrScreen(onBack = { nav.popBackStack() }, onOpenPdf = openPdf) }
                        composable(
                            route = "files?dir={dir}",
                            arguments = listOf(navArgument("dir") { type = NavType.StringType; defaultValue = "" })
                        ) { entry ->
                            val initialDir = Uri.decode(entry.arguments?.getString("dir").orEmpty()).takeIf { it.isNotBlank() }
                            FilesScreen(
                                initialDirectoryPath = initialDir,
                                onBack = { nav.popBackStack() },
                                onOpenPdf = openPdf,
                                onEditPdf = { file ->
                                    val uri = FileUtils.contentUri(this@MainActivity, file)
                                    nav.navigate("editor?uri=${Uri.encode(uri.toString())}")
                                },
                                onCleanShare = { file ->
                                    val uri = FileUtils.contentUri(this@MainActivity, file)
                                    nav.navigate("cleanshare?uri=${Uri.encode(uri.toString())}")
                                },
                                onOpenSmartPack = { nav.navigate("smartpack") }
                            )
                        }
                        composable("barcode") { BarcodeScreen { nav.popBackStack() } }
                        composable("stitch") { StitchScreen(onBack = { nav.popBackStack() }, onOpenPdf = openPdf) }
                        composable("command") { CommandScreen(onBack = { nav.popBackStack() }, onOpenPdf = openPdf) }
                        composable(
                            route = "cleanshare?uri={uri}",
                            arguments = listOf(navArgument("uri") { type = NavType.StringType; defaultValue = "" })
                        ) { entry ->
                            val uriText = Uri.decode(entry.arguments?.getString("uri").orEmpty())
                            CleanShareScreen(onBack = { nav.popBackStack() }, onOpenPdf = openPdf, initialUriText = uriText)
                        }
                        composable("smartform") { SmartFormScreen(onBack = { nav.popBackStack() }, onOpenPdf = openPdf) }
                        composable("smartpack") { SmartPackScreen(onBack = { nav.popBackStack() }) }
                        composable(
                            route = "viewer?path={path}",
                            arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })
                        ) { entry ->
                            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
                            PdfViewerScreen(filePath = path, onBack = { nav.popBackStack() })
                        }

                        composable(
                            route = "editor?uri={uri}",
                            arguments = listOf(navArgument("uri") { type = NavType.StringType; defaultValue = "" })
                        ) { entry ->
                            val uriText = Uri.decode(entry.arguments?.getString("uri").orEmpty())
                            PdfEditorScreen(uriText = uriText, onBack = { nav.popBackStack() }, onOpenPdf = openPdf)
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBack = { nav.popBackStack() },
                                darkMode = darkMode,
                                onDarkModeChange = {
                                    darkMode = it
                                    prefs.edit().putBoolean("dark_mode", it).apply()
                                },
                                appLanguage = appLanguage,
                                onLanguageChange = {
                                    appLanguage = it
                                    prefs.edit().putString("language", it.code).apply()
                                }
                            )
                        }
                    }

                    availableUpdate?.let { update ->
                        AlertDialog(
                            onDismissRequest = { if (!update.mandatory && !updateDownloading) availableUpdate = null },
                            title = { Text(this@MainActivity.uiText(appLanguage, "p9_update_title")) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(update.versionName)
                                    if (update.notes.isNotBlank()) Text(update.notes)
                                    if (updateDownloading) {
                                        CircularProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                    if (updateError) Text(this@MainActivity.uiText(appLanguage, "p9_update_failed"))
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = !updateDownloading,
                                    onClick = {
                                        updateDownloading = true
                                        updateError = false
                                        updateScope.launch {
                                            runCatching { AppUpdateManager.downloadAndRequestInstall(this@MainActivity, update) }
                                                .onSuccess { result ->
                                                    updateDownloading = false
                                                    if (result == AppUpdateManager.InstallLaunchResult.PERMISSION_REQUESTED) {
                                                        Toast.makeText(
                                                            this@MainActivity,
                                                            this@MainActivity.uiText(appLanguage, "p9_update_install_permission"),
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                }
                                                .onFailure {
                                                    updateDownloading = false
                                                    updateError = true
                                                }
                                        }
                                    }
                                ) { Text(this@MainActivity.uiText(appLanguage, "p9_update_now")) }
                            },
                            dismissButton = if (!update.mandatory) {
                                {
                                    TextButton(
                                        enabled = !updateDownloading,
                                        onClick = { availableUpdate = null }
                                    ) { Text(this@MainActivity.uiText(appLanguage, "p9_update_later")) }
                                }
                            } else null
                        )
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        AppUpdateManager.resumePendingInstall(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            lifecycleScope.launch(Dispatchers.IO) { RuntimeSafety.cleanupTemporaryFiles(applicationContext) }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        lifecycleScope.launch(Dispatchers.IO) { RuntimeSafety.cleanupTemporaryFiles(applicationContext) }
    }

}
