@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.abfilepro.app.ui.screens

import android.graphics.Bitmap
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abfilepro.app.core.DeviceStorageManager
import com.abfilepro.app.core.ExplorerAction
import com.abfilepro.app.core.ExplorerActionRules
import com.abfilepro.app.core.ExplorerFavoriteStore
import com.abfilepro.app.core.ExplorerPremiumRules
import com.abfilepro.app.core.ExplorerVisualKind
import com.abfilepro.app.core.ExplorerAppearanceRules
import com.abfilepro.app.core.FileManagerRules
import com.abfilepro.app.core.WorkspaceStorageManager
import com.abfilepro.app.core.WorkspaceShortcutManager
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private enum class DirectViewMode { LIST, GRID, DETAILS }
private enum class DirectClipboardMode { COPY, CUT }
private data class DirectClipboard(val mode: DirectClipboardMode, val entries: List<DeviceStorageManager.Entry>)

@Composable
fun AllFilesAccessGate(onGrant: () -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    Scaffold(
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 58.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                    Text(context.uiText(language, "explorer_this_device"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Storage, null, Modifier.size(86.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(context.uiText(language, "explorer_all_files_title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(context.uiText(language, "explorer_all_files_desc"), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onGrant) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text(context.uiText(language, "explorer_all_files_grant"))
            }
        }
    }
}

@Composable
fun DirectFileExplorerPane(initialDirectoryPath: String? = null, onBack: () -> Unit, onOpenLegacyWorkspace: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var workspaceDir by remember { mutableStateOf(WorkspaceStorageManager.workspaceDir(context)) }
    var workspaceNameDraft by remember { mutableStateOf(WorkspaceStorageManager.savedName(context) ?: "AB File Pro") }
    var workspaceSetupError by remember { mutableStateOf<String?>(null) }

    if (workspaceDir == null) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(context.uiText(language, "workspace_setup_title")) },
            text = {
                Column {
                    Text(context.uiText(language, "workspace_setup_desc"))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = workspaceNameDraft,
                        onValueChange = { workspaceNameDraft = it; workspaceSetupError = null },
                        label = { Text(context.uiText(language, "workspace_name")) },
                        singleLine = true
                    )
                    workspaceSetupError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(enabled = workspaceNameDraft.isNotBlank(), onClick = {
                    runCatching { WorkspaceStorageManager.createWorkspace(context, workspaceNameDraft) }
                        .onSuccess { workspaceDir = it }
                        .onFailure { workspaceSetupError = it.message ?: context.uiText(language, "saf_operation_failed") }
                }) { Text(context.uiText(language, "workspace_create")) }
            },
            dismissButton = { TextButton(onClick = onBack) { Text(context.uiText(language, "saf_cancel")) } }
        )
        return
    }

    val root = workspaceDir!!
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val appearancePrefs = remember { context.getSharedPreferences("ab_file_pro_explorer_appearance", 0) }
    var explorerTheme by remember { mutableStateOf(ExplorerAppearanceRules.theme(appearancePrefs.getString("theme", "default"))) }
    var explorerStyle by remember { mutableStateOf(ExplorerAppearanceRules.style(appearancePrefs.getString("style", "windows"))) }
    var appearanceDialog by remember { mutableStateOf(false) }
    var currentDir by remember(root.absolutePath, initialDirectoryPath) {
        val requested = initialDirectoryPath?.let(::File)?.takeIf { it.isDirectory && WorkspaceStorageManager.isInsideWorkspace(root, it) }
        mutableStateOf(requested ?: root)
    }
    var entries by remember { mutableStateOf<List<DeviceStorageManager.Entry>>(emptyList()) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var viewMode by remember { mutableStateOf(when (explorerStyle) { "cards" -> DirectViewMode.GRID; "compact" -> DirectViewMode.DETAILS; else -> DirectViewMode.LIST }) }
    var sortKey by remember { mutableStateOf(FileManagerRules.SortKey.NAME) }
    var ascending by remember { mutableStateOf(true) }
    var search by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var clipboard by remember { mutableStateOf<DirectClipboard?>(null) }
    var favoriteVersion by remember { mutableIntStateOf(0) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var newMenuOpen by remember { mutableStateOf(false) }

    var createFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var createTextDialog by remember { mutableStateOf(false) }
    var newTextName by remember { mutableStateOf("New file.txt") }
    var newTextContent by remember { mutableStateOf("") }
    var renameEntry by remember { mutableStateOf<DeviceStorageManager.Entry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteEntries by remember { mutableStateOf<List<DeviceStorageManager.Entry>>(emptyList()) }
    var zipDialog by remember { mutableStateOf(false) }
    var zipName by remember { mutableStateOf("Archive.zip") }
    var pdfDialog by remember { mutableStateOf(false) }
    var pdfName by remember { mutableStateOf("Images.pdf") }
    var extractEntry by remember { mutableStateOf<DeviceStorageManager.Entry?>(null) }
    var previewEntry by remember { mutableStateOf<DeviceStorageManager.Entry?>(null) }
    var contextEntry by remember { mutableStateOf<DeviceStorageManager.Entry?>(null) }
    var propertiesEntry by remember { mutableStateOf<DeviceStorageManager.Entry?>(null) }
    var zipTargets by remember { mutableStateOf<List<DeviceStorageManager.Entry>>(emptyList()) }
    var pdfTargets by remember { mutableStateOf<List<DeviceStorageManager.Entry>>(emptyList()) }

    fun toast(text: String, long: Boolean = false) = Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    fun requestHomeShortcut() {
        val requested = WorkspaceShortcutManager.requestPin(context, root.name)
        toast(context.uiText(language, if (requested) "workspace_shortcut_requested" else "workspace_shortcut_unavailable"), !requested)
    }
    fun clearSelection() { selectedPaths = emptySet(); selectionMode = false }
    fun toggle(entry: DeviceStorageManager.Entry) {
        selectionMode = true
        selectedPaths = if (entry.path in selectedPaths) selectedPaths - entry.path else selectedPaths + entry.path
    }
    fun prepareRename(entry: DeviceStorageManager.Entry) {
        renameEntry = entry
        renameText = if (entry.isDirectory) entry.name else entry.name.substringBeforeLast('.', entry.name)
    }
    fun reload() { refreshVersion++ }
    fun runOperation(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { block() } }
            catch (_: CancellationException) { }
            catch (e: Throwable) { toast(e.message ?: context.uiText(language, "saf_operation_failed"), true) }
            finally { reload(); busy = false }
        }
    }

    LaunchedEffect(root.absolutePath) {
        WorkspaceShortcutManager.requestPinOnce(context, root.name)
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            runOperation {
                val imported = WorkspaceStorageManager.importUris(context, uris, currentDir)
                withContext(Dispatchers.Main) { toast(context.uiText(language, "workspace_import_done", imported.size)) }
            }
        }
    }

    LaunchedEffect(currentDir.absolutePath, refreshVersion) {
        loading = true
        entries = withContext(Dispatchers.IO) { runCatching { DeviceStorageManager.listChildren(currentDir) }.getOrDefault(emptyList()) }
        loading = false
    }

    val visible = remember(entries, search, sortKey, ascending, favoritesOnly, favoriteVersion) {
        val favoritePaths = if (favoritesOnly) ExplorerFavoriteStore.paths(context) else emptySet()
        val filtered = entries.filter {
            (search.isBlank() || it.name.contains(search.trim(), ignoreCase = true)) &&
                (!favoritesOnly || it.path in favoritePaths)
        }
        val comparator = when (sortKey) {
            FileManagerRules.SortKey.NAME -> compareBy<DeviceStorageManager.Entry> { it.name.lowercase() }
            FileManagerRules.SortKey.DATE -> compareBy<DeviceStorageManager.Entry> { it.lastModified }
            FileManagerRules.SortKey.SIZE -> compareBy<DeviceStorageManager.Entry> { it.size }
            FileManagerRules.SortKey.TYPE -> compareBy<DeviceStorageManager.Entry> { FileManagerRules.extensionOf(it.name) }
        }
        val sorted = filtered.sortedWith(compareBy<DeviceStorageManager.Entry> { !it.isDirectory }.then(comparator))
        if (ascending) sorted else sorted.groupBy { it.isDirectory }.let { groups ->
            groups[true].orEmpty().reversed() + groups[false].orEmpty().reversed()
        }
    }
    val selectedEntries = entries.filter { it.path in selectedPaths }

    fun openEntry(entry: DeviceStorageManager.Entry) {
        if (selectionMode) { toggle(entry); return }
        when {
            entry.isDirectory -> { currentDir = entry.file; search = ""; clearSelection() }
            entry.name.endsWith(".zip", true) -> extractEntry = entry
            FileManagerRules.previewKind(entry.name, entry.mimeType) == FileManagerRules.PreviewKind.IMAGE -> previewEntry = entry
            else -> runCatching { DeviceStorageManager.open(context, entry) }.onFailure { toast(it.message ?: context.uiText(language, "saf_open_failed"), true) }
        }
    }

    fun goBack() {
        when {
            previewEntry != null -> previewEntry = null
            selectionMode -> clearSelection()
            currentDir.canonicalFile != root.canonicalFile -> currentDir.parentFile?.takeIf { WorkspaceStorageManager.isInsideWorkspace(root, it) }?.let { currentDir = it }
            else -> onBack()
        }
    }
    BackHandler(onBack = ::goBack)

    if (appearanceDialog) {
        AlertDialog(
            onDismissRequest = { appearanceDialog = false },
            title = { Text(context.uiText(language, "workspace_appearance")) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(context.uiText(language, "workspace_theme"), fontWeight = FontWeight.Bold)
                    listOf(
                        "default" to "workspace_theme_default",
                        "light" to "workspace_theme_light",
                        "dark" to "workspace_theme_dark",
                        "blue" to "workspace_theme_blue"
                    ).forEach { (value, key) ->
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { explorerTheme = value; appearancePrefs.edit().putString("theme", value).apply() }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = explorerTheme == value, onClick = { explorerTheme = value; appearancePrefs.edit().putString("theme", value).apply() })
                            Text(context.uiText(language, key))
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(context.uiText(language, "workspace_style"), fontWeight = FontWeight.Bold)
                    listOf(
                        "windows" to ("workspace_style_windows" to DirectViewMode.LIST),
                        "cards" to ("workspace_style_cards" to DirectViewMode.GRID),
                        "compact" to ("workspace_style_compact" to DirectViewMode.DETAILS)
                    ).forEach { (value, pair) ->
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { explorerStyle = value; viewMode = pair.second; appearancePrefs.edit().putString("style", value).apply() }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = explorerStyle == value, onClick = { explorerStyle = value; viewMode = pair.second; appearancePrefs.edit().putString("style", value).apply() })
                            Text(context.uiText(language, pair.first))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = ::requestHomeShortcut, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AddToHomeScreen, null)
                        Spacer(Modifier.width(8.dp))
                        Text(context.uiText(language, "workspace_add_shortcut"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { appearanceDialog = false }) { Text(context.uiText(language, "saf_close")) } }
        )
    }

    if (createFolderDialog) {
        AlertDialog(onDismissRequest = { createFolderDialog = false }, title = { Text(context.uiText(language, "saf_new_folder")) },
            text = { OutlinedTextField(newFolderName, { newFolderName = it }, singleLine = true) },
            confirmButton = { Button(enabled = newFolderName.isNotBlank(), onClick = { val n = newFolderName; createFolderDialog = false; newFolderName = ""; runOperation { DeviceStorageManager.createFolder(currentDir, n) } }) { Text(context.uiText(language, "saf_create")) } },
            dismissButton = { TextButton(onClick = { createFolderDialog = false }) { Text(context.uiText(language, "saf_cancel")) } })
    }
    if (createTextDialog) {
        AlertDialog(
            onDismissRequest = { createTextDialog = false },
            title = { Text(context.uiText(language, "workspace_new_text_file")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newTextName,
                        onValueChange = { newTextName = it },
                        label = { Text(context.uiText(language, "workspace_text_file_name")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newTextContent,
                        onValueChange = { newTextContent = it },
                        label = { Text(context.uiText(language, "workspace_text_content")) },
                        minLines = 5,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(enabled = newTextName.isNotBlank(), onClick = {
                    val name = newTextName
                    val content = newTextContent
                    createTextDialog = false
                    newTextName = "New file.txt"
                    newTextContent = ""
                    runOperation {
                        val created = DeviceStorageManager.createTextFile(currentDir, name, content)
                        withContext(Dispatchers.Main) { toast(context.uiText(language, "workspace_text_created", created.name)) }
                    }
                }) { Text(context.uiText(language, "saf_save")) }
            },
            dismissButton = { TextButton(onClick = { createTextDialog = false }) { Text(context.uiText(language, "saf_cancel")) } }
        )
    }
    renameEntry?.let { entry ->
        val originalExtension = if (entry.isDirectory) "" else FileManagerRules.extensionOf(entry.name)
        AlertDialog(
            onDismissRequest = { renameEntry = null },
            title = { Text(context.uiText(language, "saf_rename")) },
            text = {
                OutlinedTextField(
                    renameText,
                    { renameText = it },
                    singleLine = true,
                    supportingText = {
                        if (originalExtension.isNotBlank()) Text(context.uiText(language, "workspace_rename_extension_kept", ".$originalExtension"))
                    }
                )
            },
            confirmButton = {
                Button(enabled = renameText.isNotBlank(), onClick = {
                    val requested = ExplorerPremiumRules.renameKeepingExtension(entry.name, renameText, entry.isDirectory)
                    val wasFavorite = ExplorerFavoriteStore.isFavorite(context, entry.file)
                    renameEntry = null
                    runOperation {
                        val renamed = DeviceStorageManager.rename(entry, requested)
                        if (wasFavorite) {
                            ExplorerFavoriteStore.setFavorite(context, entry.file, false)
                            ExplorerFavoriteStore.setFavorite(context, renamed.file, true)
                            withContext(Dispatchers.Main) { favoriteVersion++ }
                        }
                    }
                }) { Text(context.uiText(language, "saf_save")) }
            },
            dismissButton = { TextButton(onClick = { renameEntry = null }) { Text(context.uiText(language, "saf_cancel")) } }
        )
    }
    if (deleteEntries.isNotEmpty()) {
        AlertDialog(onDismissRequest = { deleteEntries = emptyList() }, title = { Text(context.uiText(language, "saf_delete_title")) },
            text = { Text(context.uiText(language, "saf_delete_warning", deleteEntries.size)) },
            confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = { val snapshot = deleteEntries; deleteEntries = emptyList(); runOperation { snapshot.forEach { DeviceStorageManager.delete(it) }; clearSelection() } }) { Text(context.uiText(language, "saf_delete_permanent")) } },
            dismissButton = { TextButton(onClick = { deleteEntries = emptyList() }) { Text(context.uiText(language, "saf_cancel")) } })
    }
    if (zipDialog) {
        AlertDialog(onDismissRequest = { zipDialog = false; zipTargets = emptyList() }, title = { Text(context.uiText(language, "saf_zip_create")) },
            text = { OutlinedTextField(zipName, { zipName = it }, label = { Text(context.uiText(language, "saf_zip_name")) }, singleLine = true) },
            confirmButton = { Button(enabled = zipTargets.isNotEmpty(), onClick = { val selected = zipTargets.toList(); val name = zipName; zipDialog = false; zipTargets = emptyList(); runOperation { DeviceStorageManager.createZip(selected, currentDir, name); withContext(Dispatchers.Main) { clearSelection(); toast(context.uiText(language, "saf_zip_created")) } } }) { Text(context.uiText(language, "saf_create")) } },
            dismissButton = { TextButton(onClick = { zipDialog = false; zipTargets = emptyList() }) { Text(context.uiText(language, "saf_cancel")) } })
    }
    if (pdfDialog) {
        AlertDialog(
            onDismissRequest = { pdfDialog = false; pdfTargets = emptyList() },
            title = { Text(context.uiText(language, "workspace_images_pdf")) },
            text = { OutlinedTextField(pdfName, { pdfName = it }, label = { Text(context.uiText(language, "workspace_pdf_name")) }, singleLine = true) },
            confirmButton = {
                Button(enabled = pdfTargets.isNotEmpty(), onClick = {
                    val selected = pdfTargets.toList()
                    val name = pdfName
                    pdfDialog = false
                    pdfTargets = emptyList()
                    runOperation {
                        val result = WorkspaceStorageManager.imagesToPdf(selected, currentDir, name)
                        withContext(Dispatchers.Main) { clearSelection(); toast(context.uiText(language, "workspace_pdf_created", result.name)) }
                    }
                }) { Text(context.uiText(language, "saf_create")) }
            },
            dismissButton = { TextButton(onClick = { pdfDialog = false; pdfTargets = emptyList() }) { Text(context.uiText(language, "saf_cancel")) } }
        )
    }

    extractEntry?.let { entry ->
        AlertDialog(onDismissRequest = { extractEntry = null }, title = { Text(context.uiText(language, "explorer_zip_actions")) }, text = { Text(entry.name) },
            confirmButton = { Button(onClick = { extractEntry = null; runOperation { val (_, count) = DeviceStorageManager.extractZip(entry, currentDir, false); withContext(Dispatchers.Main) { toast(context.uiText(language, "explorer_extract_here_done", count)) } } }) { Text(context.uiText(language, "explorer_extract_here")) } },
            dismissButton = { Row { TextButton(onClick = { extractEntry = null; runOperation { val (_, count) = DeviceStorageManager.extractZip(entry, currentDir, true); withContext(Dispatchers.Main) { toast(context.uiText(language, "explorer_extract_folder_done", count)) } } }) { Text(context.uiText(language, "explorer_extract_folder")) }; TextButton(onClick = { extractEntry = null }) { Text(context.uiText(language, "saf_cancel")) } } })
    }
    previewEntry?.let { entry -> DirectImagePreview(entry, onDismiss = { previewEntry = null }) }

    fun setClipboard(mode: DirectClipboardMode) {
        if (selectedEntries.isEmpty()) return
        val snapshot = selectedEntries.toList()
        clipboard = DirectClipboard(mode, snapshot)
        clearSelection()
        toast(context.uiText(language, if (mode == DirectClipboardMode.COPY) "workspace_copied_count" else "workspace_cut_count", snapshot.size))
    }
    fun paste(destination: File = currentDir) {
        val clip = clipboard ?: return
        val target = destination.takeIf { it.isDirectory && WorkspaceStorageManager.isInsideWorkspace(root, it) } ?: currentDir
        runOperation {
            val remaining = clip.entries.toMutableList()
            try {
                clip.entries.forEach {
                    if (clip.mode == DirectClipboardMode.COPY) DeviceStorageManager.copy(it, target) else DeviceStorageManager.move(it, target)
                    remaining.remove(it)
                }
            } finally {
                if (clip.mode == DirectClipboardMode.CUT) withContext(NonCancellable + Dispatchers.Main) {
                    if (clipboard == clip) clipboard = if (remaining.isEmpty()) null else DirectClipboard(clip.mode, remaining.toList())
                }
            }
            withContext(Dispatchers.Main) {
                toast(context.uiText(language, "workspace_paste_done", clip.entries.size))
            }
        }
    }

    fun actionLabel(action: ExplorerAction): String = when (action) {
        ExplorerAction.OPEN -> context.uiText(language, "saf_open")
        ExplorerAction.COPY -> context.uiText(language, "saf_copy")
        ExplorerAction.CUT -> context.uiText(language, "explorer_cut")
        ExplorerAction.PASTE -> context.uiText(language, "explorer_paste")
        ExplorerAction.RENAME -> context.uiText(language, "saf_rename")
        ExplorerAction.DUPLICATE -> context.uiText(language, "workspace_duplicate")
        ExplorerAction.OPEN_WITH -> context.uiText(language, "workspace_open_with")
        ExplorerAction.FAVORITE -> context.uiText(language, "workspace_favorite")
        ExplorerAction.SHARE -> context.uiText(language, "saf_share")
        ExplorerAction.ZIP -> context.uiText(language, "saf_zip")
        ExplorerAction.IMAGES_TO_PDF -> context.uiText(language, "workspace_images_pdf")
        ExplorerAction.EXTRACT_HERE -> context.uiText(language, "explorer_extract_here")
        ExplorerAction.EXTRACT_FOLDER -> context.uiText(language, "explorer_extract_folder")
        ExplorerAction.PROPERTIES -> context.uiText(language, "workspace_properties")
        ExplorerAction.DELETE -> context.uiText(language, "saf_delete")
    }

    fun actionIcon(action: ExplorerAction) = when (action) {
        ExplorerAction.OPEN -> Icons.Default.OpenInNew
        ExplorerAction.COPY -> Icons.Default.ContentCopy
        ExplorerAction.CUT -> Icons.Default.ContentCut
        ExplorerAction.PASTE -> Icons.Default.ContentPaste
        ExplorerAction.RENAME -> Icons.Default.Edit
        ExplorerAction.DUPLICATE -> Icons.Default.ControlPointDuplicate
        ExplorerAction.OPEN_WITH -> Icons.Default.OpenInNew
        ExplorerAction.FAVORITE -> Icons.Default.Star
        ExplorerAction.SHARE -> Icons.Default.Share
        ExplorerAction.ZIP -> Icons.Default.FolderZip
        ExplorerAction.IMAGES_TO_PDF -> Icons.Default.PictureAsPdf
        ExplorerAction.EXTRACT_HERE, ExplorerAction.EXTRACT_FOLDER -> Icons.Default.Unarchive
        ExplorerAction.PROPERTIES -> Icons.Default.Info
        ExplorerAction.DELETE -> Icons.Default.Delete
    }

    val inheritedScheme = MaterialTheme.colorScheme
    val premiumScheme = lightColorScheme(
        primary = Color(0xFF2457D6),
        onPrimary = Color.White,
        secondary = Color(0xFFFF7358),
        tertiary = Color(0xFFF4B52A),
        error = Color(0xFFE44747),
        background = Color(0xFFFFFBF5),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF3F5FA),
        secondaryContainer = Color(0xFFFFE5DD),
        tertiaryContainer = Color(0xFFFFF0BE)
    )
    val explorerScheme = when (explorerTheme) {
        "light" -> premiumScheme
        "dark" -> darkColorScheme(primary = Color(0xFF8DB4FF), secondary = Color(0xFFFF9C86), tertiary = Color(0xFFFFD166), background = Color(0xFF0B1220), surface = Color(0xFF111827))
        "blue" -> lightColorScheme(primary = Color(0xFF0B57D0), secondary = Color(0xFFFF7358), tertiary = Color(0xFFF4B52A), background = Color(0xFFEFF6FF), surface = Color(0xFFF8FBFF), surfaceVariant = Color(0xFFDCEBFF))
        "default" -> premiumScheme
        else -> inheritedScheme
    }

    MaterialTheme(colorScheme = explorerScheme) {
    contextEntry?.let { entry ->
        val actions = ExplorerActionRules.actionsFor(
            isDirectory = entry.isDirectory,
            extension = FileManagerRules.extensionOf(entry.name),
            isImage = WorkspaceStorageManager.isImage(entry),
            clipboardAvailable = clipboard != null
        )
        val favoriteNow = ExplorerFavoriteStore.isFavorite(context, entry.file)
        ModalBottomSheet(
            onDismissRequest = { contextEntry = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = directAccent(entry).copy(alpha = 0.14f)
                ) {
                    Icon(
                        directIcon(entry),
                        null,
                        Modifier.padding(12.dp).size(30.dp),
                        tint = directAccent(entry)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    Text(
                        if (entry.isDirectory) context.uiText(language, "saf_folder")
                        else Formatter.formatFileSize(context, entry.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (favoriteNow) Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.tertiary)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(actions, key = { it.name }) { action ->
                    val label = when (action) {
                        ExplorerAction.FAVORITE -> context.uiText(language, if (favoriteNow) "workspace_remove_favorite" else "workspace_add_favorite")
                        else -> actionLabel(action)
                    }
                    val tint = when (action) {
                        ExplorerAction.DELETE -> MaterialTheme.colorScheme.error
                        ExplorerAction.FAVORITE -> MaterialTheme.colorScheme.tertiary
                        ExplorerAction.COPY, ExplorerAction.CUT, ExplorerAction.PASTE, ExplorerAction.DUPLICATE -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.primary
                    }
                    ListItem(
                        headlineContent = { Text(label, color = if (action == ExplorerAction.DELETE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                        leadingContent = {
                            Surface(shape = CircleShape, color = tint.copy(alpha = 0.12f)) {
                                Icon(actionIcon(action), null, Modifier.padding(9.dp).size(21.dp), tint = tint)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.combinedClickable(onClick = {
                            contextEntry = null
                            when (action) {
                                ExplorerAction.OPEN -> openEntry(entry)
                                ExplorerAction.COPY -> {
                                    clipboard = DirectClipboard(DirectClipboardMode.COPY, listOf(entry))
                                    clearSelection()
                                    toast(context.uiText(language, "workspace_copied_count", 1))
                                }
                                ExplorerAction.CUT -> {
                                    clipboard = DirectClipboard(DirectClipboardMode.CUT, listOf(entry))
                                    clearSelection()
                                    toast(context.uiText(language, "workspace_cut_count", 1))
                                }
                                ExplorerAction.PASTE -> paste(if (entry.isDirectory) entry.file else currentDir)
                                ExplorerAction.RENAME -> prepareRename(entry)
                                ExplorerAction.DUPLICATE -> runOperation {
                                    val result = DeviceStorageManager.copy(entry, currentDir)
                                    withContext(Dispatchers.Main) { toast(context.uiText(language, "workspace_duplicated", result.name)) }
                                }
                                ExplorerAction.OPEN_WITH -> runCatching { DeviceStorageManager.open(context, entry) }
                                    .onFailure { toast(it.message ?: context.uiText(language, "saf_open_failed"), true) }
                                ExplorerAction.FAVORITE -> {
                                    val enabled = ExplorerFavoriteStore.toggle(context, entry.file)
                                    favoriteVersion++
                                    toast(context.uiText(language, if (enabled) "workspace_favorite_added" else "workspace_favorite_removed"))
                                }
                                ExplorerAction.SHARE -> runCatching { DeviceStorageManager.share(context, listOf(entry)) }
                                    .onFailure { toast(it.message ?: context.uiText(language, "saf_share_failed"), true) }
                                ExplorerAction.ZIP -> {
                                    zipTargets = listOf(entry)
                                    zipName = "${entry.name.substringBeforeLast('.', entry.name)}.zip"
                                    zipDialog = true
                                }
                                ExplorerAction.IMAGES_TO_PDF -> {
                                    pdfTargets = listOf(entry)
                                    pdfName = "${entry.name.substringBeforeLast('.', entry.name)}.pdf"
                                    pdfDialog = true
                                }
                                ExplorerAction.EXTRACT_HERE -> runOperation {
                                    val (_, count) = DeviceStorageManager.extractZip(entry, currentDir, false)
                                    withContext(Dispatchers.Main) { toast(context.uiText(language, "explorer_extract_here_done", count)) }
                                }
                                ExplorerAction.EXTRACT_FOLDER -> runOperation {
                                    val (folder, _) = DeviceStorageManager.extractZip(entry, currentDir, true)
                                    withContext(Dispatchers.Main) { toast(context.uiText(language, "explorer_extract_folder_done", folder.name)) }
                                }
                                ExplorerAction.PROPERTIES -> propertiesEntry = entry
                                ExplorerAction.DELETE -> deleteEntries = listOf(entry)
                            }
                        })
                    )
                }
            }
        }
    }

    if (newMenuOpen) {
        ModalBottomSheet(onDismissRequest = { newMenuOpen = false }) {
            Text(
                context.uiText(language, "workspace_new"),
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            ListItem(
                headlineContent = { Text(context.uiText(language, "saf_new_folder")) },
                supportingContent = { Text(context.uiText(language, "workspace_new_folder_hint")) },
                leadingContent = {
                    Surface(shape = CircleShape, color = Color(0xFFF4B52A).copy(alpha = 0.18f)) {
                        Icon(Icons.Default.CreateNewFolder, null, Modifier.padding(10.dp), tint = Color(0xFFC88A00))
                    }
                },
                modifier = Modifier.combinedClickable(onClick = {
                    newMenuOpen = false
                    newFolderName = ""
                    createFolderDialog = true
                })
            )
            ListItem(
                headlineContent = { Text(context.uiText(language, "workspace_new_text_file")) },
                supportingContent = { Text(context.uiText(language, "workspace_new_text_hint")) },
                leadingContent = {
                    Surface(shape = CircleShape, color = Color(0xFF2457D6).copy(alpha = 0.12f)) {
                        Icon(Icons.Default.NoteAdd, null, Modifier.padding(10.dp), tint = Color(0xFF2457D6))
                    }
                },
                modifier = Modifier.combinedClickable(onClick = {
                    newMenuOpen = false
                    newTextName = "New file.txt"
                    newTextContent = ""
                    createTextDialog = true
                })
            )
            ListItem(
                headlineContent = { Text(context.uiText(language, "workspace_import")) },
                supportingContent = { Text(context.uiText(language, "workspace_import_hint")) },
                leadingContent = {
                    Surface(shape = CircleShape, color = Color(0xFFFF7358).copy(alpha = 0.14f)) {
                        Icon(Icons.Default.UploadFile, null, Modifier.padding(10.dp), tint = Color(0xFFFF7358))
                    }
                },
                modifier = Modifier.combinedClickable(onClick = {
                    newMenuOpen = false
                    importLauncher.launch(arrayOf("*/*"))
                })
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    propertiesEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { propertiesEntry = null },
            title = { Text(context.uiText(language, "workspace_properties")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${context.uiText(language, "workspace_property_name")}: ${entry.name}")
                    Text("${context.uiText(language, "workspace_property_type")}: ${if (entry.isDirectory) context.uiText(language, "saf_folder") else FileManagerRules.extensionOf(entry.name).uppercase().ifBlank { context.uiText(language, "workspace_file") }}")
                    Text("${context.uiText(language, "workspace_property_size")}: ${if (entry.isDirectory) "—" else Formatter.formatFileSize(context, entry.size)}")
                    Text("${context.uiText(language, "workspace_property_date")}: ${formatDirectDate(entry.lastModified)}")
                    Text("${context.uiText(language, "workspace_property_path")}: ${entry.path}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { propertiesEntry = null }) { Text(context.uiText(language, "saf_close")) } }
        )
    }

    ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
        ModalDrawerSheet {
            Spacer(Modifier.height(16.dp)); Text(context.uiText(language, "explorer_this_device"), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            DirectNavItem(Icons.Default.Home, context.uiText(language, "explorer_this_device")) { currentDir = root; scope.launch { drawerState.close() } }
            listOf(
                "Download" to (Icons.Default.Download to "explorer_downloads"),
                "DCIM" to (Icons.Default.PhotoCamera to "explorer_camera"),
                "Pictures" to (Icons.Default.Image to "explorer_pictures"),
                "Documents" to (Icons.Default.Description to "explorer_documents"),
                "Movies" to (Icons.Default.VideoLibrary to "explorer_videos"),
                "Music" to (Icons.Default.MusicNote to "explorer_music")
            ).forEach { (name, pair) ->
                val file = File(root, name)
                if (file.exists()) DirectNavItem(pair.first, context.uiText(language, pair.second)) { currentDir = file; scope.launch { drawerState.close() } }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            DirectNavItem(Icons.Default.FolderSpecial, context.uiText(language, "explorer_ab_workspace")) { scope.launch { drawerState.close() }; onOpenLegacyWorkspace() }
        }
    }) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 62.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, null) }
                        IconButton(onClick = ::goBack) { Icon(Icons.Default.ArrowBack, null) }
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(54.dp)
                        ) {
                            Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(31.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(currentDir.name.ifBlank { context.uiText(language, "explorer_this_device") }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(context.uiText(language, "workspace_premium_explorer"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        IconButton(onClick = ::reload) { Icon(Icons.Default.Refresh, null) }
                    }
                }
            },
            bottomBar = {
                clipboard?.let { clip ->
                    Surface(shadowElevation = 10.dp, tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)) {
                                Icon(
                                    if (clip.mode == DirectClipboardMode.COPY) Icons.Default.ContentCopy else Icons.Default.ContentCut,
                                    null,
                                    Modifier.padding(9.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                context.uiText(language, if (clip.mode == DirectClipboardMode.COPY) "workspace_clipboard_copy" else "workspace_clipboard_cut", clip.entries.size),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            TextButton(onClick = { clipboard = null }) { Text(context.uiText(language, "saf_cancel")) }
                            Button(onClick = { paste() }) {
                                Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(context.uiText(language, "workspace_paste_here"))
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                if (!selectionMode) {
                    ExtendedFloatingActionButton(
                        onClick = { newMenuOpen = true },
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text(context.uiText(language, "workspace_new"), fontWeight = FontWeight.Bold) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                DirectBreadcrumb(root, currentDir) { currentDir = it; clearSelection(); search = "" }
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = favoritesOnly,
                        onClick = { favoritesOnly = !favoritesOnly },
                        label = { Text(context.uiText(language, "saf_favorites")) },
                        leadingIcon = { Icon(if (favoritesOnly) Icons.Default.Star else Icons.Default.StarBorder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary) }
                    )
                    Spacer(Modifier.weight(1f))
                    FilledTonalIconButton(onClick = { appearanceDialog = true }) { Icon(Icons.Default.Palette, context.uiText(language, "workspace_appearance"), tint = MaterialTheme.colorScheme.secondary) }
                    Spacer(Modifier.width(4.dp))
                    FilledTonalIconButton(onClick = ::requestHomeShortcut) { Icon(Icons.Default.AddToHomeScreen, context.uiText(language, "workspace_add_shortcut"), tint = MaterialTheme.colorScheme.primary) }
                }
                LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { AssistChip(onClick = { if (selectionMode) clearSelection() else selectionMode = true }, label = { Text(if (selectionMode) context.uiText(language, "saf_selection_cancel") else context.uiText(language, "saf_select")) }, leadingIcon = { Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp)) }) }
                    item { AssistChip(onClick = { viewMode = when (viewMode) { DirectViewMode.LIST -> DirectViewMode.GRID; DirectViewMode.GRID -> DirectViewMode.DETAILS; DirectViewMode.DETAILS -> DirectViewMode.LIST } }, label = { Text(context.uiText(language, when(viewMode){ DirectViewMode.LIST -> "saf_list"; DirectViewMode.GRID -> "saf_grid"; DirectViewMode.DETAILS -> "explorer_details" })) }, leadingIcon = { Icon(Icons.Default.ViewModule, null, Modifier.size(18.dp)) }) }
                    item { AssistChip(onClick = { sortKey = when(sortKey){ FileManagerRules.SortKey.NAME -> FileManagerRules.SortKey.DATE; FileManagerRules.SortKey.DATE -> FileManagerRules.SortKey.SIZE; FileManagerRules.SortKey.SIZE -> FileManagerRules.SortKey.TYPE; FileManagerRules.SortKey.TYPE -> FileManagerRules.SortKey.NAME } }, label = { Text(context.uiText(language, "saf_sort")) }, leadingIcon = { Icon(Icons.Default.Sort, null, Modifier.size(18.dp)) }) }
                }
                if (selectionMode) {
                    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { Text(context.uiText(language, "saf_selected_count", selectedEntries.size), Modifier.padding(vertical = 10.dp), fontWeight = FontWeight.Bold) }
                        item { AssistChip(onClick = { selectedPaths = visible.map { it.path }.toSet() }, label = { Text(context.uiText(language, "saf_select_all")) }) }
                        if (selectedEntries.isNotEmpty()) item { AssistChip(onClick = { setClipboard(DirectClipboardMode.COPY) }, label = { Text(context.uiText(language, "saf_copy")) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)) }) }
                        if (selectedEntries.isNotEmpty()) item { AssistChip(onClick = { setClipboard(DirectClipboardMode.CUT) }, label = { Text(context.uiText(language, "explorer_cut")) }, leadingIcon = { Icon(Icons.Default.ContentCut, null, Modifier.size(18.dp)) }) }
                        if (selectedEntries.isNotEmpty()) item { AssistChip(onClick = { zipTargets = selectedEntries.toList(); zipName = "Archive.zip"; zipDialog = true }, label = { Text(context.uiText(language, "saf_zip")) }, leadingIcon = { Icon(Icons.Default.FolderZip, null, Modifier.size(18.dp)) }) }
                        if (selectedEntries.isNotEmpty() && selectedEntries.all(WorkspaceStorageManager::isImage)) item { AssistChip(onClick = { pdfTargets = selectedEntries.toList(); pdfName = "Images.pdf"; pdfDialog = true }, label = { Text(context.uiText(language, "workspace_images_pdf")) }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp)) }) }
                        if (selectedEntries.size == 1 && selectedEntries.first().name.endsWith(".zip", true)) item { AssistChip(onClick = { extractEntry = selectedEntries.first() }, label = { Text(context.uiText(language, "saf_extract")) }, leadingIcon = { Icon(Icons.Default.Unarchive, null, Modifier.size(18.dp)) }) }
                        if (selectedEntries.any { it.isFile }) item { AssistChip(onClick = { runCatching { DeviceStorageManager.share(context, selectedEntries) }.onFailure { toast(it.message ?: "Share failed", true) } }, label = { Text(context.uiText(language, "saf_share")) }, leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(18.dp)) }) }
                        if (selectedEntries.isNotEmpty()) item { AssistChip(onClick = { deleteEntries = selectedEntries }, label = { Text(context.uiText(language, "saf_delete")) }, leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)) }) }
                    }
                }
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), placeholder = { Text(context.uiText(language, "saf_search")) }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!loading && visible.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(context.uiText(language, "saf_empty")) }
                else when(viewMode) {
                    DirectViewMode.LIST -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) { items(visible, key = { it.path }) { e -> DirectListRow(e, e.path in selectedPaths, selectionMode, onOpen = { openEntry(e) }, onSelect = { toggle(e) }, onContext = { contextEntry = e }) } }
                    DirectViewMode.GRID -> LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { gridItems(visible, key = { it.path }) { e -> DirectGridCard(e, e.path in selectedPaths, selectionMode, { openEntry(e) }, { toggle(e) }, { contextEntry = e }) } }
                    DirectViewMode.DETAILS -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) { items(visible, key={it.path}) { e -> DirectDetailsRow(e, e.path in selectedPaths, selectionMode, {openEntry(e)}, {toggle(e)}, { contextEntry = e }) } }
                }
            }
        }
    }
    }
}

