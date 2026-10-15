package com.abfilepro.app.ui.screens

import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abfilepro.app.core.FileManagerRules
import com.abfilepro.app.core.SafStorageManager
import com.abfilepro.app.ui.components.ScreenTopBar
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private enum class SafViewMode { LIST, GRID }
private enum class SafQuickMode { RECENT, FAVORITES }
private enum class SafTransferMode { COPY, MOVE }

@Composable
fun SafFileManagerPane(onBackToWorkspace: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()

    var treeText by remember { mutableStateOf(SafStorageManager.savedTreeUri(context)?.toString()) }
    var permissionLost by remember { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var favoritesVersion by remember { mutableIntStateOf(0) }
    var recentVersion by remember { mutableIntStateOf(0) }
    var viewMode by remember { mutableStateOf(SafViewMode.LIST) }
    var sortKey by remember { mutableStateOf(FileManagerRules.SortKey.NAME) }
    var sortAscending by remember { mutableStateOf(true) }
    var sortMenu by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var quickMode by remember { mutableStateOf<SafQuickMode?>(null) }

    var folderStack by remember(treeText) { mutableStateOf<List<SafStorageManager.Entry>>(emptyList()) }
    var directoryEntries by remember(treeText) { mutableStateOf<List<SafStorageManager.Entry>>(emptyList()) }
    var quickEntries by remember(treeText) { mutableStateOf<List<SafStorageManager.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var operationBusy by remember { mutableStateOf(false) }

    var createFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renameEntry by remember { mutableStateOf<SafStorageManager.Entry?>(null) }
    var renameName by remember { mutableStateOf("") }
    var deleteEntries by remember { mutableStateOf<List<SafStorageManager.Entry>>(emptyList()) }
    var zipDialog by remember { mutableStateOf(false) }
    var zipName by remember { mutableStateOf("Archive.zip") }
    var transferMode by remember { mutableStateOf<SafTransferMode?>(null) }
    var transferEntries by remember { mutableStateOf<List<SafStorageManager.Entry>>(emptyList()) }
    var destinationDialog by remember { mutableStateOf(false) }
    var destinationFolders by remember { mutableStateOf<List<SafStorageManager.FolderChoice>>(emptyList()) }
    var destinationBusy by remember { mutableStateOf(false) }
    var analysisDialog by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<SafStorageManager.Analysis?>(null) }

    fun toast(text: String, long: Boolean = false) {
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun clearSelection() {
        selectionMode = false
        selectedUris = emptySet()
    }

    fun cancelOperation() {
        operationJob?.cancel()
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
                toast(error.message?.takeIf { it.isNotBlank() } ?: context.uiText(language, "saf_operation_failed"), true)
                if (error is SecurityException || error.cause is SecurityException) permissionLost = true
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
                    clearSelection()
                    quickMode = null
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
        if (treeUri == null || permissionLost) return@LaunchedEffect
        if (SafStorageManager.permissionState(context, treeUri) == SafStorageManager.PermissionState.MISSING) {
            permissionLost = true
            return@LaunchedEffect
        }
        if (folderStack.isEmpty()) {
            loading = true
            try {
                val root = withContext(Dispatchers.IO) {
                    val rootUri = SafStorageManager.rootDocumentUri(treeUri)
                    requireNotNull(SafStorageManager.queryEntry(context, rootUri, null))
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
                    SafQuickMode.FAVORITES -> SafStorageManager.favoriteEntries(context)
                    SafQuickMode.RECENT -> SafStorageManager.recentEntries(context)
                }
            }
        } catch (_: SecurityException) {
            permissionLost = true
            quickEntries = emptyList()
        } finally {
            loading = false
        }
    }

    fun goBack() {
        when {
            quickMode != null -> {
                quickMode = null
                clearSelection()
            }
            folderStack.size > 1 -> {
                folderStack = folderStack.dropLast(1)
                search = ""
                clearSelection()
            }
            else -> onBackToWorkspace()
        }
    }

    BackHandler(onBack = ::goBack)

    if (treeUri == null || permissionLost || permissionState == SafStorageManager.PermissionState.MISSING) {
        Scaffold(
            topBar = { ScreenTopBar(context.uiText(language, "saf_title"), onBackToWorkspace) }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(84.dp))
                Spacer(Modifier.height(18.dp))
                Text(
                    if (permissionLost) context.uiText(language, "saf_permission_lost") else context.uiText(language, "saf_choose_desc"),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(18.dp))
                Button(onClick = { folderPicker.launch(treeUri) }) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text(context.uiText(language, "saf_choose_folder"))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    context.uiText(language, "saf_privacy_note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)
                )
            }
        }
        return
    }

    val sourceEntries = if (quickMode == null) directoryEntries else quickEntries
    val visibleEntries = remember(sourceEntries, search, sortKey, sortAscending) {
        val filtered = sourceEntries.filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }
        val byId = filtered.associateBy { it.uri.toString() }
        FileManagerRules.sortEntries(filtered.map { it.meta() }, sortKey, sortAscending)
            .mapNotNull { byId[it.id] }
    }
    val selectedEntries = sourceEntries.filter { it.uri.toString() in selectedUris }

    fun toggleSelection(entry: SafStorageManager.Entry) {
        selectionMode = true
        val key = entry.uri.toString()
        selectedUris = if (key in selectedUris) selectedUris - key else selectedUris + key
        if (selectedUris.isEmpty()) selectionMode = false
    }

    fun openEntry(entry: SafStorageManager.Entry) {
        if (selectionMode) {
            toggleSelection(entry)
            return
        }
        if (entry.isDirectory) {
            quickMode = null
            folderStack = folderStack + entry
            search = ""
            clearSelection()
        } else {
            runCatching { SafStorageManager.open(context, entry) }
                .onSuccess { recentVersion++ }
                .onFailure { toast(it.message ?: context.uiText(language, "saf_open_failed"), true) }
        }
    }

    fun prepareTransfer(entries: List<SafStorageManager.Entry>, mode: SafTransferMode) {
        if (entries.isEmpty()) return
        transferEntries = entries
        transferMode = mode
        destinationDialog = true
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
                            for (entry in snapshot) {
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
                Button(enabled = canWrite && selectedEntries.isNotEmpty(), onClick = {
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

    if (destinationDialog) {
        LaunchedEffect(destinationDialog, refreshVersion) {
            destinationBusy = true
            try {
                destinationFolders = withContext(Dispatchers.IO) { SafStorageManager.listFolders(context) }
            } catch (_: SecurityException) {
                permissionLost = true
                destinationFolders = emptyList()
            } catch (error: Throwable) {
                toast(error.message ?: context.uiText(language, "saf_operation_failed"), true)
            } finally {
                destinationBusy = false
            }
        }
        AlertDialog(
            onDismissRequest = { if (!operationBusy) destinationDialog = false },
            title = { Text(context.uiText(language, "saf_destination")) },
            text = {
                if (destinationBusy) {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    LazyColumn(Modifier.heightIn(max = 440.dp)) {
                        items(destinationFolders, key = { it.entry.uri.toString() }) { choice ->
                            ListItem(
                                headlineContent = { Text(choice.entry.name) },
                                supportingContent = { Text(choice.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Icon(Icons.Default.Folder, null) },
                                modifier = Modifier.clickable(enabled = canWrite && !operationBusy) {
                                    val snapshot = transferEntries.toList()
                                    val mode = transferMode
                                    if (mode != null) {
                                        destinationDialog = false
                                        runOperation(task = {
                                            var completed = 0
                                            var failed = 0
                                            for (source in snapshot) {
                                                try {
                                                    if (mode == SafTransferMode.COPY) SafStorageManager.copy(context, source, choice.entry)
                                                    else SafStorageManager.move(context, source, choice.entry)
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
                                        clearSelection()
                                        transferEntries = emptyList()
                                        transferMode = null
                                        favoritesVersion++
                                        recentVersion++
                                            toast(context.uiText(language, "saf_transfer_result", completed, failed), failed > 0)
                                        })
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(enabled = !operationBusy, onClick = { destinationDialog = false }) { Text(context.uiText(language, "saf_cancel")) } }
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
                        Spacer(Modifier.height(12.dp))
                        Text(context.uiText(language, "saf_analyzing"))
                        TextButton(onClick = ::cancelOperation) { Text(context.uiText(language, "saf_cancel_operation")) }
                    }
                } else if (result != null) {
                    LazyColumn(Modifier.heightIn(max = 520.dp)) {
                        item { AnalyzerLine(context.uiText(language, "saf_total_size"), Formatter.formatFileSize(context, result.totalBytes)) }
                        item { AnalyzerLine(context.uiText(language, "saf_files_count"), result.fileCount.toString()) }
                        item { AnalyzerLine(context.uiText(language, "saf_folders_count"), result.folderCount.toString()) }
                        item { Spacer(Modifier.height(10.dp)); Text(context.uiText(language, "saf_large_files"), fontWeight = FontWeight.Bold) }
                        if (result.largeFiles.isEmpty()) item { Text(context.uiText(language, "saf_no_large_files")) }
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
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(context.uiText(language, "saf_duplicate_group", group.files.size, Formatter.formatFileSize(context, group.size)), fontWeight = FontWeight.SemiBold)
                                group.files.take(6).forEach { Text("• ${it.path}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            HorizontalDivider()
                        }
                    }
                } else {
                    Text(context.uiText(language, "saf_analyzer_ready"))
                }
            },
            confirmButton = {
                if (!operationBusy) TextButton(onClick = { analysisDialog = false }) { Text(context.uiText(language, "saf_close")) }
            },
            dismissButton = {}
        )
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = quickMode?.let {
                    if (it == SafQuickMode.RECENT) context.uiText(language, "saf_recent") else context.uiText(language, "saf_favorites")
                } ?: currentFolder?.name ?: context.uiText(language, "saf_title"),
                onBack = ::goBack
            ) {
                IconButton(onClick = { folderPicker.launch(treeUri) }) { Icon(Icons.Default.DriveFileMove, context.uiText(language, "saf_change_folder")) }
                IconButton(onClick = { refreshVersion++ }) { Icon(Icons.Default.Refresh, context.uiText(language, "saf_refresh")) }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!canWrite) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(context.uiText(language, "saf_readonly_note"), Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            Text(
                if (quickMode == null) folderStack.joinToString(" / ") { it.name } else context.uiText(language, "saf_quick_view"),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    AssistChip(
                        enabled = canWrite && !operationBusy && quickMode == null,
                        onClick = { createFolderDialog = true },
                        label = { Text(context.uiText(language, "saf_new_folder")) },
                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp)) }
                    )
                }
                item {
                    AssistChip(
                        onClick = { viewMode = if (viewMode == SafViewMode.LIST) SafViewMode.GRID else SafViewMode.LIST },
                        label = { Text(if (viewMode == SafViewMode.LIST) context.uiText(language, "saf_grid") else context.uiText(language, "saf_list")) },
                        leadingIcon = { Icon(if (viewMode == SafViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList, null, Modifier.size(18.dp)) }
                    )
                }
                item {
                    Box {
                        AssistChip(
                            onClick = { sortMenu = true },
                            label = { Text(context.uiText(language, "saf_sort")) },
                            leadingIcon = { Icon(Icons.Default.Sort, null, Modifier.size(18.dp)) }
                        )
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf(
                                FileManagerRules.SortKey.NAME to "saf_sort_name",
                                FileManagerRules.SortKey.DATE to "saf_sort_date",
                                FileManagerRules.SortKey.SIZE to "saf_sort_size",
                                FileManagerRules.SortKey.TYPE to "saf_sort_type"
                            ).forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(context.uiText(language, label)) },
                                    leadingIcon = { if (sortKey == key) Icon(Icons.Default.Check, null) },
                                    onClick = { sortKey = key; sortMenu = false }
                                )
                            }
                        }
                    }
                }
                item {
                    AssistChip(
                        onClick = { sortAscending = !sortAscending },
                        label = { Text(if (sortAscending) context.uiText(language, "saf_ascending") else context.uiText(language, "saf_descending")) },
                        leadingIcon = { Icon(if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, Modifier.size(18.dp)) }
                    )
                }
                item {
                    FilterChip(
                        selected = quickMode == SafQuickMode.RECENT,
                        onClick = { quickMode = if (quickMode == SafQuickMode.RECENT) null else SafQuickMode.RECENT; clearSelection() },
                        label = { Text(context.uiText(language, "saf_recent")) },
                        leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(18.dp)) }
                    )
                }
                item {
                    FilterChip(
                        selected = quickMode == SafQuickMode.FAVORITES,
                        onClick = { quickMode = if (quickMode == SafQuickMode.FAVORITES) null else SafQuickMode.FAVORITES; clearSelection() },
                        label = { Text(context.uiText(language, "saf_favorites")) },
                        leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(18.dp)) }
                    )
                }
                item {
                    AssistChip(
                        enabled = !operationBusy,
                        onClick = {
                            analysis = null
                            analysisDialog = true
                            runOperation(refreshAfter = false, task = { SafStorageManager.analyze(context) }, success = { analysis = it })
                        },
                        label = { Text(context.uiText(language, "saf_analyzer")) },
                        leadingIcon = { Icon(Icons.Default.Storage, null, Modifier.size(18.dp)) }
                    )
                }
                item {
                    AssistChip(
                        onClick = { if (selectionMode) clearSelection() else selectionMode = true },
                        label = { Text(if (selectionMode) context.uiText(language, "saf_selection_cancel") else context.uiText(language, "saf_select")) },
                        leadingIcon = { Icon(if (selectionMode) Icons.Default.Close else Icons.Default.CheckCircle, null, Modifier.size(18.dp)) }
                    )
                }
            }

            if (selectionMode) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f)) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item { Text(context.uiText(language, "saf_selected_count", selectedEntries.size), fontWeight = FontWeight.Bold) }
                        item {
                            AssistChip(onClick = {
                                selectedUris = visibleEntries.map { it.uri.toString() }.toSet()
                                selectionMode = selectedUris.isNotEmpty()
                            }, label = { Text(context.uiText(language, "saf_select_all")) })
                        }
                        if (selectedEntries.any { it.isFile }) item {
                            AssistChip(onClick = {
                                runCatching { SafStorageManager.share(context, selectedEntries) }
                                    .onFailure { toast(it.message ?: context.uiText(language, "saf_share_failed"), true) }
                            }, label = { Text(context.uiText(language, "saf_share")) }, leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(18.dp)) })
                        }
                        if (selectedEntries.isNotEmpty() && canWrite) {
                            item { AssistChip(onClick = { prepareTransfer(selectedEntries, SafTransferMode.COPY) }, label = { Text(context.uiText(language, "saf_copy")) }, leadingIcon = { Icon(Icons.Default.FileCopy, null, Modifier.size(18.dp)) }) }
                            if (selectedEntries.all { it.parentUri != null }) {
                                item { AssistChip(onClick = { prepareTransfer(selectedEntries, SafTransferMode.MOVE) }, label = { Text(context.uiText(language, "saf_move")) }, leadingIcon = { Icon(Icons.Default.DriveFileMove, null, Modifier.size(18.dp)) }) }
                                item { AssistChip(onClick = { deleteEntries = selectedEntries }, label = { Text(context.uiText(language, "saf_delete")) }, leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)) }) }
                            }
                            if (quickMode == null) item { AssistChip(onClick = { zipName = "Archive.zip"; zipDialog = true }, label = { Text(context.uiText(language, "saf_zip")) }, leadingIcon = { Icon(Icons.Default.FolderZip, null, Modifier.size(18.dp)) }) }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = { Text(context.uiText(language, "saf_search")) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, null) } },
                singleLine = true
            )

            if (operationBusy || loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (operationBusy) {
                    TextButton(onClick = ::cancelOperation, modifier = Modifier.align(Alignment.End)) { Text(context.uiText(language, "saf_cancel_operation")) }
                }
            }

            Text(
                context.uiText(language, "saf_counts", visibleEntries.count { it.isDirectory }, visibleEntries.count { it.isFile }),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )

            if (!loading && visibleEntries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(72.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(context.uiText(language, "saf_empty"))
                    }
                }
            } else if (viewMode == SafViewMode.LIST) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    contentPadding = PaddingValues(bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(visibleEntries, key = { it.uri.toString() }) { entry ->
                        SafListRow(
                            entry = entry,
                            selected = entry.uri.toString() in selectedUris,
                            selectionMode = selectionMode,
                            favorite = SafStorageManager.isFavorite(context, entry),
                            canWrite = canWrite,
                            onOpen = { openEntry(entry) },
                            onSelect = { toggleSelection(entry) },
                            onFavorite = { SafStorageManager.toggleFavorite(context, entry); favoritesVersion++ },
                            onShare = { runCatching { SafStorageManager.share(context, listOf(entry)) }.onFailure { toast(it.message ?: context.uiText(language, "saf_share_failed"), true) } },
                            onCopy = { prepareTransfer(listOf(entry), SafTransferMode.COPY) },
                            onMove = { prepareTransfer(listOf(entry), SafTransferMode.MOVE) },
                            onRename = { renameEntry = entry; renameName = entry.name },
                            onExtract = {
                                val destination = currentFolder
                                if (destination != null) {
                                    runOperation(
                                        task = { SafStorageManager.extractZip(context, entry, destination) },
                                        success = { toast(context.uiText(language, "saf_extract_done")) }
                                    )
                                }
                            },
                            onDelete = { deleteEntries = listOf(entry) }
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    contentPadding = PaddingValues(bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    gridItems(visibleEntries, key = { it.uri.toString() }) { entry ->
                        SafGridCard(
                            entry = entry,
                            selected = entry.uri.toString() in selectedUris,
                            selectionMode = selectionMode,
                            favorite = SafStorageManager.isFavorite(context, entry),
                            onOpen = { openEntry(entry) },
                            onSelect = { toggleSelection(entry) },
                            onFavorite = { SafStorageManager.toggleFavorite(context, entry); favoritesVersion++ }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SafListRow(
    entry: SafStorageManager.Entry,
    selected: Boolean,
    selectionMode: Boolean,
    favorite: Boolean,
    canWrite: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onExtract: () -> Unit,
    onDelete: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    var menu by remember { mutableStateOf(false) }
    val isZip = entry.isFile && (entry.name.endsWith(".zip", true) || entry.mimeType == "application/zip")

    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        ListItem(
            headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal) },
            supportingContent = {
                Text(
                    if (entry.isDirectory) context.uiText(language, "saf_folder")
                    else "${Formatter.formatFileSize(context, entry.size.coerceAtLeast(0L))} • ${if (entry.lastModified > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.lastModified)) else "—"}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingContent = {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onSelect() })
                } else {
                    Icon(if (entry.isDirectory) Icons.Default.Folder else if (isZip) Icons.Default.FolderZip else Icons.Default.InsertDriveFile, null)
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, null) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (entry.isFile) DropdownMenuItem(text = { Text(context.uiText(language, "saf_open")) }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) }, onClick = { menu = false; onOpen() })
                            if (entry.isFile) DropdownMenuItem(text = { Text(context.uiText(language, "saf_share")) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; onShare() })
                            DropdownMenuItem(text = { Text(context.uiText(language, "saf_copy")) }, leadingIcon = { Icon(Icons.Default.FileCopy, null) }, onClick = { menu = false; onCopy() })
                            if (canWrite && entry.parentUri != null) DropdownMenuItem(text = { Text(context.uiText(language, "saf_move")) }, leadingIcon = { Icon(Icons.Default.DriveFileMove, null) }, onClick = { menu = false; onMove() })
                            if (canWrite && entry.parentUri != null) DropdownMenuItem(text = { Text(context.uiText(language, "saf_rename")) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onRename() })
                            if (canWrite && isZip) DropdownMenuItem(text = { Text(context.uiText(language, "saf_extract")) }, leadingIcon = { Icon(Icons.Default.Unarchive, null) }, onClick = { menu = false; onExtract() })
                            if (canWrite && entry.parentUri != null) DropdownMenuItem(text = { Text(context.uiText(language, "saf_delete")) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun SafGridCard(
    entry: SafStorageManager.Entry,
    selected: Boolean,
    selectionMode: Boolean,
    favorite: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onFavorite: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isZip = entry.isFile && (entry.name.endsWith(".zip", true) || entry.mimeType == "application/zip")
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) Checkbox(checked = selected, onCheckedChange = { onSelect() }) else Spacer(Modifier.size(48.dp))
                IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, null) }
            }
            Icon(if (entry.isDirectory) Icons.Default.Folder else if (isZip) Icons.Default.FolderZip else Icons.Default.InsertDriveFile, null, Modifier.size(54.dp))
            Spacer(Modifier.height(8.dp))
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            if (entry.isFile) Text(Formatter.formatFileSize(context, entry.size.coerceAtLeast(0L)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AnalyzerLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.Bold)
    }
}
