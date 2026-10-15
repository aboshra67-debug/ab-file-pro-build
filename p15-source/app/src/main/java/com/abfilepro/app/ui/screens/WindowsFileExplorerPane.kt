@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.abfilepro.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abfilepro.app.core.DeviceStorageManager
import com.abfilepro.app.core.FileManagerRules
import com.abfilepro.app.core.SafStorageManager
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private enum class ExplorerViewMode { LIST, GRID, DETAILS }
private enum class ExplorerQuickMode { RECENT, FAVORITES }
private enum class ExplorerClipboardMode { COPY, CUT }
private data class ExplorerClipboard(val mode: ExplorerClipboardMode, val entries: List<SafStorageManager.Entry>)

@Composable
fun WindowsFileExplorerPane(
    initialDirectoryPath: String? = null,
    onBack: () -> Unit,
    onOpenLegacyWorkspace: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        var directAccess by remember { mutableStateOf(DeviceStorageManager.hasAllFilesAccess()) }
        val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            directAccess = DeviceStorageManager.hasAllFilesAccess()
        }
        if (!directAccess) {
            AllFilesAccessGate(
                onGrant = {
                    val intent = DeviceStorageManager.permissionIntent(context)
                    runCatching { allFilesLauncher.launch(intent) }
                        .onFailure { allFilesLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                },
                onBack = onBack
            )
            return
        }
        DirectFileExplorerPane(initialDirectoryPath = initialDirectoryPath, onBack = onBack, onOpenLegacyWorkspace = onOpenLegacyWorkspace)
        return
    }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var treeText by remember { mutableStateOf(SafStorageManager.savedTreeUri(context)?.toString()) }
    var permissionLost by remember { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var favoritesVersion by remember { mutableIntStateOf(0) }
    var recentVersion by remember { mutableIntStateOf(0) }
    var folderStack by remember(treeText) { mutableStateOf<List<SafStorageManager.Entry>>(emptyList()) }
    var directoryEntries by remember(treeText) { mutableStateOf<List<SafStorageManager.Entry>>(emptyList()) }
    var quickEntries by remember(treeText) { mutableStateOf<List<SafStorageManager.Entry>>(emptyList()) }
    var quickMode by remember { mutableStateOf<ExplorerQuickMode?>(null) }
    var homeMode by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var operationBusy by remember { mutableStateOf(false) }
    var operationJob by remember { mutableStateOf<Job?>(null) }

    var viewMode by remember { mutableStateOf(ExplorerViewMode.LIST) }
    var sortKey by remember { mutableStateOf(FileManagerRules.SortKey.NAME) }
    var sortAscending by remember { mutableStateOf(true) }
    var sortMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var clipboard by remember { mutableStateOf<ExplorerClipboard?>(null) }

    var createFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renameEntry by remember { mutableStateOf<SafStorageManager.Entry?>(null) }
    var renameName by remember { mutableStateOf("") }
    var deleteEntries by remember { mutableStateOf<List<SafStorageManager.Entry>>(emptyList()) }
    var zipDialog by remember { mutableStateOf(false) }
    var zipName by remember { mutableStateOf("Archive.zip") }
    var zipActionEntry by remember { mutableStateOf<SafStorageManager.Entry?>(null) }
    var previewEntry by remember { mutableStateOf<SafStorageManager.Entry?>(null) }
    var analysisDialog by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<SafStorageManager.Analysis?>(null) }

    fun toast(text: String, long: Boolean = false) {
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun clearSelection() {
        selectedUris = emptySet()
        selectionMode = false
    }

    fun toggleSelection(entry: SafStorageManager.Entry) {
        selectionMode = true
        val key = entry.uri.toString()
        selectedUris = if (key in selectedUris) selectedUris - key else selectedUris + key
    }

    fun <T> runOperation(
        refreshAfter: Boolean = true,
        task: suspend () -> T,
        success: (T) -> Unit = {}
    ) {
        if (operationBusy) return
        operationJob = scope.launch {
            operationBusy = true
            try {
                val result = withContext(Dispatchers.IO) { task() }
                success(result)
                if (refreshAfter) refreshVersion++
            } catch (_: CancellationException) {
                toast(context.uiText(language, "saf_cancelled"))
            } catch (error: Throwable) {
                if (error is SecurityException || error.cause is SecurityException) permissionLost = true
                toast(error.message?.takeIf { it.isNotBlank() } ?: context.uiText(language, "saf_operation_failed"), true)
            } finally {
                operationBusy = false
                operationJob = null
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching { SafStorageManager.persistWorkingTree(context, uri) }
                .onSuccess {
                    treeText = uri.toString()
                    permissionLost = false
                    folderStack = emptyList()
                    quickMode = null
                    homeMode = true
                    search = ""
                    clearSelection()
                    refreshVersion++
                }
                .onFailure { toast(it.message ?: context.uiText(language, "saf_permission_failed"), true) }
        }
    }

    val treeUri = treeText?.let(Uri::parse)
    val permissionState = remember(treeText, refreshVersion) {
        treeUri?.let { SafStorageManager.permissionState(context, it) } ?: SafStorageManager.PermissionState.MISSING
    }
    val canWrite = permissionState == SafStorageManager.PermissionState.READ_WRITE && !permissionLost

    LaunchedEffect(treeText, refreshVersion) {
        val tree = treeUri ?: return@LaunchedEffect
        if (permissionLost) return@LaunchedEffect
        if (SafStorageManager.permissionState(context, tree) == SafStorageManager.PermissionState.MISSING) {
            permissionLost = true
            return@LaunchedEffect
        }
        if (folderStack.isEmpty()) {
            loading = true
            try {
                val root = withContext(Dispatchers.IO) {
                    requireNotNull(SafStorageManager.queryEntry(context, SafStorageManager.rootDocumentUri(tree), null))
                }
                folderStack = listOf(root)
            } catch (_: SecurityException) {
                permissionLost = true
            } catch (error: Throwable) {
                toast(error.message ?: context.uiText(language, "saf_operation_failed"), true)
            } finally {
                loading = false
            }
        }
    }

    val currentFolder = folderStack.lastOrNull()

    LaunchedEffect(currentFolder?.uri?.toString(), refreshVersion) {
        val folder = currentFolder ?: return@LaunchedEffect
        loading = true
        try {
            directoryEntries = withContext(Dispatchers.IO) { SafStorageManager.listChildren(context, folder.uri) }
            permissionLost = false
        } catch (_: SecurityException) {
            permissionLost = true
            directoryEntries = emptyList()
        } catch (error: Throwable) {
            directoryEntries = emptyList()
            toast(error.message ?: context.uiText(language, "saf_operation_failed"), true)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(quickMode, favoritesVersion, recentVersion, refreshVersion) {
        val mode = quickMode ?: run {
            quickEntries = emptyList()
            return@LaunchedEffect
        }
        loading = true
        try {
            quickEntries = withContext(Dispatchers.IO) {
                when (mode) {
                    ExplorerQuickMode.RECENT -> SafStorageManager.recentEntries(context)
                    ExplorerQuickMode.FAVORITES -> SafStorageManager.favoriteEntries(context)
                }
            }
        } catch (_: SecurityException) {
            permissionLost = true
            quickEntries = emptyList()
        } finally {
            loading = false
        }
    }

    val sourceEntries = when {
        homeMode -> emptyList()
        quickMode == null -> directoryEntries
        else -> quickEntries
    }
    val visibleEntries = remember(sourceEntries, search, sortKey, sortAscending) {
        val query = search.trim()
        val filtered = sourceEntries.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        val byId = filtered.associateBy { it.uri.toString() }
        FileManagerRules.sortEntries(filtered.map { it.meta() }, sortKey, sortAscending).mapNotNull { byId[it.id] }
    }
    val selectedEntries = sourceEntries.filter { it.uri.toString() in selectedUris }
    val favoriteUris = remember(favoritesVersion) { SafStorageManager.favoriteUris(context) }
    val quickFolderEntries = remember(directoryEntries) {
        directoryEntries.asSequence()
            .filter { it.isDirectory }
            .mapNotNull { entry -> FileManagerRules.quickFolderKind(entry.name)?.let { it to entry } }
            .toMap()
    }

    fun openRoot() {
        homeMode = true
        quickMode = null
        search = ""
        clearSelection()
        folderStack = folderStack.firstOrNull()?.let(::listOf).orEmpty()
    }

    fun openQuick(mode: ExplorerQuickMode) {
        homeMode = false
        quickMode = mode
        search = ""
        clearSelection()
    }

    fun openQuickFolder(kind: FileManagerRules.QuickFolder) {
        val target = quickFolderEntries[kind]
        if (target == null) {
            toast(context.uiText(language, "explorer_location_unavailable"), true)
            return
        }
        homeMode = false
        quickMode = null
        search = ""
        clearSelection()
        val root = folderStack.firstOrNull()
        folderStack = if (root == null || root.uri == target.uri) listOf(target) else listOf(root, target)
    }

    fun openEntry(entry: SafStorageManager.Entry) {
        if (selectionMode) {
            toggleSelection(entry)
            return
        }
        if (entry.isDirectory) {
            homeMode = false
            quickMode = null
            folderStack = if (folderStack.lastOrNull()?.uri == entry.uri) folderStack else folderStack + entry
            search = ""
            clearSelection()
            return
        }
        SafStorageManager.recordRecent(context, entry)
        recentVersion++
        if (entry.name.endsWith(".zip", true) || entry.mimeType == "application/zip") {
            zipActionEntry = entry
            return
        }
        when (FileManagerRules.previewKind(entry.name, entry.mimeType)) {
            FileManagerRules.PreviewKind.IMAGE, FileManagerRules.PreviewKind.TEXT -> previewEntry = entry
            FileManagerRules.PreviewKind.NONE -> runCatching { SafStorageManager.open(context, entry) }
                .onFailure { toast(it.message ?: context.uiText(language, "saf_open_failed"), true) }
        }
    }

    fun goBack() {
        when {
            previewEntry != null -> previewEntry = null
            selectionMode -> clearSelection()
            quickMode != null -> { quickMode = null; homeMode = true }
            folderStack.size > 1 -> {
                folderStack = folderStack.dropLast(1)
                search = ""
            }
            !homeMode -> homeMode = true
            else -> onBack()
        }
    }

    BackHandler(onBack = ::goBack)

    if (treeUri == null || permissionLost || permissionState == SafStorageManager.PermissionState.MISSING) {
        Scaffold { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Computer, null, Modifier.size(88.dp))
                Spacer(Modifier.height(16.dp))
                Text(context.uiText(language, "explorer_title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (permissionLost) context.uiText(language, "saf_permission_lost") else context.uiText(language, "explorer_choose_desc"),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(18.dp))
                Button(onClick = { folderPicker.launch(treeUri) }) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text(context.uiText(language, "saf_choose_folder"))
                }
                Spacer(Modifier.height(10.dp))
                Text(context.uiText(language, "saf_privacy_note"), style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    fun setClipboard(mode: ExplorerClipboardMode) {
        if (selectedEntries.isEmpty()) return
        if (mode == ExplorerClipboardMode.CUT && selectedEntries.any { it.parentUri == null }) return
        clipboard = ExplorerClipboard(mode, selectedEntries.toList())
        clearSelection()
        toast(context.uiText(language, if (mode == ExplorerClipboardMode.COPY) "explorer_copied_count" else "explorer_cut_count", selectedEntries.size))
    }

    fun pasteClipboard() {
        val target = currentFolder ?: return
        val clip = clipboard ?: return
        if (!canWrite || quickMode != null || operationBusy) return
        runOperation(task = {
            var completed = 0
            var failed = 0
            for (entry in clip.entries) {
                try {
                    if (clip.mode == ExplorerClipboardMode.COPY) SafStorageManager.copy(context, entry, target)
                    else SafStorageManager.move(context, entry, target)
                    completed++
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (security: SecurityException) {
                    throw security
                } catch (_: Throwable) {
                    failed++
                }
            }
            completed to failed
        }, success = { (completed, failed) ->
            if (clip.mode == ExplorerClipboardMode.CUT) clipboard = null
            favoritesVersion++
            recentVersion++
            toast(context.uiText(language, "saf_transfer_result", completed, failed), failed > 0)
        })
    }

    if (createFolderDialog) {
        AlertDialog(
            onDismissRequest = { createFolderDialog = false },
            title = { Text(context.uiText(language, "saf_new_folder")) },
            text = { OutlinedTextField(newFolderName, { newFolderName = it }, singleLine = true) },
            confirmButton = {
                Button(enabled = canWrite && newFolderName.isNotBlank(), onClick = {
                    val folder = currentFolder ?: return@Button
                    val name = newFolderName
                    createFolderDialog = false
                    newFolderName = ""
                    runOperation(task = { SafStorageManager.createFolder(context, folder.uri, name) })
                }) { Text(context.uiText(language, "saf_create")) }
            },
            dismissButton = { TextButton(onClick = { createFolderDialog = false }) { Text(context.uiText(language, "saf_cancel")) } }
        )
    }

    renameEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { renameEntry = null },
            title = { Text(context.uiText(language, "saf_rename")) },
            text = { OutlinedTextField(renameName, { renameName = it }, singleLine = true) },
            confirmButton = {
                Button(enabled = canWrite && renameName.isNotBlank(), onClick = {
                    val name = renameName
                    renameEntry = null
                    runOperation(task = { SafStorageManager.rename(context, entry, name) })
                }) { Text(context.uiText(language, "saf_save")) }
            },
            dismissButton = { TextButton(onClick = { renameEntry = null }) { Text(context.uiText(language, "saf_cancel")) } }
        )
    }

    if (deleteEntries.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleteEntries = emptyList() },
            title = { Text(context.uiText(language, "saf_delete_title")) },
            text = { Text(context.uiText(language, "saf_delete_warning", deleteEntries.size)) },
            confirmButton = {
                Button(
                    enabled = canWrite,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val snapshot = deleteEntries
                        deleteEntries = emptyList()
                        runOperation(task = {
                            var deleted = 0
                            var failed = 0
                            snapshot.forEach { entry ->
                                try {
                                    if (SafStorageManager.delete(context, entry)) deleted++ else failed++
                                } catch (security: SecurityException) {
                                    throw security
                                } catch (_: Throwable) {
                                    failed++
                                }
                            }
                            deleted to failed
                        }, success = { (deleted, failed) ->
                            clearSelection()
                            favoritesVersion++
                            recentVersion++
                            toast(context.uiText(language, "saf_delete_result", deleted, failed), failed > 0)
                        })
                    }
                ) { Text(context.uiText(language, "saf_delete_permanent")) }
            },
            dismissButton = { TextButton(onClick = { deleteEntries = emptyList() }) { Text(context.uiText(language, "saf_cancel")) } }
        )
    }

    if (zipDialog) {
        AlertDialog(
            onDismissRequest = { zipDialog = false },
            title = { Text(context.uiText(language, "saf_zip_create")) },
            text = { OutlinedTextField(zipName, { zipName = it }, label = { Text(context.uiText(language, "saf_zip_name")) }, singleLine = true) },
            confirmButton = {
                Button(enabled = canWrite && selectedEntries.isNotEmpty() && quickMode == null, onClick = {
                    val folder = currentFolder ?: return@Button
                    val snapshot = selectedEntries.toList()
                    val name = zipName
                    zipDialog = false
                    runOperation(task = { SafStorageManager.createZip(context, folder, snapshot, name) }, success = {
                        clearSelection()
                        toast(context.uiText(language, "saf_zip_created"))
                    })
                }) { Text(context.uiText(language, "saf_create")) }
            },
            dismissButton = { TextButton(onClick = { zipDialog = false }) { Text(context.uiText(language, "saf_cancel")) } }
        )
    }

    zipActionEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { zipActionEntry = null },
            title = { Text(context.uiText(language, "explorer_zip_actions")) },
            text = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                Button(enabled = canWrite && currentFolder != null && quickMode == null, onClick = {
                    val target = currentFolder ?: return@Button
                    zipActionEntry = null
                    runOperation(task = { SafStorageManager.extractZipHere(context, entry, target) }, success = {
                        toast(context.uiText(language, "explorer_extract_here_done", it))
                    })
                }) { Text(context.uiText(language, "explorer_extract_here")) }
            },
            dismissButton = {
                Row {
                    TextButton(enabled = canWrite && currentFolder != null && quickMode == null, onClick = {
                        val target = currentFolder ?: return@TextButton
                        zipActionEntry = null
                        runOperation(task = { SafStorageManager.extractZip(context, entry, target) }, success = {
                            toast(context.uiText(language, "explorer_extract_folder_done", it.name))
                        })
                    }) { Text(context.uiText(language, "explorer_extract_folder")) }
                    TextButton(onClick = { zipActionEntry = null }) { Text(context.uiText(language, "saf_cancel")) }
                }
            }
        )
    }

    previewEntry?.let { entry ->
        ExplorerPreviewDialog(
            entry = entry,
            onDismiss = { previewEntry = null },
            onOpenExternal = {
                previewEntry = null
                runCatching { SafStorageManager.open(context, entry) }
                    .onFailure { toast(it.message ?: context.uiText(language, "saf_open_failed"), true) }
            },
            onShare = {
                runCatching { SafStorageManager.share(context, listOf(entry)) }
                    .onFailure { toast(it.message ?: context.uiText(language, "saf_share_failed"), true) }
            }
        )
    }

    if (analysisDialog) {
        AlertDialog(
            onDismissRequest = { if (!operationBusy) analysisDialog = false },
            title = { Text(context.uiText(language, "saf_analyzer")) },
            text = {
                val result = analysis
                if (operationBusy && result == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Text(context.uiText(language, "saf_analyzing"))
                        TextButton(onClick = { operationJob?.cancel() }) { Text(context.uiText(language, "saf_cancel_operation")) }
                    }
                } else if (result != null) {
                    LazyColumn(Modifier.heightIn(max = 500.dp)) {
                        item { ExplorerAnalyzerLine(context.uiText(language, "saf_total_size"), Formatter.formatFileSize(context, result.totalBytes)) }
                        item { ExplorerAnalyzerLine(context.uiText(language, "saf_files_count"), result.fileCount.toString()) }
                        item { ExplorerAnalyzerLine(context.uiText(language, "saf_folders_count"), result.folderCount.toString()) }
                        item { Spacer(Modifier.height(10.dp)); Text(context.uiText(language, "saf_large_files"), fontWeight = FontWeight.Bold) }
                        items(result.largeFiles.take(12), key = { it.entry.uri.toString() }) { file ->
                            ListItem(
                                headlineContent = { Text(file.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(Formatter.formatFileSize(context, file.exactSize)) },
                                leadingContent = { Icon(Icons.Default.InsertDriveFile, null) }
                            )
                        }
                        item { Spacer(Modifier.height(10.dp)); Text(context.uiText(language, "saf_duplicates"), fontWeight = FontWeight.Bold) }
                        if (result.duplicateGroups.isEmpty()) item { Text(context.uiText(language, "saf_no_duplicates")) }
                        items(result.duplicateGroups.take(20), key = { it.sha256 }) { group ->
                            Text(context.uiText(language, "saf_duplicate_group", group.files.size, Formatter.formatFileSize(context, group.size)))
                            HorizontalDivider()
                        }
                    }
                } else Text(context.uiText(language, "saf_analyzer_ready"))
            },
            confirmButton = { if (!operationBusy) TextButton(onClick = { analysisDialog = false }) { Text(context.uiText(language, "saf_close")) } }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                Spacer(Modifier.height(12.dp))
                Text(context.uiText(language, "explorer_title"), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                NavigationDrawerItem(
                    label = { Text(context.uiText(language, "explorer_this_device")) },
                    selected = homeMode && quickMode == null,
                    icon = { Icon(Icons.Default.Computer, null) },
                    onClick = { openRoot(); scope.launch { drawerState.close() } }
                )
                NavigationDrawerItem(
                    label = { Text(context.uiText(language, "explorer_downloads")) },
                    selected = false,
                    icon = { Icon(Icons.Default.Download, null) },
                    onClick = { openQuickFolder(FileManagerRules.QuickFolder.DOWNLOADS); scope.launch { drawerState.close() } }
                )
                NavigationDrawerItem(
                    label = { Text(context.uiText(language, "explorer_pictures")) },
                    selected = false,
                    icon = { Icon(Icons.Default.Image, null) },
                    onClick = { openQuickFolder(FileManagerRules.QuickFolder.PICTURES); scope.launch { drawerState.close() } }
                )
                NavigationDrawerItem(
                    label = { Text(context.uiText(language, "explorer_videos")) },
                    selected = false,
                    icon = { Icon(Icons.Default.VideoLibrary, null) },
                    onClick = { openQuickFolder(FileManagerRules.QuickFolder.VIDEOS); scope.launch { drawerState.close() } }
                )
                NavigationDrawerItem(
                    label = { Text(context.uiText(language, "explorer_documents")) },
                    selected = false,
                    icon = { Icon(Icons.Default.Description, null) },
                    onClick = { openQuickFolder(FileManagerRules.QuickFolder.DOCUMENTS); scope.launch { drawerState.close() } }
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text(context.uiText(language, "saf_recent")) },
                    selected = quickMode == ExplorerQuickMode.RECENT,
                    icon = { Icon(Icons.Default.History, null) },
                    onClick = { openQuick(ExplorerQuickMode.RECENT); scope.launch { drawerState.close() } }
                )
                NavigationDrawerItem(
                    label = { Text(context.uiText(language, "saf_favorites")) },
                    selected = quickMode == ExplorerQuickMode.FAVORITES,
                    icon = { Icon(Icons.Default.Star, null) },
                    onClick = { openQuick(ExplorerQuickMode.FAVORITES); scope.launch { drawerState.close() } }
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text(context.uiText(language, "explorer_ab_workspace")) },
                    selected = false,
                    icon = { Icon(Icons.Default.FolderSpecial, null) },
                    onClick = { scope.launch { drawerState.close() }; onOpenLegacyWorkspace() }
                )
                NavigationDrawerItem(
                    label = { Text(context.uiText(language, "saf_analyzer")) },
                    selected = false,
                    icon = { Icon(Icons.Default.Storage, null) },
                    onClick = {
                        scope.launch { drawerState.close() }
                        analysis = null
                        analysisDialog = true
                        runOperation(refreshAfter = false, task = { SafStorageManager.analyze(context) }, success = { analysis = it })
                    }
                )
                NavigationDrawerItem(
                    label = { Text(context.uiText(language, "saf_change_folder")) },
                    selected = false,
                    icon = { Icon(Icons.Default.DriveFileMove, null) },
                    onClick = { scope.launch { drawerState.close() }; folderPicker.launch(treeUri) }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                Surface(tonalElevation = 2.dp) {
                    Row(
                        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 58.dp).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, context.uiText(language, "explorer_menu")) }
                        IconButton(onClick = ::goBack) { Icon(Icons.Default.ArrowBack, null) }
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(54.dp)
                        ) {
                            Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(31.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    homeMode -> context.uiText(language, "explorer_this_device")
                                    quickMode == ExplorerQuickMode.RECENT -> context.uiText(language, "saf_recent")
                                    quickMode == ExplorerQuickMode.FAVORITES -> context.uiText(language, "saf_favorites")
                                    else -> currentFolder?.name ?: context.uiText(language, "explorer_this_device")
                                },
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(context.uiText(language, "explorer_windows_mode"), style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { refreshVersion++ }) { Icon(Icons.Default.Refresh, context.uiText(language, "saf_refresh")) }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!canWrite) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(context.uiText(language, "saf_readonly_note"), Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (!homeMode && quickMode == null) {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        folderStack.forEachIndexed { index, folder ->
                            item(key = folder.uri.toString()) {
                                TextButton(onClick = {
                                    folderStack = folderStack.take(index + 1)
                                    search = ""
                                    clearSelection()
                                }) {
                                    if (index == 0) Icon(Icons.Default.Computer, null, Modifier.size(16.dp))
                                    if (index == 0) Spacer(Modifier.width(4.dp))
                                    Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (index < folderStack.lastIndex) item(key = "sep-$index") { Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp)) }
                        }
                    }
                }

                if (!homeMode) {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        AssistChip(
                            enabled = canWrite && quickMode == null && !operationBusy,
                            onClick = { createFolderDialog = true },
                            label = { Text(context.uiText(language, "saf_new_folder")) },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp)) }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = {
                                if (selectionMode) clearSelection() else selectionMode = true
                            },
                            label = { Text(if (!selectionMode) context.uiText(language, "saf_select") else context.uiText(language, "saf_selection_cancel")) },
                            leadingIcon = { Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp)) }
                        )
                    }
                    if (clipboard != null && quickMode == null) item {
                        AssistChip(
                            enabled = canWrite && !operationBusy,
                            onClick = ::pasteClipboard,
                            label = { Text(context.uiText(language, "explorer_paste")) },
                            leadingIcon = { Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp)) }
                        )
                    }
                    item {
                        Box {
                            AssistChip(
                                onClick = { viewMenu = true },
                                label = { Text(context.uiText(language, when (viewMode) {
                                    ExplorerViewMode.LIST -> "saf_list"
                                    ExplorerViewMode.GRID -> "saf_grid"
                                    ExplorerViewMode.DETAILS -> "explorer_details"
                                })) },
                                leadingIcon = { Icon(Icons.Default.ViewModule, null, Modifier.size(18.dp)) }
                            )
                            DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                                DropdownMenuItem(text = { Text(context.uiText(language, "saf_list")) }, leadingIcon = { Icon(Icons.Default.ViewList, null) }, onClick = { viewMode = ExplorerViewMode.LIST; viewMenu = false })
                                DropdownMenuItem(text = { Text(context.uiText(language, "saf_grid")) }, leadingIcon = { Icon(Icons.Default.GridView, null) }, onClick = { viewMode = ExplorerViewMode.GRID; viewMenu = false })
                                DropdownMenuItem(text = { Text(context.uiText(language, "explorer_details")) }, leadingIcon = { Icon(Icons.Default.TableRows, null) }, onClick = { viewMode = ExplorerViewMode.DETAILS; viewMenu = false })
                            }
                        }
                    }
                    item {
                        Box {
                            AssistChip(onClick = { sortMenu = true }, label = { Text(context.uiText(language, "saf_sort")) }, leadingIcon = { Icon(Icons.Default.Sort, null, Modifier.size(18.dp)) })
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf(
                                    FileManagerRules.SortKey.NAME to "saf_sort_name",
                                    FileManagerRules.SortKey.DATE to "saf_sort_date",
                                    FileManagerRules.SortKey.SIZE to "saf_sort_size",
                                    FileManagerRules.SortKey.TYPE to "saf_sort_type"
                                ).forEach { (key, textKey) ->
                                    DropdownMenuItem(text = { Text(context.uiText(language, textKey)) }, leadingIcon = { if (sortKey == key) Icon(Icons.Default.Check, null) }, onClick = { sortKey = key; sortMenu = false })
                                }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(context.uiText(language, if (sortAscending) "saf_descending" else "saf_ascending")) }, leadingIcon = { Icon(if (sortAscending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null) }, onClick = { sortAscending = !sortAscending; sortMenu = false })
                            }
                        }
                    }
                }
                }

                if (!homeMode && selectionMode) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .72f)) {
                        LazyRow(
                            Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item { Text(context.uiText(language, "saf_selected_count", selectedEntries.size), fontWeight = FontWeight.Bold) }
                            item { AssistChip(onClick = { selectedUris = visibleEntries.map { it.uri.toString() }.toSet() }, label = { Text(context.uiText(language, "saf_select_all")) }, leadingIcon = { Icon(Icons.Default.DoneAll, null, Modifier.size(18.dp)) }) }
                            if (selectedEntries.isNotEmpty()) item { AssistChip(onClick = { setClipboard(ExplorerClipboardMode.COPY) }, label = { Text(context.uiText(language, "saf_copy")) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)) }) }
                            if (selectedEntries.isNotEmpty() && selectedEntries.all { it.parentUri != null }) item { AssistChip(onClick = { setClipboard(ExplorerClipboardMode.CUT) }, label = { Text(context.uiText(language, "explorer_cut")) }, leadingIcon = { Icon(Icons.Default.ContentCut, null, Modifier.size(18.dp)) }) }
                            if (selectedEntries.size == 1 && selectedEntries.first().isFile && selectedEntries.first().name.endsWith(".zip", true) && quickMode == null && canWrite) item {
                                AssistChip(onClick = { zipActionEntry = selectedEntries.first() }, label = { Text(context.uiText(language, "saf_extract")) }, leadingIcon = { Icon(Icons.Default.Unarchive, null, Modifier.size(18.dp)) })
                            }
                            if (selectedEntries.isNotEmpty() && quickMode == null && canWrite) item { AssistChip(onClick = { zipName = "Archive.zip"; zipDialog = true }, label = { Text(context.uiText(language, "saf_zip")) }, leadingIcon = { Icon(Icons.Default.FolderZip, null, Modifier.size(18.dp)) }) }
                            if (selectedEntries.any { it.isFile }) item {
                                AssistChip(onClick = { runCatching { SafStorageManager.share(context, selectedEntries) }.onFailure { toast(it.message ?: context.uiText(language, "saf_share_failed"), true) } }, label = { Text(context.uiText(language, "saf_share")) }, leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(18.dp)) })
                            }
                            if (selectedEntries.isNotEmpty() && selectedEntries.all { it.parentUri != null } && canWrite) item { AssistChip(onClick = { deleteEntries = selectedEntries }, label = { Text(context.uiText(language, "saf_delete")) }, leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)) }) }
                        }
                    }
                }

                if (!homeMode) OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    placeholder = { Text(context.uiText(language, "saf_search")) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, null) } },
                    singleLine = true
                )

                if (loading || operationBusy) LinearProgressIndicator(Modifier.fillMaxWidth())

                if (homeMode) {
                    ExplorerHomePane(
                        rootName = currentFolder?.name.orEmpty(),
                        quickFolders = quickFolderEntries,
                        canWrite = canWrite,
                        onOpenFolder = { openQuickFolder(it) },
                        onOpenRootFolder = {
                            homeMode = false
                            quickMode = null
                            search = ""
                            clearSelection()
                        },
                        onChooseRoot = { folderPicker.launch(treeUri) },
                        onOpenRecent = { openQuick(ExplorerQuickMode.RECENT) },
                        onOpenFavorites = { openQuick(ExplorerQuickMode.FAVORITES) },
                        onOpenWorkspace = onOpenLegacyWorkspace
                    )
                } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(context.uiText(language, "saf_counts", visibleEntries.count { it.isDirectory }, visibleEntries.count { it.isFile }), style = MaterialTheme.typography.bodySmall)
                    clipboard?.let { Text(context.uiText(language, "explorer_clipboard_count", it.entries.size), style = MaterialTheme.typography.bodySmall) }
                }

                if (!loading && visibleEntries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(72.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(context.uiText(language, "saf_empty"))
                        }
                    }
                } else when (viewMode) {
                    ExplorerViewMode.LIST -> LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        contentPadding = PaddingValues(bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(visibleEntries, key = { it.uri.toString() }) { entry ->
                            ExplorerListRow(
                                entry = entry,
                                selected = entry.uri.toString() in selectedUris,
                                selectionActive = selectionMode,
                                favorite = entry.uri.toString() in favoriteUris,
                                canWrite = canWrite,
                                canModifyHere = entry.parentUri != null,
                                onOpen = { openEntry(entry) },
                                onLongSelect = { toggleSelection(entry) },
                                onToggleSelect = { toggleSelection(entry) },
                                onFavorite = { SafStorageManager.toggleFavorite(context, entry); favoritesVersion++ },
                                onCopy = { clipboard = ExplorerClipboard(ExplorerClipboardMode.COPY, listOf(entry)); toast(context.uiText(language, "explorer_copied_count", 1)) },
                                onCut = { if (entry.parentUri != null) { clipboard = ExplorerClipboard(ExplorerClipboardMode.CUT, listOf(entry)); toast(context.uiText(language, "explorer_cut_count", 1)) } },
                                onRename = { renameEntry = entry; renameName = entry.name },
                                onDelete = { deleteEntries = listOf(entry) },
                                onShare = { runCatching { SafStorageManager.share(context, listOf(entry)) }.onFailure { toast(it.message ?: context.uiText(language, "saf_share_failed"), true) } },
                                onExtract = { zipActionEntry = entry }
                            )
                        }
                    }
                    ExplorerViewMode.GRID -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(128.dp),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        contentPadding = PaddingValues(bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        gridItems(visibleEntries, key = { it.uri.toString() }) { entry ->
                            ExplorerGridCard(
                                entry = entry,
                                selected = entry.uri.toString() in selectedUris,
                                favorite = entry.uri.toString() in favoriteUris,
                                selectionActive = selectionMode,
                                onOpen = { openEntry(entry) },
                                onLongSelect = { toggleSelection(entry) },
                                onToggleSelect = { toggleSelection(entry) },
                                onFavorite = { SafStorageManager.toggleFavorite(context, entry); favoritesVersion++ }
                            )
                        }
                    }
                    ExplorerViewMode.DETAILS -> LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        contentPadding = PaddingValues(bottom = 40.dp)
                    ) {
                        item { ExplorerDetailsHeader() }
                        items(visibleEntries, key = { it.uri.toString() }) { entry ->
                            ExplorerDetailsRow(
                                entry = entry,
                                selected = entry.uri.toString() in selectedUris,
                                selectionActive = selectionMode,
                                onOpen = { openEntry(entry) },
                                onLongSelect = { toggleSelection(entry) },
                                onToggleSelect = { toggleSelection(entry) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun ExplorerHomePane(
    rootName: String,
    quickFolders: Map<FileManagerRules.QuickFolder, SafStorageManager.Entry>,
    canWrite: Boolean,
    onOpenFolder: (FileManagerRules.QuickFolder) -> Unit,
    onOpenRootFolder: () -> Unit,
    onChooseRoot: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenWorkspace: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    val hasDeviceFolders = quickFolders.keys.any {
        it in setOf(
            FileManagerRules.QuickFolder.DOWNLOADS,
            FileManagerRules.QuickFolder.PICTURES,
            FileManagerRules.QuickFolder.VIDEOS,
            FileManagerRules.QuickFolder.DOCUMENTS,
            FileManagerRules.QuickFolder.DCIM
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(context.uiText(language, "explorer_this_device"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(onClick = onOpenRootFolder, Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(context.uiText(language, "explorer_internal_storage"), fontWeight = FontWeight.Bold)
                    Text(rootName.ifBlank { context.uiText(language, "explorer_storage_root") }, style = MaterialTheme.typography.bodySmall)
                    Text(
                        context.uiText(language, if (canWrite) "explorer_access_read_write" else "explorer_access_read_only"),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Icon(Icons.Default.ChevronRight, null)
            }
        }
        FilledTonalButton(onClick = onChooseRoot, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text(context.uiText(language, "explorer_choose_internal_root"))
        }
        if (!hasDeviceFolders) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null)
                    Spacer(Modifier.width(8.dp))
                    Text(context.uiText(language, "explorer_root_hint"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text(context.uiText(language, "explorer_quick_access"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExplorerHomeShortcut(Modifier.weight(1f), Icons.Default.Download, context.uiText(language, "explorer_downloads"), quickFolders.containsKey(FileManagerRules.QuickFolder.DOWNLOADS)) { onOpenFolder(FileManagerRules.QuickFolder.DOWNLOADS) }
            ExplorerHomeShortcut(Modifier.weight(1f), Icons.Default.Image, context.uiText(language, "explorer_pictures"), quickFolders.containsKey(FileManagerRules.QuickFolder.PICTURES)) { onOpenFolder(FileManagerRules.QuickFolder.PICTURES) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExplorerHomeShortcut(Modifier.weight(1f), Icons.Default.VideoLibrary, context.uiText(language, "explorer_videos"), quickFolders.containsKey(FileManagerRules.QuickFolder.VIDEOS)) { onOpenFolder(FileManagerRules.QuickFolder.VIDEOS) }
            ExplorerHomeShortcut(Modifier.weight(1f), Icons.Default.Description, context.uiText(language, "explorer_documents"), quickFolders.containsKey(FileManagerRules.QuickFolder.DOCUMENTS)) { onOpenFolder(FileManagerRules.QuickFolder.DOCUMENTS) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExplorerHomeShortcut(Modifier.weight(1f), Icons.Default.PhotoCamera, context.uiText(language, "explorer_camera"), quickFolders.containsKey(FileManagerRules.QuickFolder.DCIM)) { onOpenFolder(FileManagerRules.QuickFolder.DCIM) }
            ExplorerHomeShortcut(Modifier.weight(1f), Icons.Default.MusicNote, context.uiText(language, "explorer_music"), quickFolders.containsKey(FileManagerRules.QuickFolder.MUSIC)) { onOpenFolder(FileManagerRules.QuickFolder.MUSIC) }
        }

        HorizontalDivider()
        Text(context.uiText(language, "explorer_navigation"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExplorerHomeShortcut(Modifier.weight(1f), Icons.Default.History, context.uiText(language, "saf_recent"), true, onOpenRecent)
            ExplorerHomeShortcut(Modifier.weight(1f), Icons.Default.Star, context.uiText(language, "saf_favorites"), true, onOpenFavorites)
        }
        ExplorerHomeShortcut(Modifier.fillMaxWidth(), Icons.Default.FolderSpecial, context.uiText(language, "explorer_ab_workspace"), true, onOpenWorkspace)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .55f))) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderZip, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(context.uiText(language, "explorer_zip_ready"), fontWeight = FontWeight.Bold)
                    Text(context.uiText(language, "explorer_zip_hint"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ExplorerHomeShortcut(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    available: Boolean,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(30.dp), tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!available) Text("—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun ExplorerListRow(
    entry: SafStorageManager.Entry,
    selected: Boolean,
    selectionActive: Boolean,
    favorite: Boolean,
    canWrite: Boolean,
    canModifyHere: Boolean,
    onOpen: () -> Unit,
    onLongSelect: () -> Unit,
    onToggleSelect: () -> Unit,
    onFavorite: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onExtract: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    var menu by remember { mutableStateOf(false) }
    val isZip = entry.isFile && (entry.name.endsWith(".zip", true) || entry.mimeType == "application/zip")
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = { if (selectionActive) onToggleSelect() else onOpen() }, onLongClick = onLongSelect)
    ) {
        ListItem(
            headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal) },
            supportingContent = {
                Text(
                    if (entry.isDirectory) context.uiText(language, "saf_folder")
                    else "${Formatter.formatFileSize(context, entry.size.coerceAtLeast(0L))} • ${formatExplorerDate(entry.lastModified)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingContent = {
                if (selectionActive) Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                else Icon(explorerIcon(entry), null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, null) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(context.uiText(language, "saf_open")) }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) }, onClick = { menu = false; onOpen() })
                            DropdownMenuItem(text = { Text(context.uiText(language, "saf_select")) }, leadingIcon = { Icon(Icons.Default.CheckCircle, null) }, onClick = { menu = false; onLongSelect() })
                            DropdownMenuItem(text = { Text(context.uiText(language, "saf_copy")) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; onCopy() })
                            if (canWrite && canModifyHere) DropdownMenuItem(text = { Text(context.uiText(language, "explorer_cut")) }, leadingIcon = { Icon(Icons.Default.ContentCut, null) }, onClick = { menu = false; onCut() })
                            if (entry.isFile) DropdownMenuItem(text = { Text(context.uiText(language, "saf_share")) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; onShare() })
                            if (canWrite && canModifyHere) DropdownMenuItem(text = { Text(context.uiText(language, "saf_rename")) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onRename() })
                            if (canWrite && isZip) DropdownMenuItem(text = { Text(context.uiText(language, "saf_extract")) }, leadingIcon = { Icon(Icons.Default.Unarchive, null) }, onClick = { menu = false; onExtract() })
                            if (canWrite && canModifyHere) DropdownMenuItem(text = { Text(context.uiText(language, "saf_delete")) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun ExplorerGridCard(
    entry: SafStorageManager.Entry,
    selected: Boolean,
    favorite: Boolean,
    selectionActive: Boolean,
    onOpen: () -> Unit,
    onLongSelect: () -> Unit,
    onToggleSelect: () -> Unit,
    onFavorite: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = { if (selectionActive) onToggleSelect() else onOpen() }, onLongClick = onLongSelect)
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (selectionActive) Checkbox(checked = selected, onCheckedChange = { onToggleSelect() }) else Spacer(Modifier.size(40.dp))
                IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, null) }
            }
            Icon(explorerIcon(entry), null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            if (entry.isFile) Text(Formatter.formatFileSize(context, entry.size.coerceAtLeast(0L)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ExplorerDetailsHeader() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(context.uiText(language, "saf_sort_name"), Modifier.weight(2.2f), fontWeight = FontWeight.Bold)
        Text(context.uiText(language, "saf_sort_type"), Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text(context.uiText(language, "saf_sort_size"), Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text(context.uiText(language, "saf_sort_date"), Modifier.weight(1.4f), fontWeight = FontWeight.Bold)
    }
    HorizontalDivider()
}

@Composable
private fun ExplorerDetailsRow(
    entry: SafStorageManager.Entry,
    selected: Boolean,
    selectionActive: Boolean,
    onOpen: () -> Unit,
    onLongSelect: () -> Unit,
    onToggleSelect: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = { if (selectionActive) onToggleSelect() else onOpen() }, onLongClick = onLongSelect).padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionActive) Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
        Icon(explorerIcon(entry), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(entry.name, Modifier.weight(2.2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (entry.isDirectory) context.uiText(language, "saf_folder") else FileManagerRules.extensionOf(entry.name).uppercase().ifBlank { context.uiText(language, "explorer_file") }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (entry.isDirectory) "—" else Formatter.formatFileSize(context, entry.size.coerceAtLeast(0L)), Modifier.weight(1f), maxLines = 1)
        Text(formatExplorerDate(entry.lastModified), Modifier.weight(1.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ExplorerPreviewDialog(
    entry: SafStorageManager.Entry,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    var loading by remember(entry.uri) { mutableStateOf(true) }
    var image by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }
    var text by remember(entry.uri) { mutableStateOf<String?>(null) }
    var error by remember(entry.uri) { mutableStateOf<String?>(null) }

    val bitmapOwnedByEffect = image
    DisposableEffect(bitmapOwnedByEffect) {
        onDispose { bitmapOwnedByEffect?.takeIf { !it.isRecycled }?.recycle() }
    }

    LaunchedEffect(entry.uri, entry.lastModified) {
        loading = true
        image = null
        text = null
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                when (FileManagerRules.previewKind(entry.name, entry.mimeType)) {
                    FileManagerRules.PreviewKind.IMAGE -> Pair(SafStorageManager.loadImagePreview(context, entry, 1280), null)
                    FileManagerRules.PreviewKind.TEXT -> Pair(null, SafStorageManager.readTextPreview(context, entry, 160 * 1024))
                    FileManagerRules.PreviewKind.NONE -> throw IllegalStateException("unsupported")
                }
            }
        }
        result.onSuccess { (bitmap, previewText) ->
            image = bitmap
            text = previewText
            if (bitmap == null && previewText == null) error = context.uiText(language, "explorer_preview_error")
        }.onFailure { error = context.uiText(language, "explorer_preview_error") }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 560.dp), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator()
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    image != null -> Image(image!!.asImageBitmap(), entry.name, Modifier.fillMaxWidth().heightIn(max = 520.dp), contentScale = ContentScale.Fit)
                    text != null -> Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { SelectionContainer { Text(text.orEmpty()) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onOpenExternal) { Text(context.uiText(language, "explorer_open_external")) } },
        dismissButton = {
            Row {
                TextButton(onClick = onShare) { Text(context.uiText(language, "saf_share")) }
                TextButton(onClick = onDismiss) { Text(context.uiText(language, "saf_close")) }
            }
        }
    )
}

private fun explorerIcon(entry: SafStorageManager.Entry): ImageVector {
    if (entry.isDirectory) return Icons.Default.Folder
    return when (FileManagerRules.extensionOf(entry.name)) {
        "png", "jpg", "jpeg", "webp", "bmp", "gif" -> Icons.Default.Image
        "pdf" -> Icons.Default.PictureAsPdf
        "zip", "rar", "7z" -> Icons.Default.FolderZip
        "doc", "docx", "txt", "md", "json", "xml", "log" -> Icons.Default.Description
        "xls", "xlsx", "csv" -> Icons.Default.TableChart
        else -> Icons.Default.InsertDriveFile
    }
}

private fun formatExplorerDate(lastModified: Long): String = if (lastModified > 0L) {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastModified))
} else "—"

@Composable
private fun ExplorerAnalyzerLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.Bold)
    }
}
