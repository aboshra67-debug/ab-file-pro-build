@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.abfilepro.app.ui.screens

import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abfilepro.app.core.DeviceStorageManager
import com.abfilepro.app.core.WorkspaceStorageManager
import com.abfilepro.app.ui.i18n.LocalAppLanguage
import com.abfilepro.app.ui.i18n.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Alpha37 folder picker backed by the same persistent AB workspace used by My Files.
 * It intentionally lists folders only and never leaves the AB workspace.
 */
@Composable
fun WorkspaceFolderPickerPane(
    initialDirectory: File?,
    onCancel: () -> Unit,
    onFolderPicked: (File) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var accessGranted by remember { mutableStateOf(DeviceStorageManager.hasAllFilesAccess()) }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        accessGranted = DeviceStorageManager.hasAllFilesAccess()
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !accessGranted) {
        AllFilesAccessGate(
            onGrant = {
                runCatching { accessLauncher.launch(DeviceStorageManager.permissionIntent(context)) }
                    .onFailure { accessLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            },
            onBack = onCancel
        )
        return
    }

    val root = remember(accessGranted) { WorkspaceStorageManager.ensureDefaultWorkspace(context) }
    if (root == null) {
        Scaffold(topBar = { TopAppBar(title = { Text(context.uiText(language, "alpha37_choose_folder")) }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(context.uiText(language, "alpha37_workspace_unavailable"))
            }
        }
        return
    }

    val start = remember(root.absolutePath, initialDirectory?.absolutePath) {
        initialDirectory?.takeIf { it.isDirectory && WorkspaceStorageManager.isInsideWorkspace(root, it) } ?: root
    }
    var currentDir by remember(root.absolutePath, start.absolutePath) { mutableStateOf(start) }
    var folders by remember { mutableStateOf<List<DeviceStorageManager.Entry>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var createDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    LaunchedEffect(currentDir.absolutePath, refresh) {
        folders = withContext(Dispatchers.IO) {
            runCatching { DeviceStorageManager.listChildren(currentDir).filter { it.isDirectory } }
                .getOrDefault(emptyList())
        }
    }

    fun goBack() {
        if (currentDir.canonicalFile == root.canonicalFile) onCancel()
        else currentDir.parentFile
            ?.takeIf { WorkspaceStorageManager.isInsideWorkspace(root, it) }
            ?.let { currentDir = it }
    }
    BackHandler(onBack = ::goBack)

    if (createDialog) {
        AlertDialog(
            onDismissRequest = { createDialog = false },
            title = { Text(context.uiText(language, "alpha37_new_folder")) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it.take(60) },
                    singleLine = true,
                    label = { Text(context.uiText(language, "alpha37_folder_name")) }
                )
            },
            confirmButton = {
                Button(enabled = newFolderName.isNotBlank(), onClick = {
                    val name = newFolderName
                    createDialog = false
                    newFolderName = ""
                    scope.launch(Dispatchers.IO) {
                        runCatching { DeviceStorageManager.createFolder(currentDir, name) }
                        withContext(Dispatchers.Main) { refresh++ }
                    }
                }) { Text(context.uiText(language, "alpha37_create")) }
            },
            dismissButton = { TextButton(onClick = { createDialog = false }) { Text(context.uiText(language, "alpha36_cancel")) } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(context.uiText(language, "alpha37_choose_folder"), fontWeight = FontWeight.Bold)
                        Text(
                            currentDir.name.ifBlank { root.name },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = ::goBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { createDialog = true }) { Icon(Icons.Default.CreateNewFolder, null) }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        context.uiText(language, "alpha37_selected_folder", currentDir.absolutePath),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Button(
                        onClick = { onFolderPicked(currentDir) },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text(context.uiText(language, "alpha37_use_folder"))
                    }
                }
            }
        }
    ) { padding ->
        if (folders.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text(context.uiText(language, "alpha37_no_subfolders"))
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(folders, key = { it.path }) { entry ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { currentDir = entry.file },
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = 1.dp
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(entry.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