@Composable private fun DirectNavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, click: () -> Unit) { NavigationDrawerItem(label={Text(text)}, selected=false, icon={Icon(icon,null)}, onClick=click) }

@Composable private fun DirectBreadcrumb(root: File, current: File, onOpen: (File)->Unit) {
    val relative = runCatching { current.canonicalFile.relativeTo(root.canonicalFile).path }.getOrDefault("")
    val parts = relative.split(File.separator).filter { it.isNotBlank() }
    LazyRow(Modifier.fillMaxWidth().padding(horizontal=8.dp), verticalAlignment=Alignment.CenterVertically) {
        item { TextButton(onClick={onOpen(root)}) { Icon(Icons.Default.Computer,null,Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(root.name) } }
        var cursor = root
        parts.forEachIndexed { index, name -> cursor = File(cursor,name); val target=cursor; item(key="$index-$name") { Icon(Icons.Default.ChevronRight,null,Modifier.size(16.dp)); TextButton(onClick={onOpen(target)}) { Text(name,maxLines=1) } } }
    }
}

@Composable
private fun DirectListRow(
    e: DeviceStorageManager.Entry,
    selected: Boolean,
    selection: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onContext: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    val accent = directAccent(e)
    val favorite = ExplorerFavoriteStore.isFavorite(context, e.file)

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .combinedClickable(
                onClick = { if (selection) onSelect() else onOpen() },
                onLongClick = { if (selection) onSelect() else onContext() }
            ),
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        e.name,
                        Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (e.isDirectory) FontWeight.SemiBold else FontWeight.Medium
                    )
                    if (favorite) {
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                    }
                }
            },
            supportingContent = {
                Text(
                    if (e.isDirectory) context.uiText(language, "saf_folder")
                    else "${Formatter.formatFileSize(context, e.size)} • ${formatDirectDate(e.lastModified)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = {
                if (selection) Checkbox(selected, { onSelect() })
                else Surface(shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = 0.13f)) {
                    Icon(directIcon(e), null, Modifier.padding(11.dp).size(25.dp), tint = accent)
                }
            },
            trailingContent = {
                IconButton(onClick = onContext) { Icon(Icons.Default.MoreVert, context.uiText(language, "workspace_actions")) }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun DirectGridCard(
    e: DeviceStorageManager.Entry,
    selected: Boolean,
    selection: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onContext: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accent = directAccent(e)
    val favorite = ExplorerFavoriteStore.isFavorite(context, e.file)
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 146.dp)
            .combinedClickable(
                onClick = { if (selection) onSelect() else onOpen() },
                onLongClick = { if (selection) onSelect() else onContext() }
            ),
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.75f) else accent.copy(alpha = 0.24f)),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth().height(68.dp)) {
                if (selection) Checkbox(selected, { onSelect() }, Modifier.align(Alignment.TopStart))
                if (favorite) Icon(Icons.Default.Star, null, Modifier.align(Alignment.TopEnd).size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                DirectGridVisual(e, Modifier.align(Alignment.Center).size(64.dp))
            }
            Spacer(Modifier.height(7.dp))
            Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(
                if (e.isDirectory) context.uiText(LocalAppLanguage.current, "saf_folder") else Formatter.formatFileSize(context, e.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DirectGridVisual(e: DeviceStorageManager.Entry, modifier: Modifier = Modifier) {
    val kind = ExplorerPremiumRules.visualKind(e.name, e.isDirectory)
    val accent = directAccent(e)
    var bitmap by remember(e.path, e.lastModified) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(e.path, e.lastModified, kind) {
        bitmap = if (kind == ExplorerVisualKind.IMAGE && e.isFile) {
            withContext(Dispatchers.IO) { DeviceStorageManager.loadImagePreview(e, maxDimension = 360) }
        } else null
    }
    val owned = bitmap
    DisposableEffect(owned) {
        onDispose { owned?.takeIf { !it.isRecycled }?.recycle() }
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = 0.12f)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            bitmap?.let {
                Image(it.asImageBitmap(), e.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: Icon(directIcon(e), null, Modifier.size(36.dp), tint = accent)
        }
    }
}

@Composable
private fun DirectDetailsRow(
    e: DeviceStorageManager.Entry,
    selected: Boolean,
    selection: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onContext: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    val accent = directAccent(e)
    val favorite = ExplorerFavoriteStore.isFavorite(context, e.file)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selection) onSelect() else onOpen() },
                onLongClick = { if (selection) onSelect() else onContext() }
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selection) Checkbox(selected, { onSelect() })
        Surface(shape = RoundedCornerShape(9.dp), color = accent.copy(alpha = 0.12f)) {
            Icon(directIcon(e), null, Modifier.padding(6.dp).size(22.dp), tint = accent)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(2f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (favorite) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                }
            }
            Text(formatDirectDate(e.lastModified), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (e.isDirectory) context.uiText(language, "saf_folder") else FileManagerRules.extensionOf(e.name).uppercase(), Modifier.weight(.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Text(if (e.isDirectory) "—" else Formatter.formatFileSize(context, e.size), Modifier.weight(.9f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        IconButton(onClick = onContext, Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, null, Modifier.size(18.dp)) }
    }
}

@Composable private fun DirectImagePreview(entry:DeviceStorageManager.Entry,onDismiss:()->Unit){var bmp by remember(entry.path){mutableStateOf<Bitmap?>(null)};var loading by remember(entry.path){mutableStateOf(true)};LaunchedEffect(entry.path,entry.lastModified){loading=true;bmp=withContext(Dispatchers.IO){DeviceStorageManager.loadImagePreview(entry)};loading=false};val owned=bmp;DisposableEffect(owned){onDispose{owned?.takeIf{!it.isRecycled}?.recycle()}};AlertDialog(onDismissRequest=onDismiss,title={Text(entry.name,maxLines=1)},text={Box(Modifier.fillMaxWidth().heightIn(min=180.dp,max=560.dp),contentAlignment=Alignment.Center){if(loading)CircularProgressIndicator()else bmp?.let{Image(it.asImageBitmap(),entry.name,Modifier.fillMaxWidth().heightIn(max=520.dp),contentScale=ContentScale.Fit)}}},confirmButton={TextButton(onClick=onDismiss){Text("OK")}})}

private fun directIcon(e:DeviceStorageManager.Entry)=if(e.isDirectory)Icons.Default.Folder else when(FileManagerRules.extensionOf(e.name)){"jpg","jpeg","png","webp","gif","bmp","heic","heif"->Icons.Default.Image;"pdf"->Icons.Default.PictureAsPdf;"zip","rar","7z","tar","gz"->Icons.Default.FolderZip;"mp4","mkv","avi","mov","webm"->Icons.Default.VideoFile;"mp3","wav","m4a","aac","flac","ogg"->Icons.Default.AudioFile;"txt","md","log","json","xml","csv"->Icons.Default.Description;else->Icons.Default.InsertDriveFile}
private fun directAccent(e: DeviceStorageManager.Entry): Color = when (ExplorerPremiumRules.visualKind(e.name, e.isDirectory)) {
    ExplorerVisualKind.FOLDER -> Color(0xFFE2A100)
    ExplorerVisualKind.IMAGE -> Color(0xFFFF8A3D)
    ExplorerVisualKind.PDF -> Color(0xFFE44747)
    ExplorerVisualKind.ZIP -> Color(0xFFF4B52A)
    ExplorerVisualKind.VIDEO -> Color(0xFFD6538F)
    ExplorerVisualKind.AUDIO -> Color(0xFF159B78)
    ExplorerVisualKind.TEXT -> Color(0xFF2457D6)
    ExplorerVisualKind.OTHER -> Color(0xFF68758B)
}
private fun formatDirectDate(value:Long)=if(value>0)DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(value)) else "—"
