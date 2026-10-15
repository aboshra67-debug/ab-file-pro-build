package com.abfilepro.app.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abfilepro.app.core.FileUtils
import com.abfilepro.app.core.PdfTools
import com.abfilepro.app.core.FileTagStore
import com.abfilepro.app.core.FileUndoStore
import com.abfilepro.app.core.TrashStore
import com.abfilepro.app.core.WorkspaceSearchEngine
import com.abfilepro.app.ui.components.ScreenTopBar
import com.abfilepro.app.ui.i18n.AppLanguage
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private enum class FileFilter {
    ALL, PDF, IMAGES, OFFICE, TEXT, FAVORITES;
    fun titleKey(): String = when (this) {
        ALL -> "b2_files_filter_all"
        PDF -> "b2_files_filter_pdf"
        IMAGES -> "b2_files_filter_images"
        OFFICE -> "b2_files_filter_office"
        TEXT -> "b2_files_filter_text"
        FAVORITES -> "b2_files_filter_favorites"
    }
}

private enum class FileSort {
    DATE, NAME, SIZE, TYPE;
    fun titleKey(): String = when (this) {
        DATE -> "b2_files_sort_date"
        NAME -> "b2_files_sort_name"
        SIZE -> "b2_files_sort_size"
        TYPE -> "b2_files_sort_type"
    }
}

private enum class SearchScope {
    CURRENT, ALL;
    fun titleKey(): String = when (this) {
        CURRENT -> "b2_files_scope_current"
        ALL -> "b2_files_scope_all"
    }
}

private enum class QuickAccess(val key: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    RECENT("recent", Icons.Default.History),
    FAVORITES("favorites", Icons.Default.Favorite),
    PDF("pdf", Icons.Default.PictureAsPdf),
    IMAGES("images", Icons.Default.Image),
    OFFICE("office", Icons.Default.Description),
    TEXT("text", Icons.Default.TextSnippet);

    fun titleKey(): String = when (this) {
        RECENT -> "b2_files_quick_recent"
        FAVORITES -> "b2_files_quick_favorites"
        PDF -> "b2_files_quick_pdf"
        IMAGES -> "b2_files_quick_images"
        OFFICE -> "b2_files_quick_office"
        TEXT -> "b2_files_quick_text"
    }
}

private enum class TransferMode { COPY, MOVE }

@Composable
fun FilesScreen(
    initialDirectoryPath: String? = null,
    onBack: () -> Unit,
    onOpenPdf: (File) -> Unit,
    onEditPdf: (File) -> Unit,
    onCleanShare: (File) -> Unit,
    onOpenSmartPack: () -> Unit
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var legacyWorkspaceMode by remember { mutableStateOf(false) }
    if (!legacyWorkspaceMode) {
        WindowsFileExplorerPane(
            initialDirectoryPath = initialDirectoryPath,
            onBack = onBack,
            onOpenLegacyWorkspace = { legacyWorkspaceMode = true }
        )
        return
    }
    var deviceStorageMode by remember { mutableStateOf(false) }
    if (deviceStorageMode) {
        SafFileManagerPane(onBackToWorkspace = { deviceStorageMode = false })
        return
    }
    val scope = rememberCoroutineScope()
    val root = remember { FileUtils.rootDir(context) }
    val prefs = remember { context.getSharedPreferences("ab_file_pro_files", 0) }
    var currentDir by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var directoryBusy by remember { mutableStateOf(true) }
    var directoryVersion by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var searchScope by remember { mutableStateOf(SearchScope.CURRENT) }
    var globalSearchResults by remember { mutableStateOf<List<File>>(emptyList()) }
    var searchBusy by remember { mutableStateOf(false) }
    var quickAccess by remember { mutableStateOf<QuickAccess?>(null) }
    var quickResults by remember { mutableStateOf<List<File>>(emptyList()) }
    var quickBusy by remember { mutableStateOf(false) }
    var recentVersion by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(FileFilter.ALL) }
    var sort by remember { mutableStateOf(FileSort.DATE) }
    var sortAscending by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var createFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renameEntry by remember { mutableStateOf<File?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteEntry by remember { mutableStateOf<File?>(null) }
    var detailsEntry by remember { mutableStateOf<File?>(null) }
    var previewEntry by remember { mutableStateOf<File?>(null) }
    var pdfQuickEntry by remember { mutableStateOf<File?>(null) }
    var operationBusy by remember { mutableStateOf(false) }
    var transferEntries by remember { mutableStateOf<List<File>>(emptyList()) }
    var transferMode by remember { mutableStateOf(TransferMode.COPY) }
    var transferFolders by remember { mutableStateOf<List<File>>(emptyList()) }
    var transferFoldersBusy by remember { mutableStateOf(false) }
    var detailsChildCount by remember { mutableStateOf<Int?>(null) }
    var detailsCountBusy by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkDeleteDialog by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var matchedContentPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tagEntry by remember { mutableStateOf<File?>(null) }
    var tagText by remember { mutableStateOf("") }
    var tagVersion by remember { mutableIntStateOf(0) }
    var activeTag by remember { mutableStateOf<String?>(null) }
    var tagResults by remember { mutableStateOf<List<File>>(emptyList()) }
    var tagChooser by remember { mutableStateOf(false) }
    var trashDialog by remember { mutableStateOf(false) }
    var trashVersion by remember { mutableIntStateOf(0) }
    var permanentTrashItem by remember { mutableStateOf<TrashStore.TrashItem?>(null) }
    var emptyTrashConfirm by remember { mutableStateOf(false) }
    var defaultFolderDialog by remember { mutableStateOf(false) }
    var defaultFolderVersion by remember { mutableIntStateOf(0) }
    var undoVersion by remember { mutableIntStateOf(0) }

    fun recentRecords(requireExisting: Boolean = true): List<Pair<Long, File>> {
        return prefs.getStringSet("recent_files_v2", emptySet()).orEmpty()
            .mapNotNull { raw ->
                val separator = raw.indexOf('|')
                if (separator <= 0) return@mapNotNull null
                val timestamp = raw.substring(0, separator).toLongOrNull() ?: return@mapNotNull null
                val file = File(raw.substring(separator + 1))
                if (!FileUtils.isInsideRoot(context, file)) null
                else if (requireExisting && (!file.exists() || !file.isFile)) null
                else timestamp to file
            }
            .sortedByDescending { it.first }
    }

    fun saveRecentRecords(records: List<Pair<Long, File>>) {
        val stored = records
            .filter { (_, file) -> file.exists() && file.isFile && FileUtils.isInsideRoot(context, file) }
            .distinctBy { it.second.absolutePath }
            .sortedByDescending { it.first }
            .take(30)
            .mapTo(mutableSetOf()) { (timestamp, file) -> "$timestamp|${file.absolutePath}" }
        prefs.edit().putStringSet("recent_files_v2", stored).apply()
        recentVersion++
    }

    fun recordRecent(file: File) {
        if (!file.exists() || !file.isFile || !FileUtils.isInsideRoot(context, file)) return
        val updated = recentRecords().filterNot { it.second.absolutePath == file.absolutePath }.toMutableList()
        updated += System.currentTimeMillis() to file
        saveRecentRecords(updated)
    }

    fun updateRecentPath(old: File, new: File) {
        val oldPath = old.absolutePath
        val changed = recentRecords(requireExisting = false).map { (timestamp, file) ->
            val path = file.absolutePath
            if (path == oldPath || path.startsWith(oldPath + File.separator)) {
                timestamp to File(new.absolutePath + path.removePrefix(oldPath))
            } else timestamp to file
        }
        saveRecentRecords(changed)
    }

    fun removeRecentPaths(paths: Set<String>) {
        if (paths.isEmpty()) return
        saveRecentRecords(recentRecords(requireExisting = false).filterNot { (_, file) -> file.absolutePath in paths })
    }

    fun refresh() {
        if (!currentDir.exists() || !FileUtils.isInsideRoot(context, currentDir)) currentDir = root
        selectedPaths = selectedPaths.filterTo(mutableSetOf()) { path ->
            val file = File(path)
            file.exists() && file.isFile && FileUtils.isInsideRoot(context, file)
        }
        if (selectedPaths.isEmpty()) selectionMode = false
        recentVersion++
        directoryVersion++
    }

    fun clearSelection() {
        selectedPaths = emptySet()
        selectionMode = false
    }

    fun toggleSelection(file: File) {
        if (!file.isFile) return
        selectionMode = true
        selectedPaths = if (file.absolutePath in selectedPaths) selectedPaths - file.absolutePath else selectedPaths + file.absolutePath
        if (selectedPaths.isEmpty()) selectionMode = false
    }

    fun selectedFiles(): List<File> = selectedPaths.map(::File).filter {
        it.exists() && it.isFile && FileUtils.isInsideRoot(context, it)
    }

    fun saveFavorites(value: Set<String>) {
        favorites = value
        prefs.edit().putStringSet("favorites", value).apply()
    }

    fun isUsableEntry(entry: File, requireFile: Boolean? = null): Boolean {
        val valid = runCatching {
            entry.exists() && FileUtils.isInsideRoot(context, entry) && when (requireFile) {
                true -> entry.isFile
                false -> entry.isDirectory
                null -> entry.isFile || entry.isDirectory
            }
        }.getOrDefault(false)
        if (!valid) {
            Toast.makeText(context, context.uiText(language, "b2_files_missing"), Toast.LENGTH_SHORT).show()
            refresh()
        }
        return valid
    }

    fun updateFavoritePath(old: File, new: File) {
        val oldPath = old.absolutePath
        val affected = favorites.filter { it == oldPath || it.startsWith(oldPath + File.separator) }
        if (affected.isEmpty()) return
        val updated = favorites.toMutableSet().apply {
            removeAll(affected.toSet())
            affected.forEach { path -> add(new.absolutePath + path.removePrefix(oldPath)) }
        }
        saveFavorites(updated)
    }

    fun toggleFavorite(file: File) {
        saveFavorites(if (file.absolutePath in favorites) favorites - file.absolutePath else favorites + file.absolutePath)
    }

    fun preview(file: File) {
        if (!isUsableEntry(file, requireFile = true) || !FileUtils.canPreview(file)) return
        recordRecent(file)
        previewEntry = file
    }

    fun open(entry: File) {
        if (!isUsableEntry(entry)) return
        if (selectionMode && entry.isFile) {
            toggleSelection(entry)
            return
        }
        if (entry.isDirectory) {
            clearSelection()
            currentDir = entry
            search = ""
            quickAccess = null
            searchScope = SearchScope.CURRENT
            refresh()
        } else {
            recordRecent(entry)
            when {
                FileUtils.canPreview(entry) -> previewEntry = entry
                entry.extension.equals("pdf", true) -> onOpenPdf(entry)
                else -> runCatching { FileUtils.openFile(context, entry) }
                    .onFailure { Toast.makeText(context, context.uiText(language, "b2_files_open_error"), Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun runPdfJob(job: suspend () -> String) {
        if (operationBusy) return
        scope.launch {
            operationBusy = true
            try {
                val message = job()
                recentVersion++
                refresh()
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Toast.makeText(context, context.uiText(language, "b2_files_pdf_tool_error"), Toast.LENGTH_LONG).show()
            } finally {
                pdfQuickEntry = null
                operationBusy = false
            }
        }
    }

    fun goBack() {
        if (selectionMode) {
            clearSelection()
            return
        }
        if (previewEntry != null) {
            previewEntry = null
            return
        }
        if (quickAccess != null) {
            quickAccess = null
            return
        }
        if (activeTag != null) {
            activeTag = null
            return
        }
        if (search.isNotBlank()) {
            search = ""
            return
        }
        if (currentDir.canonicalFile != root.canonicalFile) {
            currentDir = currentDir.parentFile?.takeIf { FileUtils.isInsideRoot(context, it) } ?: root
            search = ""
            refresh()
        } else { legacyWorkspaceMode = false }
    }

    BackHandler(onBack = ::goBack)

    LaunchedEffect(currentDir.absolutePath, directoryVersion) {
        val targetDirectory = currentDir
        directoryBusy = true
        val loaded = withContext(Dispatchers.IO) {
            runCatching { FileUtils.listDirectory(context, targetDirectory) }.getOrDefault(emptyList())
        }
        if (currentDir.absolutePath == targetDirectory.absolutePath) {
            entries = loaded
            directoryBusy = false
        }
    }

    LaunchedEffect(detailsEntry?.absolutePath, detailsEntry?.lastModified()) {
        val target = detailsEntry
        detailsChildCount = null
        detailsCountBusy = false
        if (target != null && target.isDirectory) {
            detailsCountBusy = true
            detailsChildCount = withContext(Dispatchers.IO) {
                runCatching { FileUtils.directVisibleChildCount(context, target) }.getOrNull()
            }
            detailsCountBusy = false
        }
    }

    LaunchedEffect(transferEntries, currentDir.absolutePath) {
        val sourceSnapshot = transferEntries.filter { it.exists() && it.isFile }
        if (sourceSnapshot.isEmpty()) {
            transferFolders = emptyList()
            transferFoldersBusy = false
        } else {
            transferFoldersBusy = true
            transferFolders = withContext(Dispatchers.IO) {
                val sourceParents = sourceSnapshot.mapNotNull { it.parentFile?.canonicalPath }.toSet()
                runCatching {
                    FileUtils.listFolders(context, limit = 2000)
                        .filter { it.canonicalPath !in sourceParents }
                }.getOrDefault(emptyList())
            }
            transferFoldersBusy = false
        }
    }

    LaunchedEffect(search, searchScope, recentVersion) {
        if (searchScope == SearchScope.ALL && search.trim().isNotBlank()) {
            searchBusy = true
            delay(220)
            val hits = withContext(Dispatchers.IO) {
                runCatching { WorkspaceSearchEngine.search(context, search, limit = 300) }.getOrDefault(emptyList())
            }
            globalSearchResults = hits.map { it.file }
            matchedContentPaths = hits.filter { it.matchedContent }.mapTo(mutableSetOf()) { it.file.absolutePath }
            searchBusy = false
        } else {
            globalSearchResults = emptyList()
            matchedContentPaths = emptySet()
            searchBusy = false
        }
    }

    LaunchedEffect(activeTag, tagVersion) {
        val tag = activeTag
        tagResults = if (tag.isNullOrBlank()) emptyList() else withContext(Dispatchers.IO) {
            runCatching { FileTagStore.filesWithTag(context, tag, limit = 300) }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(quickAccess, favorites, recentVersion) {
        val selectedQuick = quickAccess
        if (selectedQuick == null) {
            quickResults = emptyList()
            quickBusy = false
        } else {
            quickBusy = true
            quickResults = withContext(Dispatchers.IO) {
                if (selectedQuick == QuickAccess.RECENT) {
                    recentRecords().map { it.second }.take(30)
                } else {
                    runCatching {
                        FileUtils.quickAccessFiles(context, selectedQuick.key, favorites, limit = 300)
                    }.getOrDefault(emptyList())
                }
            }
            quickBusy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && !operationBusy) {
            val destination = currentDir
            scope.launch {
                operationBusy = true
                val (imported, failed) = withContext(Dispatchers.IO) {
                    var importedCount = 0
                    var failedCount = 0
                    uris.forEach { uri ->
                        runCatching { FileUtils.importUri(context, uri, destination) }
                            .onSuccess { importedCount++ }
                            .onFailure { failedCount++ }
                    }
                    importedCount to failedCount
                }
                refresh()
                operationBusy = false
                val message = if (failed == 0) context.uiText(language, "b2_files_imported", imported) else context.uiText(language, "b2_files_imported_failed", imported, failed)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty() && !operationBusy) {
            val destination = currentDir
            scope.launch {
                operationBusy = true
                val (imported, failed) = withContext(Dispatchers.IO) {
                    var importedCount = 0
                    var failedCount = 0
                    uris.take(30).forEach { uri ->
                        runCatching { FileUtils.importUri(context, uri, destination) }
                            .onSuccess { importedCount++ }
                            .onFailure { failedCount++ }
                    }
                    importedCount to failedCount
                }
                refresh()
                operationBusy = false
                Toast.makeText(context, context.uiText(language, "b2_files_import_images_failed", imported, failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    val sourceEntries = when {
        searchScope == SearchScope.ALL && search.trim().isNotBlank() -> globalSearchResults
        activeTag != null && search.isBlank() -> tagResults
        quickAccess != null && search.isBlank() -> quickResults
        else -> entries
    }

    val shown = remember(sourceEntries, search, searchScope, filter, sort, sortAscending, favorites) {
        val filtered = sourceEntries.filter { entry ->
            val queryOk = searchScope == SearchScope.ALL || search.isBlank() || entry.name.contains(search.trim(), ignoreCase = true)
            val typeOk = if (entry.isDirectory) {
                filter == FileFilter.ALL || (filter == FileFilter.FAVORITES && entry.absolutePath in favorites)
            } else when (filter) {
                FileFilter.ALL -> true
                FileFilter.PDF -> entry.extension.equals("pdf", true)
                FileFilter.IMAGES -> FileUtils.isImageFile(entry)
                FileFilter.OFFICE -> FileUtils.isOfficeFile(entry)
                FileFilter.TEXT -> FileUtils.isTextFile(entry)
                FileFilter.FAVORITES -> entry.absolutePath in favorites
            }
            queryOk && typeOk
        }
        val baseComparator = when (sort) {
            FileSort.DATE -> compareBy<File> { it.lastModified() }
            FileSort.NAME -> compareBy<File> { it.name.lowercase() }
            FileSort.SIZE -> compareBy<File> { if (it.isFile) it.length() else 0L }
            FileSort.TYPE -> compareBy<File> { if (it.isDirectory) "" else it.extension.lowercase() }.thenBy { it.name.lowercase() }
        }
        val comparator = if (sortAscending) baseComparator else baseComparator.reversed()
        filtered.sortedWith(compareByDescending<File> { it.isDirectory }.then(comparator))
    }

    createFolderDialog.takeIf { it }?.let {
        AlertDialog(
            onDismissRequest = { createFolderDialog = false },
            title = { Text(context.uiText(language, "b2_files_new_folder")) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(context.uiText(language, "b2_files_folder_name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    runCatching { FileUtils.createFolder(context, currentDir, newFolderName) }
                        .onSuccess { refresh(); Toast.makeText(context, context.uiText(language, "b2_files_folder_created"), Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, context.uiText(language, "b2_files_folder_error"), Toast.LENGTH_LONG).show() }
                    createFolderDialog = false
                    newFolderName = ""
                }) { Text(context.uiText(language, "b2_files_create")) }
            },
            dismissButton = { TextButton(onClick = { createFolderDialog = false }) { Text(context.uiText(language, "b2_files_cancel")) } }
        )
    }

    renameEntry?.let { entry ->
        val finalName = FileUtils.previewRenamedName(entry, renameText)
        AlertDialog(
            onDismissRequest = { renameEntry = null },
            title = { Text(context.uiText(language, "b2_files_rename_title", if (entry.isDirectory) context.uiText(language, "b2_files_type_folder") else context.uiText(language, "b2_files_type_file"))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(context.uiText(language, "b2_files_new_name")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (entry.isFile && entry.extension.isNotBlank()) {
                        Text(
                            context.uiText(language, "b2_files_keep_ext", entry.extension.lowercase()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (finalName.isNotBlank()) {
                        Text(
                            context.uiText(language, "b2_files_final_name", finalName),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = finalName.isNotBlank() && !operationBusy,
                    onClick = {
                        val requestedName = renameText
                        scope.launch {
                            operationBusy = true
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val renamed = FileUtils.renameEntry(entry, requestedName)
                                        FileUndoStore.recordRename(context, entry, renamed)
                                        renamed
                                    }
                                }
                                result
                                    .onSuccess { newEntry ->
                                        updateFavoritePath(entry, newEntry)
                                        updateRecentPath(entry, newEntry)
                                        FileTagStore.movePath(context, entry, newEntry)
                                        undoVersion++
                                        tagVersion++
                                        refresh()
                                        Toast.makeText(context, context.uiText(language, "b2_files_rename_done"), Toast.LENGTH_SHORT).show()
                                    }
                                    .onFailure { Toast.makeText(context, context.uiText(language, "b2_files_rename_error"), Toast.LENGTH_LONG).show() }
                                renameEntry = null
                            } finally {
                                operationBusy = false
                            }
                        }
                    }
                ) { Text(context.uiText(language, "b2_files_save")) }
            },
            dismissButton = { TextButton(onClick = { renameEntry = null }) { Text(context.uiText(language, "b2_files_cancel")) } }
        )
    }

    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text(context.uiText(language, "b2_files_trash_single_title", if (entry.isDirectory) context.uiText(language, "b2_files_type_folder") else context.uiText(language, "b2_files_type_file"))) },
            text = { Text(context.uiText(language, "b2_files_trash_single_desc", entry.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        runCatching { TrashStore.moveToTrash(context, entry) }
                            .onSuccess { trashItem ->
                                saveFavorites(favorites - entry.absolutePath)
                                removeRecentPaths(setOf(entry.absolutePath))
                                FileTagStore.movePath(context, entry, trashItem.trashFile)
                                trashVersion++
                                tagVersion++
                                refresh()
                                Toast.makeText(context, context.uiText(language, "b2_files_trash_move_done"), Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { Toast.makeText(context, context.uiText(language, "b2_files_trash_move_error"), Toast.LENGTH_LONG).show() }
                        deleteEntry = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(context.uiText(language, "b2_files_trash_move_button")) }
            },
            dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text(context.uiText(language, "b2_files_cancel")) } }
        )
    }

    if (bulkDeleteDialog) {
        val selected = selectedFiles()
        AlertDialog(
            onDismissRequest = { bulkDeleteDialog = false },
            title = { Text(context.uiText(language, "b2_files_trash_bulk_title", selected.size)) },
            text = { Text(context.uiText(language, "b2_files_trash_bulk_desc")) },
            confirmButton = {
                Button(
                    enabled = !operationBusy,
                    onClick = {
                        val snapshot = selected.toList()
                        scope.launch {
                            operationBusy = true
                            val result = withContext(Dispatchers.IO) {
                                var deleted = 0
                                var failed = 0
                                val deletedPaths = mutableSetOf<String>()
                                snapshot.forEach { file ->
                                    runCatching { TrashStore.moveToTrash(context, file) }
                                        .onSuccess { item -> FileTagStore.movePath(context, file, item.trashFile); deleted++; deletedPaths += file.absolutePath }
                                        .onFailure { failed++ }
                                }
                                Triple(deleted, failed, deletedPaths.toSet())
                            }
                            val (deleted, failed, deletedPaths) = result
                            if (deletedPaths.isNotEmpty()) {
                                saveFavorites(favorites - deletedPaths)
                                removeRecentPaths(deletedPaths)
                                trashVersion++
                                tagVersion++
                            }
                            bulkDeleteDialog = false
                            clearSelection()
                            refresh()
                            operationBusy = false
                            val message = if (failed == 0) context.uiText(language, "b2_files_trash_bulk_done", deleted) else context.uiText(language, "b2_files_trash_bulk_failed", deleted, failed)
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    if (operationBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(context.uiText(language, "b2_files_trash_bulk_button"))
                }
            },
            dismissButton = { TextButton(onClick = { bulkDeleteDialog = false }) { Text(context.uiText(language, "b2_files_cancel")) } }
        )
    }

    detailsEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { detailsEntry = null },
            title = { Text(context.uiText(language, "b2_files_details")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.name, fontWeight = FontWeight.Bold)
                    DetailLine(context.uiText(language, "b2_files_type"), fileTypeLabel(context, language, entry))
                    if (entry.isFile) {
                        DetailLine(context.uiText(language, "b2_files_extension"), entry.extension.uppercase().ifBlank { context.uiText(language, "b2_files_no_extension") })
                        DetailLine("MIME", FileUtils.mimeType(entry))
                        DetailLine(context.uiText(language, "b2_files_size"), formatSize(entry.length()))
                        DetailLine(context.uiText(language, "b2_files_exact_size"), context.uiText(language, "b2_files_bytes", entry.length()))
                    } else {
                        when {
                            detailsCountBusy -> DetailLine(context.uiText(language, "b2_files_direct_items"), context.uiText(language, "b2_files_calculating"))
                            detailsChildCount != null -> DetailLine(context.uiText(language, "b2_files_direct_items"), "${detailsChildCount ?: 0}")
                            else -> DetailLine(context.uiText(language, "b2_files_direct_items"), context.uiText(language, "b2_files_unavailable"))
                        }
                    }
                    DetailLine(context.uiText(language, "b2_files_modified"), DateFormat.getDateTimeInstance().format(Date(entry.lastModified())))
                    DetailLine(context.uiText(language, "b2_files_location"), relativePath(context, language, root, entry))
                    DetailLine(context.uiText(language, "b2_files_parent"), relativePath(context, language, root, entry.parentFile ?: root))
                }
            },
            confirmButton = { TextButton(onClick = { detailsEntry = null }) { Text(context.uiText(language, "b2_files_close")) } }
        )
    }

    previewEntry?.let { entry ->
        FilePreviewDialog(
            entry = entry,
            onDismiss = { previewEntry = null },
            onOpenExternal = {
                previewEntry = null
                FileUtils.openFile(context, entry)
            },
            onShare = { FileUtils.shareFile(context, entry) },
            onWhatsApp = {
                runCatching { FileUtils.shareImageToWhatsApp(context, entry) }
                    .onFailure { Toast.makeText(context, context.uiText(language, "b2_files_whatsapp_error"), Toast.LENGTH_LONG).show() }
            }
        )
    }

    pdfQuickEntry?.let { pdf ->
        if (!pdf.exists() || !pdf.isFile || !FileUtils.isInsideRoot(context, pdf)) {
            AlertDialog(
                onDismissRequest = { pdfQuickEntry = null },
                title = { Text(context.uiText(language, "b2_files_pdf_unavailable")) },
                text = { Text(context.uiText(language, "b2_files_pdf_missing_desc")) },
                confirmButton = { TextButton(onClick = { pdfQuickEntry = null; refresh() }) { Text(context.uiText(language, "b2_files_close")) } }
            )
        } else {
        val pdfUri = remember(pdf.absolutePath, pdf.lastModified()) { FileUtils.contentUri(context, pdf) }
        AlertDialog(
            onDismissRequest = { if (!operationBusy) pdfQuickEntry = null },
            title = { Text(context.uiText(language, "b2_files_quick_pdf")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pdf.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        context.uiText(language, "b2_files_quick_pdf_note"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (operationBusy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        OutlinedButton(
                            onClick = { pdfQuickEntry = null; onEditPdf(pdf) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text(context.uiText(language, "b2_files_edit_pdf"))
                        }
                        OutlinedButton(
                            onClick = {
                                runPdfJob {
                                    val out = PdfTools.compressPdf(context, pdfUri)
                                    recordRecent(out)
                                    context.uiText(language, "b2_files_compressed_done", out.name)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Compress, null); Spacer(Modifier.width(8.dp)); Text(context.uiText(language, "b2_files_compress_pdf"))
                        }
                        OutlinedButton(
                            onClick = {
                                runPdfJob {
                                    val out = PdfTools.rotateAllPages(context, pdfUri, 90)
                                    recordRecent(out)
                                    context.uiText(language, "b2_files_rotated_done", out.name)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.RotateRight, null); Spacer(Modifier.width(8.dp)); Text(context.uiText(language, "b2_files_rotate_pdf"))
                        }
                        OutlinedButton(
                            onClick = {
                                runPdfJob {
                                    val parts = PdfTools.splitPdf(context, pdfUri)
                                    parts.take(10).forEach(::recordRecent)
                                    context.uiText(language, "b2_files_split_done", parts.size)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CallSplit, null); Spacer(Modifier.width(8.dp)); Text(context.uiText(language, "b2_files_split_pages"))
                        }
                        OutlinedButton(
                            onClick = {
                                runPdfJob {
                                    val images = PdfTools.pdfToImages(context, pdfUri)
                                    images.take(10).forEach(::recordRecent)
                                    context.uiText(language, "b2_files_images_done", images.size)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp)); Text(context.uiText(language, "b2_files_pages_to_images"))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(enabled = !operationBusy, onClick = { pdfQuickEntry = null }) { Text(context.uiText(language, "b2_files_close")) }
            }
        )
        }
    }

    if (transferEntries.isNotEmpty()) {
        val sources = transferEntries.filter { it.exists() && it.isFile }
        val folders = transferFolders
        AlertDialog(
            onDismissRequest = { if (!operationBusy) transferEntries = emptyList() },
            title = {
                val verb = if (transferMode == TransferMode.COPY) context.uiText(language, "b2_files_copy") else context.uiText(language, "b2_files_move")
                val noun = if (sources.size == 1) context.uiText(language, "b2_files_type_file") else context.uiText(language, "b2_files_files_plural")
                Text(context.uiText(language, "b2_files_transfer_title", verb, sources.size, noun))
            },
            text = {
                if (transferFoldersBusy) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(folders, key = { it.absolutePath }) { folder ->
                        ListItem(
                            headlineContent = { Text(if (folder.canonicalFile == root.canonicalFile) context.uiText(language, "b2_files_path_root") else folder.name) },
                            supportingContent = { Text(relativePath(context, language, root, folder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.Folder, null) },
                            modifier = Modifier.clickable(enabled = !operationBusy) {
                                val mode = transferMode
                                val sourceSnapshot = sources.toList()
                                scope.launch {
                                    operationBusy = true
                                    val result = withContext(Dispatchers.IO) {
                                        var completed = 0
                                        var failed = 0
                                        val movedPaths = mutableMapOf<String, String>()
                                        sourceSnapshot.forEach { source ->
                                            runCatching {
                                                if (mode == TransferMode.COPY) FileUtils.copyFile(context, source, folder)
                                                else FileUtils.moveFile(context, source, folder)
                                            }.onSuccess { target ->
                                                completed++
                                                if (mode == TransferMode.MOVE) movedPaths[source.absolutePath] = target.absolutePath
                                            }.onFailure { failed++ }
                                        }
                                        Triple(completed, failed, movedPaths.toMap())
                                    }
                                    val (completed, failed, movedPaths) = result
                                    if (movedPaths.isNotEmpty()) {
                                        saveFavorites(favorites.map { movedPaths[it] ?: it }.toSet())
                                        val updatedRecent = recentRecords(requireExisting = false).map { (timestamp, file) ->
                                            timestamp to File(movedPaths[file.absolutePath] ?: file.absolutePath)
                                        }
                                        saveRecentRecords(updatedRecent)
                                        movedPaths.forEach { (oldPath, newPath) -> FileTagStore.movePath(context, File(oldPath), File(newPath)) }
                                        withContext(Dispatchers.IO) { FileUndoStore.recordMoves(context, movedPaths) }
                                        undoVersion++
                                        tagVersion++
                                    }
                                    transferEntries = emptyList()
                                    clearSelection()
                                    refresh()
                                    operationBusy = false
                                    val verb = if (mode == TransferMode.COPY) context.uiText(language, "b2_files_copy") else context.uiText(language, "b2_files_move")
                                    val message = if (failed == 0) context.uiText(language, "b2_files_transfer_done", verb, completed) else context.uiText(language, "b2_files_transfer_failed", verb, completed, failed)
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(enabled = !operationBusy, onClick = { transferEntries = emptyList() }) { Text(context.uiText(language, "b2_files_cancel")) } }
        )
    }

    tagEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { tagEntry = null },
            title = { Text(context.uiText(language, "b2_files_tags_title", entry.name)) },
            text = {
                OutlinedTextField(
                    value = tagText,
                    onValueChange = { tagText = it },
                    label = { Text(context.uiText(language, "b2_files_tags_hint")) },
                    supportingText = { Text(context.uiText(language, "b2_files_tags_note")) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val tags = tagText.split(',', '،').map { it.trim() }.filter { it.isNotBlank() }
                    runCatching { FileTagStore.setTags(context, entry, tags) }
                        .onSuccess { tagVersion++; Toast.makeText(context, context.uiText(language, "b2_files_tags_saved"), Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, context.uiText(language, "b2_files_tags_error"), Toast.LENGTH_LONG).show() }
                    tagEntry = null
                }) { Text(context.uiText(language, "b2_files_save")) }
            },
            dismissButton = { TextButton(onClick = { tagEntry = null }) { Text(context.uiText(language, "b2_files_cancel")) } }
        )
    }

    if (tagChooser) {
        val tags = remember(tagVersion) { FileTagStore.allTags(context).toList() }
        AlertDialog(
            onDismissRequest = { tagChooser = false },
            title = { Text(context.uiText(language, "b2_files_tags_filter")) },
            text = {
                if (tags.isEmpty()) Text(context.uiText(language, "b2_files_tags_empty")) else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(tags, key = { it }) { tag ->
                        ListItem(
                            headlineContent = { Text("#$tag") },
                            leadingContent = { Icon(Icons.Default.Label, null) },
                            modifier = Modifier.clickable { activeTag = tag; quickAccess = null; search = ""; tagChooser = false }
                        )
                    }
                }
            },
            confirmButton = {
                if (activeTag != null) TextButton(onClick = { activeTag = null; tagChooser = false }) { Text(context.uiText(language, "b2_files_tags_clear_filter")) }
            },
            dismissButton = { TextButton(onClick = { tagChooser = false }) { Text(context.uiText(language, "b2_files_close")) } }
        )
    }

    if (defaultFolderDialog) {
        var folders by remember(defaultFolderVersion, defaultFolderDialog) { mutableStateOf<List<File>>(emptyList()) }
        var loadingFolders by remember(defaultFolderDialog) { mutableStateOf(true) }
        LaunchedEffect(defaultFolderDialog, defaultFolderVersion) {
            loadingFolders = true
            folders = withContext(Dispatchers.IO) { runCatching { FileUtils.listFolders(context, 1000) }.getOrDefault(listOf(root)) }
            loadingFolders = false
        }
        val currentDefault = remember(defaultFolderVersion) { FileUtils.defaultSaveDirectory(context) }
        AlertDialog(
            onDismissRequest = { defaultFolderDialog = false },
            title = { Text(context.uiText(language, "b2_files_default_folder_title")) },
            text = {
                Column {
                    Text(context.uiText(language, "b2_files_default_folder_note"), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    if (loadingFolders) LinearProgressIndicator(Modifier.fillMaxWidth()) else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(folders, key = { it.absolutePath }) { folder ->
                            ListItem(
                                headlineContent = { Text(if (folder.canonicalFile == root.canonicalFile) context.uiText(language, "b2_files_path_root") else folder.name) },
                                supportingContent = { Text(relativePath(context, language, root, folder), maxLines = 1) },
                                leadingContent = { Icon(if (folder.canonicalFile == currentDefault.canonicalFile) Icons.Default.CheckCircle else Icons.Default.Folder, null) },
                                modifier = Modifier.clickable {
                                    runCatching { FileUtils.setDefaultSaveDirectory(context, folder) }
                                        .onSuccess { defaultFolderVersion++; Toast.makeText(context, context.uiText(language, "b2_files_default_folder_set"), Toast.LENGTH_SHORT).show() }
                                    defaultFolderDialog = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { defaultFolderDialog = false }) { Text(context.uiText(language, "b2_files_close")) } }
        )
    }

    if (trashDialog) {
        val trashItems = remember(trashVersion, trashDialog) { TrashStore.items(context) }
        AlertDialog(
            onDismissRequest = { trashDialog = false },
            title = { Text(context.uiText(language, "b2_files_trash_title")) },
            text = {
                if (trashItems.isEmpty()) {
                    Text(context.uiText(language, "b2_files_trash_empty"))
                } else {
                    LazyColumn(Modifier.heightIn(max = 440.dp)) {
                        items(trashItems, key = { it.trashFile.absolutePath }) { item ->
                            ListItem(
                                headlineContent = { Text(File(item.originalPath).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(context.uiText(language, "b2_files_original_location", item.originalPath.removePrefix(root.absolutePath).ifBlank { "/" }), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Icon(Icons.Default.RestoreFromTrash, null) },
                                trailingContent = {
                                    Row {
                                        IconButton(onClick = {
                                            runCatching { TrashStore.restore(context, item) }
                                                .onSuccess { restored -> FileTagStore.movePath(context, item.trashFile, restored); trashVersion++; tagVersion++; refresh(); Toast.makeText(context, context.uiText(language, "b2_files_restored", restored.name), Toast.LENGTH_SHORT).show() }
                                                .onFailure { Toast.makeText(context, context.uiText(language, "b2_files_restore_error"), Toast.LENGTH_LONG).show() }
                                        }) { Icon(Icons.Default.Restore, contentDescription = context.uiText(language, "b2_files_restore")) }
                                        IconButton(onClick = { permanentTrashItem = item }) {
                                            Icon(Icons.Default.DeleteForever, contentDescription = context.uiText(language, "b2_files_delete_forever"))
                                        }
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                if (trashItems.isNotEmpty()) TextButton(onClick = { emptyTrashConfirm = true }) { Text(context.uiText(language, "b2_files_empty_trash")) }
            },
            dismissButton = { TextButton(onClick = { trashDialog = false }) { Text(context.uiText(language, "b2_files_close")) } }
        )
    }

    permanentTrashItem?.let { item ->
        AlertDialog(
            onDismissRequest = { permanentTrashItem = null },
            title = { Text(context.uiText(language, "b2_files_delete_forever_title")) },
            text = { Text(context.uiText(language, "b2_files_delete_forever_desc", File(item.originalPath).name)) },
            confirmButton = {
                Button(
                    onClick = {
                        if (TrashStore.deletePermanently(context, item)) {
                            trashVersion++; tagVersion++
                            Toast.makeText(context, context.uiText(language, "b2_files_deleted_forever"), Toast.LENGTH_SHORT).show()
                        }
                        permanentTrashItem = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(context.uiText(language, "b2_files_delete_forever")) }
            },
            dismissButton = { TextButton(onClick = { permanentTrashItem = null }) { Text(context.uiText(language, "b2_files_cancel")) } }
        )
    }

    if (emptyTrashConfirm) {
        AlertDialog(
            onDismissRequest = { emptyTrashConfirm = false },
            title = { Text(context.uiText(language, "b2_files_empty_trash_title")) },
            text = { Text(context.uiText(language, "b2_files_empty_trash_desc")) },
            confirmButton = {
                Button(
                    onClick = {
                        val count = TrashStore.empty(context)
                        trashVersion++; tagVersion++; emptyTrashConfirm = false
                        Toast.makeText(context, context.uiText(language, "b2_files_empty_count", count), Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(context.uiText(language, "b2_files_empty_forever")) }
            },
            dismissButton = { TextButton(onClick = { emptyTrashConfirm = false }) { Text(context.uiText(language, "b2_files_cancel")) } }
        )
    }

    val canUndoNow = remember(undoVersion) { FileUndoStore.canUndo(context) }

    Scaffold(
        topBar = {
            ScreenTopBar(if (currentDir.canonicalFile == root.canonicalFile) context.uiText(language, "b2_files_my_files") else currentDir.name, ::goBack) {
                if (canUndoNow) {
                    IconButton(enabled = !operationBusy, onClick = {
                        scope.launch {
                        operationBusy = true
                        try {
                        withContext(Dispatchers.IO) { FileUndoStore.undo(context) }
                            .onSuccess { count ->
                                favorites = prefs.getStringSet("favorites", emptySet())?.toSet().orEmpty()
                                undoVersion++; tagVersion++; refresh()
                                Toast.makeText(context, context.uiText(language, "b2_files_undo_count", count), Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { Toast.makeText(context, context.uiText(language, "b2_files_undo_error"), Toast.LENGTH_LONG).show() }
                        } finally { operationBusy = false }
                        }
                    }) { Icon(Icons.Default.Undo, contentDescription = context.uiText(language, "b2_files_undo")) }
                }
                IconButton(onClick = { trashDialog = true }) { Icon(Icons.Default.RestoreFromTrash, contentDescription = context.uiText(language, "b2_files_trash_title")) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!operationBusy) importLauncher.launch(arrayOf("*/*")) },
                icon = { if (operationBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.FileDownload, null) },
                text = { Text(if (operationBusy) context.uiText(language, "b2_files_importing") else context.uiText(language, "b2_files_import")) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        when {
                            activeTag != null -> "Tag / $activeTag"
                            quickAccess != null -> context.uiText(language, "b2_files_quick_access_path", context.uiText(language, "b2_files_quick_access"), quickAccess?.let { context.uiText(language, it.titleKey()) }.orEmpty())
                            searchScope == SearchScope.ALL && search.isNotBlank() -> context.uiText(language, "b2_files_search_name_content")
                            else -> relativePath(context, language, root, currentDir)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            AssistChip(
                                onClick = { deviceStorageMode = true },
                                label = { Text(context.uiText(language, "saf_device_storage")) },
                                leadingIcon = { Icon(Icons.Default.Storage, null, Modifier.size(18.dp)) }
                            )
                        }
                        item {
                            AssistChip(
                                onClick = { createFolderDialog = true },
                                label = { Text(context.uiText(language, "b2_files_new_folder")) },
                                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp)) }
                            )
                        }
                        item {
                            AssistChip(
                                onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                label = { Text(context.uiText(language, "b2_files_import_photos")) },
                                leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp)) }
                            )
                        }
                        item {
                            AssistChip(
                                onClick = onOpenSmartPack,
                                label = { Text(context.uiText(language, "b2_files_duplicates")) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)) }
                            )
                        }
                        item {
                            AssistChip(
                                onClick = ::refresh,
                                label = { Text(context.uiText(language, "b2_files_refresh")) },
                                leadingIcon = { Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)) }
                            )
                        }
                        item {
                            AssistChip(
                                onClick = { tagChooser = true },
                                label = { Text(activeTag?.let { "#$it" } ?: "Tags") },
                                leadingIcon = { Icon(Icons.Default.Label, null, Modifier.size(18.dp)) }
                            )
                        }
                        item {
                            AssistChip(
                                onClick = { defaultFolderDialog = true },
                                label = { Text(context.uiText(language, "b2_files_save_folder")) },
                                leadingIcon = { Icon(Icons.Default.Save, null, Modifier.size(18.dp)) }
                            )
                        }
                        item {
                            AssistChip(
                                onClick = { if (selectionMode) clearSelection() else selectionMode = true },
                                label = { Text(if (selectionMode) context.uiText(language, "b2_files_selection_cancel") else context.uiText(language, "b2_files_selection_start")) },
                                leadingIcon = { Icon(if (selectionMode) Icons.Default.Close else Icons.Default.Check, null, Modifier.size(18.dp)) }
                            )
                        }
                        item {
                            Box {
                                AssistChip(
                                    onClick = { sortMenu = true },
                                    label = { Text(context.uiText(language, sort.titleKey())) },
                                    leadingIcon = { Icon(Icons.Default.Sort, null, Modifier.size(18.dp)) }
                                )
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    FileSort.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(context.uiText(language, option.titleKey())) },
                                            leadingIcon = { if (sort == option) Icon(Icons.Default.Check, null) },
                                            onClick = {
                                                sort = option
                                                sortAscending = option == FileSort.NAME || option == FileSort.TYPE
                                                sortMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            AssistChip(
                                onClick = { sortAscending = !sortAscending },
                                label = { Text(if (sortAscending) context.uiText(language, "b2_files_ascending") else context.uiText(language, "b2_files_descending")) },
                                leadingIcon = { Icon(if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, Modifier.size(18.dp)) }
                            )
                        }
                    }
                }
            }

            if (selectionMode) {
                val selected = selectedFiles()
                Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .55f)) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item { Text(context.uiText(language, "b2_files_selected_count", selected.size), fontWeight = FontWeight.Bold) }
                        item {
                            AssistChip(
                                onClick = {
                                    selectedPaths = shown.filter { it.isFile }.map { it.absolutePath }.toSet()
                                    selectionMode = selectedPaths.isNotEmpty()
                                },
                                label = { Text(context.uiText(language, "b2_files_select_all")) },
                                leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                            )
                        }
                        if (selected.isNotEmpty()) {
                            item {
                                AssistChip(
                                    onClick = {
                                        runCatching { FileUtils.shareFiles(context, selected) }
                                            .onFailure { Toast.makeText(context, context.uiText(language, "b2_files_share_error"), Toast.LENGTH_LONG).show() }
                                    },
                                    label = { Text(context.uiText(language, "b2_files_share")) },
                                    leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(18.dp)) }
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = { transferMode = TransferMode.COPY; transferEntries = selected },
                                    label = { Text(context.uiText(language, "b2_files_copy")) },
                                    leadingIcon = { Icon(Icons.Default.FileCopy, null, Modifier.size(18.dp)) }
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = { transferMode = TransferMode.MOVE; transferEntries = selected },
                                    label = { Text(context.uiText(language, "b2_files_move")) },
                                    leadingIcon = { Icon(Icons.Default.DriveFileMove, null, Modifier.size(18.dp)) }
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = { bulkDeleteDialog = true },
                                    label = { Text(context.uiText(language, "b2_files_delete")) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)) }
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = {
                    search = it
                    if (it.isNotBlank()) { quickAccess = null; activeTag = null }
                },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, null) }
                },
                placeholder = { Text(if (searchScope == SearchScope.ALL) context.uiText(language, "b2_files_search_all_hint") else context.uiText(language, "b2_files_search_folder_hint")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchScope.entries.forEach { scope ->
                    FilterChip(
                        selected = searchScope == scope,
                        onClick = {
                            searchScope = scope
                            quickAccess = null
                        },
                        label = { Text(context.uiText(language, scope.titleKey())) },
                        leadingIcon = if (scope == SearchScope.ALL) {{ Icon(Icons.Default.TravelExplore, null, Modifier.size(18.dp)) }} else null
                    )
                }
            }

            if (currentDir.canonicalFile == root.canonicalFile && search.isBlank() && !selectionMode) {
                Text(
                    context.uiText(language, "b2_files_quick_access"),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(QuickAccess.entries) { item ->
                        FilterChip(
                            selected = quickAccess == item,
                            onClick = {
                                quickAccess = if (quickAccess == item) null else item
                                activeTag = null
                                filter = FileFilter.ALL
                                searchScope = SearchScope.CURRENT
                            },
                            label = { Text(context.uiText(language, item.titleKey())) },
                            leadingIcon = { Icon(item.icon, null, Modifier.size(18.dp)) }
                        )
                    }
                }
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(FileFilter.entries) { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(context.uiText(language, item.titleKey())) },
                        leadingIcon = if (item == FileFilter.FAVORITES) {{ Icon(Icons.Default.Favorite, null, Modifier.size(18.dp)) }} else null
                    )
                }
            }

            if (directoryBusy || searchBusy || quickBusy || operationBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            val showLocation = (searchScope == SearchScope.ALL && search.isNotBlank()) || quickAccess != null || activeTag != null
            Text(
                context.uiText(
                    language,
                    "b2_files_counts",
                    shown.count { it.isDirectory },
                    shown.count { it.isFile },
                    if (matchedContentPaths.isNotEmpty()) context.uiText(language, "b2_files_content_count", matchedContentPaths.size) else ""
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            if (shown.isEmpty() && !(directoryBusy || searchBusy || quickBusy)) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        if (quickAccess == QuickAccess.RECENT) Icons.Default.History else Icons.Default.FolderOpen,
                        null,
                        modifier = Modifier.size(90.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when {
                            activeTag != null -> context.uiText(language, "b2_files_empty_tag")
                            quickAccess == QuickAccess.RECENT -> context.uiText(language, "b2_files_empty_recent")
                            quickAccess != null -> context.uiText(language, "b2_files_empty_quick")
                            search.isNotBlank() -> context.uiText(language, "b2_files_empty_results")
                            entries.isEmpty() -> context.uiText(language, "b2_files_empty_folder")
                            else -> context.uiText(language, "b2_files_empty_results")
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(context.uiText(language, "b2_files_empty_help"), style = MaterialTheme.typography.bodySmall)
                }
            } else if (shown.isNotEmpty()) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shown, key = { it.absolutePath }) { entry ->
                        FileManagerRow(
                            entry = entry,
                            favorite = entry.absolutePath in favorites,
                            selectionMode = selectionMode,
                            selected = entry.absolutePath in selectedPaths,
                            locationText = if (showLocation) relativePath(context, language, root, entry.parentFile ?: root) else null,
                            previewable = FileUtils.canPreview(entry),
                            tags = remember(entry.absolutePath, tagVersion) { FileTagStore.tagsFor(context, entry) },
                            contentMatched = entry.absolutePath in matchedContentPaths,
                            onOpen = { open(entry) },
                            onPreview = { preview(entry) },
                            onToggleSelect = { toggleSelection(entry) },
                            onFavorite = { toggleFavorite(entry) },
                            onShare = { if (isUsableEntry(entry, requireFile = true)) runCatching { FileUtils.shareFile(context, entry) }.onFailure { Toast.makeText(context, context.uiText(language, "b2_files_share_error"), Toast.LENGTH_LONG).show() } },
                            onCleanShare = { if (isUsableEntry(entry, requireFile = true)) onCleanShare(entry) },
                            onWhatsApp = { if (isUsableEntry(entry, requireFile = true)) runCatching { FileUtils.shareImageToWhatsApp(context, entry) }.onFailure { Toast.makeText(context, context.uiText(language, "b2_files_whatsapp_error"), Toast.LENGTH_LONG).show() } },
                            onEditPdf = { if (entry.extension.equals("pdf", true) && isUsableEntry(entry, requireFile = true)) onEditPdf(entry) },
                            onPdfTools = { if (entry.extension.equals("pdf", true) && isUsableEntry(entry, requireFile = true)) pdfQuickEntry = entry },
                            onRename = {
                                if (isUsableEntry(entry)) {
                                    renameEntry = entry
                                    renameText = if (entry.isFile) entry.nameWithoutExtension else entry.name
                                }
                            },
                            onCopy = {
                                if (isUsableEntry(entry, requireFile = true)) {
                                    transferMode = TransferMode.COPY
                                    transferEntries = listOf(entry)
                                }
                            },
                            onMove = {
                                if (isUsableEntry(entry, requireFile = true)) {
                                    transferMode = TransferMode.MOVE
                                    transferEntries = listOf(entry)
                                }
                            },
                            onDetails = { if (isUsableEntry(entry)) detailsEntry = entry },
                            onTags = {
                                if (isUsableEntry(entry)) {
                                    tagEntry = entry
                                    tagText = FileTagStore.tagsFor(context, entry).joinToString(", ")
                                }
                            },
                            onDelete = { if (isUsableEntry(entry)) deleteEntry = entry }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileManagerRow(
    entry: File,
    favorite: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    locationText: String?,
    previewable: Boolean,
    tags: Set<String>,
    contentMatched: Boolean,
    onOpen: () -> Unit,
    onPreview: () -> Unit,
    onToggleSelect: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onCleanShare: () -> Unit,
    onWhatsApp: () -> Unit,
    onEditPdf: () -> Unit,
    onPdfTools: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDetails: () -> Unit,
    onTags: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var menu by remember { mutableStateOf(false) }
    val icon = when {
        entry.isDirectory -> Icons.Default.Folder
        entry.extension.equals("pdf", true) -> Icons.Default.PictureAsPdf
        entry.extension.lowercase() in setOf("doc", "docx", "txt", "md", "json", "xml", "log") -> Icons.Default.Description
        entry.extension.lowercase() in setOf("xls", "xlsx", "csv") -> Icons.Default.TableChart
        FileUtils.isImageFile(entry) -> Icons.Default.Image
        entry.extension.lowercase() in setOf("zip", "rar", "7z") -> Icons.Default.FolderZip
        else -> Icons.Default.InsertDriveFile
    }

    Card(Modifier.fillMaxWidth().clickable(onClick = { if (selectionMode && entry.isFile) onToggleSelect() else onOpen() })) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), horizontalAlignment = if (language == AppLanguage.ARABIC) Alignment.End else Alignment.Start) {
                Text(entry.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (entry.isDirectory) context.uiText(language, "b2_files_folder_date", DateFormat.getDateInstance().format(Date(entry.lastModified())))
                    else "${formatSize(entry.length())} • ${DateFormat.getDateTimeInstance().format(Date(entry.lastModified()))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
                    maxLines = 1
                )
                if (!locationText.isNullOrBlank()) {
                    Text(
                        locationText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = .72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (contentMatched) {
                    Text(context.uiText(language, "b2_files_content_match"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (tags.isNotEmpty()) {
                    Text(tags.take(3).joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            if (selectionMode && entry.isFile) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            } else {
                IconButton(onClick = onFavorite) {
                    Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = context.uiText(language, "b2_files_favorite"))
                }
            }
            if (!selectionMode) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = context.uiText(language, "b2_files_more")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(context.uiText(language, "b2_files_open")) }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) }, onClick = { menu = false; onOpen() })
                    if (entry.isFile) {
                        if (entry.extension.equals("pdf", true)) {
                            DropdownMenuItem(text = { Text(context.uiText(language, "b2_files_edit_pdf")) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onEditPdf() })
                            DropdownMenuItem(text = { Text(context.uiText(language, "b2_files_quick_pdf")) }, leadingIcon = { Icon(Icons.Default.Build, null) }, onClick = { menu = false; onPdfTools() })
                        }
                        if (previewable) {
                            DropdownMenuItem(text = { Text(context.uiText(language, "b2_files_preview")) }, leadingIcon = { Icon(Icons.Default.Visibility, null) }, onClick = { menu = false; onPreview() })
                        }
                        if (FileUtils.isImageFile(entry)) {
                            DropdownMenuItem(
                                text = { Text(context.uiText(language, "b2_files_whatsapp")) },
                                leadingIcon = { Icon(Icons.Default.Chat, null) },
                                onClick = { menu = false; onWhatsApp() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("AB Clean Share") },
                            leadingIcon = { Icon(Icons.Default.Security, null) },
                            onClick = { menu = false; onCleanShare() }
                        )
                        DropdownMenuItem(text = { Text(context.uiText(language, "b2_files_share")) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; onShare() })
                        DropdownMenuItem(text = { Text(context.uiText(language, "b2_files_copy_to")) }, leadingIcon = { Icon(Icons.Default.FileCopy, null) }, onClick = { menu = false; onCopy() })
                        DropdownMenuItem(text = { Text(context.uiText(language, "b2_files_move_to")) }, leadingIcon = { Icon(Icons.Default.DriveFileMove, null) }, onClick = { menu = false; onMove() })
                    }
                    DropdownMenuItem(text = { Text(context.uiText(language, "b2_files_rename")) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(context.uiText(language, "b2_files_tags_menu")) }, leadingIcon = { Icon(Icons.Default.Label, null) }, onClick = { menu = false; onTags() })
                    DropdownMenuItem(text = { Text(context.uiText(language, "b2_files_details")) }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { menu = false; onDetails() })
                    DropdownMenuItem(text = { Text(context.uiText(language, "b2_files_delete")) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun FilePreviewDialog(
    entry: File,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onWhatsApp: () -> Unit
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var loading by remember(entry.absolutePath) { mutableStateOf(true) }
    var image by remember(entry.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var text by remember(entry.absolutePath) { mutableStateOf<String?>(null) }
    var error by remember(entry.absolutePath) { mutableStateOf<String?>(null) }

    val bitmapOwnedByEffect = image
    DisposableEffect(bitmapOwnedByEffect) {
        onDispose { bitmapOwnedByEffect?.takeIf { !it.isRecycled }?.recycle() }
    }

    LaunchedEffect(entry.absolutePath, entry.lastModified()) {
        loading = true
        error = null
        image = null
        text = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    FileUtils.isImageFile(entry) -> Pair(FileUtils.loadImagePreview(entry), null)
                    FileUtils.isTextFile(entry) -> Pair(null, FileUtils.readTextPreview(entry, 160 * 1024))
                    else -> throw IllegalStateException(context.uiText(language, "b2_files_preview_unsupported"))
                }
            }
        }
        result.onSuccess { (bitmap, previewText) ->
            image = bitmap
            text = previewText
            if (bitmap == null && previewText == null) error = context.uiText(language, "b2_files_preview_error")
        }.onFailure {
            error = context.uiText(language, "b2_files_preview_error")
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 520.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    loading -> CircularProgressIndicator()
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    image != null -> Image(
                        bitmap = image!!.asImageBitmap(),
                        contentDescription = entry.name,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                        contentScale = ContentScale.Fit
                    )
                    text != null -> Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState())
                    ) {
                        SelectionContainer { Text(text.orEmpty()) }
                        if (entry.length() > 160 * 1024) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                context.uiText(language, "b2_files_preview_partial"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onOpenExternal) { Text(context.uiText(language, "b2_files_open_external")) } },
        dismissButton = {
            Row {
                if (FileUtils.isImageFile(entry)) {
                    TextButton(onClick = onWhatsApp) { Text(context.uiText(language, "b2_files_whatsapp")) }
                }
                TextButton(onClick = onShare) { Text(context.uiText(language, "b2_files_share")) }
                TextButton(onClick = onDismiss) { Text(context.uiText(language, "b2_files_close")) }
            }
        }
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(value, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

private fun relativePath(context: android.content.Context, language: AppLanguage, root: File, entry: File): String {
    val relative = runCatching { root.canonicalFile.toPath().relativize(entry.canonicalFile.toPath()).toString() }.getOrDefault("")
    val base = context.uiText(language, "b2_files_path_root")
    return if (relative.isBlank()) base else "$base / ${relative.replace(File.separator, " / ")}"
}

private fun fileTypeLabel(context: android.content.Context, language: AppLanguage, entry: File): String = when {
    entry.isDirectory -> context.uiText(language, "b2_files_type_folder")
    entry.extension.equals("pdf", true) -> context.uiText(language, "b2_files_type_pdf")
    FileUtils.isImageFile(entry) -> context.uiText(language, "b2_files_type_image")
    FileUtils.isOfficeFile(entry) -> context.uiText(language, "b2_files_type_office")
    FileUtils.isTextFile(entry) -> context.uiText(language, "b2_files_type_text")
    entry.extension.lowercase() in setOf("zip", "rar", "7z") -> context.uiText(language, "b2_files_type_archive")
    else -> entry.extension.uppercase().ifBlank { context.uiText(language, "b2_files_type_file") }
}


private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024f * 1024f * 1024f))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
