package com.abfilepro.app.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Storage Access Framework (SAF) backend for user-owned files.
 *
 * This class deliberately never converts content:// URIs into filesystem paths and never asks for
 * MANAGE_EXTERNAL_STORAGE. All operations stay inside the user-selected document tree.
 */
object SafStorageManager {
    private const val PREFS = "ab_file_pro_saf_storage"
    private const val KEY_TREE = "working_tree_v1"
    private const val KEY_FAVORITES = "favorites_v1"
    private const val KEY_RECENT = "recent_v1"
    private const val MAX_RECENT = 40
    private const val LARGE_FILE_THRESHOLD = 50L * 1024L * 1024L
    private const val MAX_ZIP_ENTRIES = 100_000
    private const val MAX_ZIP_EXPANDED_BYTES = 20L * 1024L * 1024L * 1024L

    enum class PermissionState { MISSING, READ_ONLY, READ_WRITE }

    data class Entry(
        val uri: Uri,
        val parentUri: Uri?,
        val documentId: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long,
        val flags: Int
    ) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        val isFile: Boolean get() = !isDirectory
        val canDelete: Boolean get() = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0
        val canRename: Boolean get() = flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0
        val canWrite: Boolean get() = isDirectory && flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
        val canCopyNative: Boolean get() = flags and DocumentsContract.Document.FLAG_SUPPORTS_COPY != 0
        val canMoveNative: Boolean get() = flags and DocumentsContract.Document.FLAG_SUPPORTS_MOVE != 0

        fun meta(): FileManagerRules.EntryMeta = FileManagerRules.EntryMeta(
            name = name,
            isDirectory = isDirectory,
            size = size.coerceAtLeast(0L),
            lastModified = lastModified,
            mimeType = mimeType,
            id = uri.toString()
        )
    }

    data class FolderChoice(val entry: Entry, val path: String)
    data class AnalyzedFile(val entry: Entry, val path: String, val exactSize: Long, val sha256: String? = null)
    data class DuplicateGroup(val sha256: String, val size: Long, val files: List<AnalyzedFile>)
    data class Analysis(
        val totalBytes: Long,
        val fileCount: Int,
        val folderCount: Int,
        val largeFiles: List<AnalyzedFile>,
        val largestFiles: List<AnalyzedFile>,
        val duplicateGroups: List<DuplicateGroup>
    )

    class PartialMoveException(val copiedEntry: Entry, cause: Throwable) :
        IllegalStateException("move_copied_but_source_not_deleted", cause)

    private data class DocumentContent(val bytes: Long, val sha256: String)
    private data class DocumentProof(
        val entry: Entry,
        val content: DocumentContent?,
        val children: List<DocumentProof>
    )
    private class CreatedDocument(val entry: Entry) {
        var proof: DocumentProof? = null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedTreeUri(context: Context): Uri? = prefs(context).getString(KEY_TREE, null)
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::parse)

    fun persistWorkingTree(context: Context, treeUri: Uri): PermissionState {
        val resolver = context.contentResolver
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val state = try {
            resolver.takePersistableUriPermission(treeUri, read or write)
            PermissionState.READ_WRITE
        } catch (_: SecurityException) {
            resolver.takePersistableUriPermission(treeUri, read)
            PermissionState.READ_ONLY
        }
        prefs(context).edit().putString(KEY_TREE, treeUri.toString()).apply()
        // Query immediately so a stale/provider-invalid URI is never silently accepted.
        requireNotNull(queryEntry(context, rootDocumentUri(treeUri), null)) { "تعذر الوصول إلى مجلد التخزين المختار" }
        return permissionState(context, treeUri).takeUnless { it == PermissionState.MISSING } ?: state
    }

    fun clearWorkingTree(context: Context, releasePermission: Boolean = false) {
        val uri = savedTreeUri(context)
        if (releasePermission && uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
        }
        prefs(context).edit().remove(KEY_TREE).apply()
    }

    fun permissionState(context: Context, treeUri: Uri? = savedTreeUri(context)): PermissionState {
        val resolvedTreeUri = treeUri ?: return PermissionState.MISSING
        val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == resolvedTreeUri }
            ?: return PermissionState.MISSING
        if (!grant.isReadPermission) return PermissionState.MISSING
        return if (grant.isWritePermission) PermissionState.READ_WRITE else PermissionState.READ_ONLY
    }

    fun isWorkingTreeAccessible(context: Context): Boolean {
        val tree = savedTreeUri(context) ?: return false
        if (permissionState(context, tree) == PermissionState.MISSING) return false
        return runCatching { queryEntry(context, rootDocumentUri(tree), null) != null }.getOrDefault(false)
    }

    fun rootDocumentUri(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri)
    )

    fun queryEntry(context: Context, documentUri: Uri, parentUri: Uri? = null): Entry? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        val cursor = requireNotNull(context.contentResolver.query(documentUri, projection, null, null, null)) {
            "تعذر قراءة العنصر من مزود التخزين"
        }
        return cursor.use {
            if (!cursor.moveToFirst()) return@use null
            val documentId = cursor.string(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                ?: runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrDefault(documentUri.toString())
            Entry(
                uri = documentUri,
                parentUri = parentUri,
                documentId = documentId,
                name = cursor.string(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    ?.takeIf { it.isNotBlank() }
                    ?: documentId.substringAfterLast(':').ifBlank { "Storage" },
                mimeType = cursor.string(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    ?: "application/octet-stream",
                size = cursor.longOr(DocumentsContract.Document.COLUMN_SIZE, -1L),
                lastModified = cursor.longOr(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0L),
                flags = cursor.intOr(DocumentsContract.Document.COLUMN_FLAGS, 0)
            )
        }
    }

    fun listChildren(context: Context, parentUri: Uri): List<Entry> {
        val parentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        val result = ArrayList<Entry>()
        val documentIds = HashSet<String>()
        val cursor = requireNotNull(context.contentResolver.query(childrenUri, projection, null, null, null)) {
            "تعذر قراءة محتويات المجلد من مزود التخزين"
        }
        cursor.use {
            while (cursor.moveToNext()) {
                val documentId = requireNotNull(cursor.string(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) {
                    "تعذر التحقق من عنصر داخل المجلد"
                }
                require(documentId.isNotBlank() && documentIds.add(documentId)) {
                    "أعاد مزود التخزين قائمة عناصر غير صالحة"
                }
                val childUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, documentId)
                result += Entry(
                    uri = childUri,
                    parentUri = parentUri,
                    documentId = documentId,
                    name = cursor.string(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        ?.takeIf { it.isNotBlank() }
                        ?: documentId.substringAfterLast(':').ifBlank { "item" },
                    mimeType = cursor.string(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        ?: "application/octet-stream",
                    size = cursor.longOr(DocumentsContract.Document.COLUMN_SIZE, -1L),
                    lastModified = cursor.longOr(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0L),
                    flags = cursor.intOr(DocumentsContract.Document.COLUMN_FLAGS, 0)
                )
            }
        }
        return result
    }

    fun createFolder(context: Context, parentUri: Uri, rawName: String): Entry {
        val parent = requireNotNull(queryEntry(context, parentUri)) { "المجلد الهدف غير متاح" }
        require(parent.isDirectory) { "المجلد الهدف غير صالح" }
        val requested = FileManagerRules.safeName(rawName)
        require(requested.isNotBlank()) { "اكتب اسم مجلد صحيح" }
        val existing = listChildren(context, parentUri).map { it.name }
        require(existing.none { it.equals(requested, true) }) { "يوجد عنصر بنفس الاسم" }
        val uri = requireNotNull(
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                requested
            )
        ) { "تعذر إنشاء المجلد" }
        return requireNotNull(queryEntry(context, uri, parentUri)) { "تم إنشاء المجلد لكن تعذر قراءته" }
    }

    fun rename(context: Context, entry: Entry, rawName: String): Entry {
        val parentUri = requireNotNull(entry.parentUri) { "لا يمكن إعادة تسمية جذر التخزين" }
        val requested = FileManagerRules.safeName(rawName)
        require(requested.isNotBlank()) { "اكتب اسمًا صحيحًا" }
        if (requested == entry.name) return entry
        val siblingNames = listChildren(context, parentUri)
            .filterNot { it.uri == entry.uri }
            .map { it.name }
        require(siblingNames.none { it.equals(requested, true) }) { "يوجد عنصر بنفس الاسم" }
        val newUri = requireNotNull(DocumentsContract.renameDocument(context.contentResolver, entry.uri, requested)) {
            "تعذر إعادة التسمية"
        }
        val renamed = requireNotNull(queryEntry(context, newUri, parentUri)) { "تعذر قراءة العنصر بعد إعادة التسمية" }
        remapSavedUri(context, entry.uri, renamed.uri)
        return renamed
    }

    fun delete(context: Context, entry: Entry): Boolean {
        require(entry.parentUri != null) { "لا يمكن حذف جذر التخزين" }
        val deleted = DocumentsContract.deleteDocument(context.contentResolver, entry.uri)
        if (deleted) removeSavedUri(context, entry.uri)
        return deleted
    }

    suspend fun copy(context: Context, source: Entry, destinationFolder: Entry): Entry {
        require(destinationFolder.isDirectory) { "المجلد الهدف غير صالح" }
        requireSafeDestination(context, source, destinationFolder)
        currentCoroutineContext().ensureActive()
        val destinationChildren = listChildren(context, destinationFolder.uri)
        val exactNameFree = destinationChildren.none { it.name.equals(source.name, true) }
        if (exactNameFree && source.canCopyNative && source.uri.authority == destinationFolder.uri.authority) {
            val native = runCatching {
                DocumentsContract.copyDocument(context.contentResolver, source.uri, destinationFolder.uri)
            }.getOrNull()
            if (native != null) {
                return queryEntry(context, native, destinationFolder.uri)
                    ?: Entry(native, destinationFolder.uri, DocumentsContract.getDocumentId(native), source.name, source.mimeType, source.size, source.lastModified, source.flags)
            }
        }
        val targetName = FileManagerRules.uniqueName(source.name, destinationChildren.map { it.name })
        return copyFallback(context, captureDocumentTree(context, source), destinationFolder.uri, targetName).entry
    }

    suspend fun move(context: Context, source: Entry, destinationFolder: Entry): Entry {
        require(destinationFolder.isDirectory) { "المجلد الهدف غير صالح" }
        requireSafeDestination(context, source, destinationFolder)
        val sourceParent = requireNotNull(source.parentUri) { "لا يمكن نقل جذر التخزين" }
        require(sourceParent != destinationFolder.uri) { "العنصر موجود بالفعل داخل هذا المجلد" }
        currentCoroutineContext().ensureActive()
        val destinationChildren = listChildren(context, destinationFolder.uri)
        val exactNameFree = destinationChildren.none { it.name.equals(source.name, true) }
        if (exactNameFree && source.canMoveNative && source.uri.authority == destinationFolder.uri.authority) {
            val native = DocumentsContract.moveDocument(context.contentResolver, source.uri, sourceParent, destinationFolder.uri)
            if (native != null) {
                val moved = queryEntry(context, native, destinationFolder.uri)
                    ?: Entry(native, destinationFolder.uri, DocumentsContract.getDocumentId(native), source.name, source.mimeType, source.size, source.lastModified, source.flags)
                remapSavedUri(context, source.uri, moved.uri)
                return moved
            }
        }
        val targetName = FileManagerRules.uniqueName(source.name, destinationChildren.map { it.name })
        // A successful stream copy is insufficient proof for a destructive move.
        // Freeze identities, child membership and actual bytes before creating any output.
        val sourceProof = captureDocumentTree(context, source)
        val copiedProof = copyFallback(context, sourceProof, destinationFolder.uri, targetName)
        val copied = copiedProof.entry
        try {
            currentCoroutineContext().ensureActive()
            require(captureDocumentTree(context, source) == sourceProof) { "تغير الأصل بعد النسخ؛ تم الاحتفاظ به" }
            require(captureDocumentTree(context, copied) == copiedProof) { "تعذر التحقق من اكتمال النسخ" }
            deleteProvenTree(context, sourceProof)
            remapSavedUri(context, source.uri, copied.uri)
            return copied
        } catch (error: Throwable) {
            // Never delete the completed copy when source deletion fails: preserve both copies and report partial move.
            throw PartialMoveException(copied, error)
        }
    }


    private suspend fun requireSafeDestination(context: Context, source: Entry, destinationFolder: Entry) {
        if (!source.isDirectory) return
        require(source.uri != destinationFolder.uri) { "لا يمكن نسخ أو نقل المجلد داخل نفسه" }
        val queue = ArrayDeque<Entry>()
        val visited = HashSet<String>()
        queue.add(source)
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val folder = queue.removeFirst()
            if (!visited.add(folder.documentId)) continue
            for (child in listChildren(context, folder.uri)) {
                if (!child.isDirectory) continue
                require(child.uri != destinationFolder.uri) { "لا يمكن نسخ أو نقل المجلد داخل أحد مجلداته الفرعية" }
                queue.add(child)
            }
        }
    }

    private suspend fun captureDocumentTree(context: Context, root: Entry): DocumentProof {
        val visited = HashSet<String>()
        suspend fun visit(expected: Entry): DocumentProof {
            currentCoroutineContext().ensureActive()
            val before = requireNotNull(queryEntry(context, expected.uri, expected.parentUri)) { "تعذر قراءة الأصل للتحقق" }
            require(before.documentId == DocumentsContract.getDocumentId(before.uri)) { "هوية عنصر التخزين غير صالحة" }
            require(visited.add(documentKey(before.uri))) { "تحتوي شجرة التخزين على عناصر متكررة" }
            val children = if (before.isDirectory) listChildren(context, before.uri).sortedBy { it.documentId } else emptyList()
            val proofs = children.map { child ->
                visit(child).also { require(it.entry == child) { "تغير عنصر أثناء التحقق" } }
            }
            val content = if (before.isFile) readDocumentContent(context, before) else null
            require(queryEntry(context, before.uri, before.parentUri) == before) { "تغير عنصر أثناء التحقق" }
            if (before.isDirectory) {
                require(listChildren(context, before.uri).sortedBy { it.documentId } == children) { "تغيرت محتويات المجلد أثناء التحقق" }
            }
            return DocumentProof(before, content, proofs)
        }
        return visit(root)
    }

    private suspend fun readDocumentContent(context: Context, entry: Entry): DocumentContent {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        context.contentResolver.openInputStream(entry.uri).use { input ->
            requireNotNull(input) { "تعذر قراءة ${entry.name}" }
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                bytes += count
            }
        }
        return DocumentContent(bytes, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun documentKey(uri: Uri): String = "${uri.authority}:${DocumentsContract.getDocumentId(uri)}"

    private fun sameDocument(left: Entry, right: Entry): Boolean =
        left.uri == right.uri && left.documentId == right.documentId && left.name == right.name && left.mimeType == right.mimeType

    private suspend fun copyFallback(context: Context, source: DocumentProof, destinationParentUri: Uri, requestedName: String): DocumentProof {
        val resolver = context.contentResolver
        val created = ArrayList<CreatedDocument>()
        val protectedIds = HashSet<String>()
        fun protect(proof: DocumentProof) {
            protectedIds += documentKey(proof.entry.uri)
            proof.children.forEach(::protect)
        }
        protect(source)
        val createdIds = HashSet<String>()
        suspend fun copyNode(proof: DocumentProof, parentUri: Uri, name: String): DocumentProof {
            currentCoroutineContext().ensureActive()
            val existing = listChildren(context, parentUri)
            val mime = if (proof.entry.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else
                proof.entry.mimeType.takeUnless { it.isBlank() } ?: mimeTypeForName(proof.entry.name)
            val uri = requireNotNull(DocumentsContract.createDocument(resolver, parentUri, mime, name)) { "تعذر إنشاء العنصر الهدف" }
            val key = documentKey(uri)
            require(key !in protectedIds && existing.none { documentKey(it.uri) == key } && createdIds.add(key)) {
                "لم ينشئ مزود التخزين عنصرًا جديدًا آمنًا"
            }
            val entry = requireNotNull(queryEntry(context, uri, parentUri)) { "تعذر قراءة العنصر الهدف" }
            require(entry.documentId == DocumentsContract.getDocumentId(uri) && entry.isDirectory == proof.entry.isDirectory) {
                "أعاد مزود التخزين عنصرًا هدفًا غير صالح"
            }
            val owned = CreatedDocument(entry).also { created += it }
            val children = ArrayList<DocumentProof>()
            if (entry.isDirectory) {
                for (child in proof.children) {
                    val names = listChildren(context, uri).map { it.name }
                    children += copyNode(child, uri, FileManagerRules.uniqueName(child.entry.name, names))
                }
            } else {
                val digest = MessageDigest.getInstance("SHA-256")
                val bytes = resolver.openInputStream(proof.entry.uri).use { input ->
                    requireNotNull(input) { "تعذر قراءة الملف المصدر" }
                    resolver.openOutputStream(uri, "w").use { output ->
                        requireNotNull(output) { "تعذر الكتابة إلى الملف الهدف" }
                        val written = RuntimeSafety.copyCancellable(input, DigestOutputStream(output, digest))
                        output.flush()
                        written
                    }
                }
                val written = DocumentContent(bytes, digest.digest().joinToString("") { "%02x".format(it) })
                require(written == proof.content) { "تغير الملف المصدر أثناء النسخ" }
            }
            val completed = DocumentProof(
                requireNotNull(queryEntry(context, uri, parentUri)) { "تعذر قراءة العنصر بعد النسخ" },
                proof.content,
                children.sortedBy { it.entry.documentId }
            )
            owned.proof = completed
            return completed
        }
        try {
            val copied = copyNode(source, destinationParentUri, requestedName)
            require(captureDocumentTree(context, copied.entry) == copied) { "تعذر التحقق من اكتمال النسخ" }
            return copied
        } catch (error: Throwable) {
            // Never recursively remove a created folder: another writer may have added data.
            // Unfinished or changed files have no reliable ownership proof and remain intact.
            withContext(NonCancellable) {
                for (owned in created.asReversed()) {
                    try {
                        val now = queryEntry(context, owned.entry.uri, owned.entry.parentUri) ?: continue
                        if (!sameDocument(now, owned.entry)) continue
                        if (now.isDirectory) {
                            if (listChildren(context, now.uri).isNotEmpty()) continue
                        } else {
                            val proof = owned.proof ?: continue
                            if (captureDocumentTree(context, now) != proof) continue
                        }
                        require(DocumentsContract.deleteDocument(resolver, now.uri)) { "تعذر تنظيف ناتج النسخ" }
                    } catch (cleanupError: Exception) {
                        error.addSuppressed(cleanupError)
                    }
                }
            }
            throw error
        }
    }

    private suspend fun deleteProvenTree(context: Context, proof: DocumentProof) {
        for (child in proof.children) deleteProvenTree(context, child)
        currentCoroutineContext().ensureActive()
        if (proof.entry.isDirectory) {
            val current = requireNotNull(queryEntry(context, proof.entry.uri, proof.entry.parentUri)) { "تعذر التحقق من المجلد الأصل" }
            // Own child removals change directory metadata. Delete only the same, now-empty document.
            require(sameDocument(current, proof.entry) && listChildren(context, current.uri).isEmpty()) { "تغير المجلد الأصل؛ تم الاحتفاظ به" }
        } else {
            require(captureDocumentTree(context, proof.entry) == proof) { "تغير الملف الأصل؛ تم الاحتفاظ به" }
        }
        currentCoroutineContext().ensureActive()
        require(DocumentsContract.deleteDocument(context.contentResolver, proof.entry.uri)) { "تعذر حذف الأصل بعد النسخ" }
    }

    suspend fun createZip(context: Context, parentFolder: Entry, selected: List<Entry>, rawName: String = "Archive.zip"): Entry {
        require(parentFolder.isDirectory) { "المجلد الهدف غير صالح" }
        require(selected.isNotEmpty()) { "اختر ملفًا واحدًا على الأقل" }
        val existing = listChildren(context, parentFolder.uri).map { it.name }
        val safeBase = FileManagerRules.safeName(rawName).ifBlank { "Archive.zip" }
        val requested = if (safeBase.endsWith(".zip", true)) safeBase else "$safeBase.zip"
        val targetName = FileManagerRules.uniqueName(requested, existing)
        val targetUri = requireNotNull(
            DocumentsContract.createDocument(context.contentResolver, parentFolder.uri, "application/zip", targetName)
        ) { "تعذر إنشاء ملف ZIP" }
        try {
            context.contentResolver.openOutputStream(targetUri, "w").use { raw ->
                requireNotNull(raw) { "تعذر فتح ملف ZIP للكتابة" }
                ZipOutputStream(raw.buffered()).use { zip ->
                    for (entry in selected) {
                        currentCoroutineContext().ensureActive()
                        addToZip(context, zip, entry, entry.name)
                    }
                }
            }
            return requireNotNull(queryEntry(context, targetUri, parentFolder.uri)) { "تعذر قراءة ملف ZIP الناتج" }
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, targetUri) }
            throw error
        }
    }

    private suspend fun addToZip(context: Context, zip: ZipOutputStream, entry: Entry, rawPath: String) {
        currentCoroutineContext().ensureActive()
        val path = rawPath.replace('\\', '/').trimStart('/')
        require(FileManagerRules.isSafeZipPath(path)) { "مسار ZIP غير آمن" }
        if (entry.isDirectory) {
            val dirName = path.trimEnd('/') + "/"
            zip.putNextEntry(ZipEntry(dirName).apply { if (entry.lastModified > 0) time = entry.lastModified })
            zip.closeEntry()
            for (child in listChildren(context, entry.uri)) {
                currentCoroutineContext().ensureActive()
                addToZip(context, zip, child, "$dirName${child.name}")
            }
        } else {
            zip.putNextEntry(ZipEntry(path).apply { if (entry.lastModified > 0) time = entry.lastModified })
            context.contentResolver.openInputStream(entry.uri).use { input ->
                requireNotNull(input) { "تعذر قراءة ${entry.name}" }
                RuntimeSafety.copyCancellable(input, zip)
            }
            zip.closeEntry()
        }
    }

    suspend fun extractZip(context: Context, zipEntry: Entry, destinationParent: Entry): Entry {
        require(zipEntry.isFile && (zipEntry.mimeType == "application/zip" || zipEntry.name.endsWith(".zip", true))) { "اختر ملف ZIP صالح" }
        require(destinationParent.isDirectory) { "المجلد الهدف غير صالح" }
        val existing = listChildren(context, destinationParent.uri).map { it.name }
        val baseName = zipEntry.name.substringBeforeLast('.', zipEntry.name).ifBlank { "Extracted" }
        val folderName = FileManagerRules.uniqueName(FileManagerRules.safeName(baseName).ifBlank { "Extracted" }, existing)
        val rootUri = requireNotNull(
            DocumentsContract.createDocument(
                context.contentResolver,
                destinationParent.uri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                folderName
            )
        ) { "تعذر إنشاء مجلد فك الضغط" }
        val resolver = context.contentResolver
        val folderCache = mutableMapOf("" to rootUri)
        var entryCount = 0
        var expandedBytes = 0L
        try {
            resolver.openInputStream(zipEntry.uri).use { raw ->
                requireNotNull(raw) { "تعذر قراءة ملف ZIP" }
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val item = zip.nextEntry ?: break
                        entryCount++
                        require(entryCount <= MAX_ZIP_ENTRIES) { "ملف ZIP يحتوي عدد عناصر غير آمن" }
                        val normalized = item.name.replace('\\', '/').trimEnd('/')
                        require(FileManagerRules.isSafeZipPath(normalized)) { "تم إيقاف فك ZIP بسبب مسار غير آمن" }
                        val parts = normalized.split('/').filter { it.isNotBlank() }
                        require(parts.isNotEmpty()) { "مسار ZIP فارغ" }
                        var parentPath = ""
                        var parentUri = rootUri
                        for (part in parts.dropLast(1)) {
                            currentCoroutineContext().ensureActive()
                            parentPath = if (parentPath.isEmpty()) part else "$parentPath/$part"
                            parentUri = folderCache[parentPath] ?: run {
                                val children = listChildren(context, parentUri)
                                val existingDir = children.firstOrNull { it.isDirectory && it.name == part }
                                val uri = existingDir?.uri ?: requireNotNull(
                                    DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, FileManagerRules.safeName(part).ifBlank { "folder" })
                                ) { "تعذر إنشاء مجلد داخل ZIP" }
                                folderCache[parentPath] = uri
                                uri
                            }
                        }
                        val leaf = FileManagerRules.safeName(parts.last()).ifBlank { "file" }
                        val fullPath = parts.joinToString("/")
                        if (item.isDirectory || item.name.endsWith('/')) {
                            val existingDir = listChildren(context, parentUri).firstOrNull { it.isDirectory && it.name == leaf }
                            val dirUri = existingDir?.uri ?: requireNotNull(
                                DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, leaf)
                            ) { "تعذر إنشاء مجلد داخل ZIP" }
                            folderCache[fullPath] = dirUri
                        } else {
                            val targetName = FileManagerRules.uniqueName(leaf, listChildren(context, parentUri).map { it.name })
                            val fileUri = requireNotNull(
                                DocumentsContract.createDocument(resolver, parentUri, mimeTypeForName(targetName), targetName)
                            ) { "تعذر إنشاء ملف أثناء فك ZIP" }
                            try {
                                resolver.openOutputStream(fileUri, "w").use { output ->
                                    requireNotNull(output) { "تعذر الكتابة أثناء فك ZIP" }
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val read = zip.read(buffer)
                                        if (read <= 0) break
                                        expandedBytes += read
                                        require(expandedBytes <= MAX_ZIP_EXPANDED_BYTES) { "تم إيقاف ZIP لحماية مساحة التخزين" }
                                        output.write(buffer, 0, read)
                                    }
                                    output.flush()
                                }
                            } catch (error: Throwable) {
                                runCatching { DocumentsContract.deleteDocument(resolver, fileUri) }
                                throw error
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            return requireNotNull(queryEntry(context, rootUri, destinationParent.uri)) { "تعذر قراءة مجلد فك ZIP" }
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, rootUri) }
            throw error
        }
    }

    suspend fun extractZipHere(context: Context, zipEntry: Entry, destinationParent: Entry): Int {
        require(zipEntry.isFile && (zipEntry.mimeType == "application/zip" || zipEntry.name.endsWith(".zip", true))) { "اختر ملف ZIP صالح" }
        require(destinationParent.isDirectory) { "المجلد الهدف غير صالح" }
        val resolver = context.contentResolver
        val folderCache = mutableMapOf("" to destinationParent.uri)
        val createdUris = ArrayList<Uri>()
        var entryCount = 0
        var expandedBytes = 0L
        var extractedFiles = 0
        try {
            resolver.openInputStream(zipEntry.uri).use { raw ->
                requireNotNull(raw) { "تعذر قراءة ملف ZIP" }
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val item = zip.nextEntry ?: break
                        entryCount++
                        require(entryCount <= MAX_ZIP_ENTRIES) { "ملف ZIP يحتوي عدد عناصر غير آمن" }
                        val normalized = item.name.replace('\\', '/').trimEnd('/')
                        require(FileManagerRules.isSafeZipPath(normalized)) { "تم إيقاف فك ZIP بسبب مسار غير آمن" }
                        val parts = normalized.split('/').filter { it.isNotBlank() }
                        require(parts.isNotEmpty()) { "مسار ZIP فارغ" }

                        var parentPath = ""
                        var parentUri = destinationParent.uri
                        for (part in parts.dropLast(1)) {
                            currentCoroutineContext().ensureActive()
                            parentPath = if (parentPath.isEmpty()) part else "$parentPath/$part"
                            parentUri = folderCache[parentPath] ?: run {
                                val safe = FileManagerRules.safeName(part).ifBlank { "folder" }
                                val unique = FileManagerRules.uniqueName(safe, listChildren(context, parentUri).map { it.name })
                                val uri = requireNotNull(
                                    DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, unique)
                                ) { "تعذر إنشاء مجلد داخل ZIP" }
                                createdUris += uri
                                folderCache[parentPath] = uri
                                uri
                            }
                        }

                        val leaf = FileManagerRules.safeName(parts.last()).ifBlank { "file" }
                        val fullPath = parts.joinToString("/")
                        if (item.isDirectory || item.name.endsWith('/')) {
                            if (folderCache[fullPath] == null) {
                                val unique = FileManagerRules.uniqueName(leaf, listChildren(context, parentUri).map { it.name })
                                val dirUri = requireNotNull(
                                    DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, unique)
                                ) { "تعذر إنشاء مجلد داخل ZIP" }
                                createdUris += dirUri
                                folderCache[fullPath] = dirUri
                            }
                        } else {
                            val targetName = FileManagerRules.uniqueName(leaf, listChildren(context, parentUri).map { it.name })
                            val fileUri = requireNotNull(
                                DocumentsContract.createDocument(resolver, parentUri, mimeTypeForName(targetName), targetName)
                            ) { "تعذر إنشاء ملف أثناء فك ZIP" }
                            createdUris += fileUri
                            try {
                                resolver.openOutputStream(fileUri, "w").use { output ->
                                    requireNotNull(output) { "تعذر الكتابة أثناء فك ZIP" }
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val read = zip.read(buffer)
                                        if (read <= 0) break
                                        expandedBytes += read
                                        require(expandedBytes <= MAX_ZIP_EXPANDED_BYTES) { "تم إيقاف ZIP لحماية مساحة التخزين" }
                                        output.write(buffer, 0, read)
                                    }
                                    output.flush()
                                }
                                extractedFiles++
                            } catch (error: Throwable) {
                                runCatching { DocumentsContract.deleteDocument(resolver, fileUri) }
                                createdUris.remove(fileUri)
                                throw error
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            return extractedFiles
        } catch (error: Throwable) {
            for (uri in createdUris.asReversed()) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw error
        }
    }

    suspend fun listFolders(context: Context, treeUri: Uri = savedTreeUri(context) ?: error("لم يتم اختيار مجلد تخزين")): List<FolderChoice> {
        val rootUri = rootDocumentUri(treeUri)
        val root = requireNotNull(queryEntry(context, rootUri, null)) { "تعذر قراءة جذر التخزين" }
        val result = ArrayList<FolderChoice>()
        val queue = ArrayDeque<Pair<Entry, String>>()
        val visited = HashSet<String>()
        queue.add(root to root.name)
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (folder, path) = queue.removeFirst()
            if (!visited.add(folder.documentId)) continue
            result += FolderChoice(folder, path)
            for (child in listChildren(context, folder.uri)) {
                if (child.isDirectory) queue.add(child to "$path/${child.name}")
            }
        }
        return result
    }

    suspend fun analyze(context: Context, treeUri: Uri = savedTreeUri(context) ?: error("لم يتم اختيار مجلد تخزين")): Analysis {
        val rootUri = rootDocumentUri(treeUri)
        val root = requireNotNull(queryEntry(context, rootUri, null)) { "تعذر قراءة جذر التخزين" }
        val queue = ArrayDeque<Pair<Entry, String>>()
        val visited = HashSet<String>()
        val files = ArrayList<AnalyzedFile>()
        var folderCount = 0
        var total = 0L
        queue.add(root to root.name)
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (folder, path) = queue.removeFirst()
            if (!visited.add(folder.documentId)) continue
            folderCount++
            for (child in listChildren(context, folder.uri)) {
                currentCoroutineContext().ensureActive()
                val childPath = "$path/${child.name}"
                if (child.isDirectory) {
                    queue.add(child to childPath)
                } else {
                    val size = try {
                        exactSize(context, child)
                    } catch (_: FileNotFoundException) {
                        // A document provider may report an item that disappears before it is opened.
                        // Treat that as a concurrent removal, not as analyzer corruption.
                        continue
                    }
                    total += size
                    files += AnalyzedFile(child, childPath, size)
                }
            }
        }

        val groupedBySize = files.groupBy { it.exactSize }.filterValues { it.size > 1 }
        val duplicates = ArrayList<DuplicateGroup>()
        for ((size, candidates) in groupedBySize) {
            currentCoroutineContext().ensureActive()
            val hashed = ArrayList<AnalyzedFile>(candidates.size)
            for (file in candidates) {
                currentCoroutineContext().ensureActive()
                try {
                    hashed += file.copy(sha256 = sha256(context, file.entry))
                } catch (_: FileNotFoundException) {
                    // File disappeared while analysis was running; omit it from duplicate results.
                }
            }
            for ((hash, group) in hashed.groupBy { it.sha256.orEmpty() }) {
                if (hash.isNotBlank() && group.size > 1) duplicates += DuplicateGroup(hash, size, group)
            }
        }

        return Analysis(
            totalBytes = total,
            fileCount = files.size,
            folderCount = (folderCount - 1).coerceAtLeast(0),
            largeFiles = files.filter { it.exactSize >= LARGE_FILE_THRESHOLD }.sortedByDescending { it.exactSize },
            largestFiles = files.sortedByDescending { it.exactSize }.take(30),
            duplicateGroups = duplicates.sortedByDescending { it.size * it.files.size }
        )
    }

    private suspend fun exactSize(context: Context, entry: Entry): Long {
        if (entry.size >= 0L) return entry.size
        val resolver = context.contentResolver
        val descriptorLength = runCatching { resolver.openAssetFileDescriptor(entry.uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
        if (descriptorLength >= 0L) return descriptorLength
        var total = 0L
        resolver.openInputStream(entry.uri).use { input ->
            requireNotNull(input) { "تعذر قراءة ${entry.name}" }
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
            }
        }
        return total
    }

    private suspend fun sha256(context: Context, entry: Entry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(entry.uri).use { input ->
            requireNotNull(input) { "تعذر قراءة ${entry.name}" }
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun favoriteUris(context: Context): Set<String> = prefs(context).getStringSet(KEY_FAVORITES, emptySet()).orEmpty().toSet()

    fun isFavorite(context: Context, entry: Entry): Boolean = entry.uri.toString() in favoriteUris(context)

    fun toggleFavorite(context: Context, entry: Entry): Boolean {
        val current = favoriteUris(context).toMutableSet()
        val key = entry.uri.toString()
        val nowFavorite = if (key in current) {
            current.remove(key)
            false
        } else {
            current.add(key)
            true
        }
        prefs(context).edit().putStringSet(KEY_FAVORITES, current).apply()
        return nowFavorite
    }

    fun favoriteEntries(context: Context): List<Entry> {
        val valid = ArrayList<Entry>()
        val invalid = HashSet<String>()
        favoriteUris(context).forEach { raw ->
            val entry = runCatching { queryEntry(context, Uri.parse(raw), null) }.getOrNull()
            if (entry == null) invalid += raw else valid += entry
        }
        if (invalid.isNotEmpty()) {
            prefs(context).edit().putStringSet(KEY_FAVORITES, favoriteUris(context) - invalid).apply()
        }
        return valid
    }

    fun recordRecent(context: Context, entry: Entry) {
        if (!entry.isFile) return
        val current = parseRecent(context).filterNot { it.second.toString() == entry.uri.toString() }.toMutableList()
        current.add(0, System.currentTimeMillis() to entry.uri)
        writeRecent(context, current.take(MAX_RECENT))
    }

    fun recentEntries(context: Context): List<Entry> {
        val validPairs = ArrayList<Pair<Long, Uri>>()
        val entries = ArrayList<Entry>()
        parseRecent(context).forEach { pair ->
            val entry = runCatching { queryEntry(context, pair.second, null) }.getOrNull()
            if (entry != null && entry.isFile) {
                validPairs += pair
                entries += entry
            }
        }
        writeRecent(context, validPairs.take(MAX_RECENT))
        return entries
    }

    private fun parseRecent(context: Context): List<Pair<Long, Uri>> = prefs(context).getStringSet(KEY_RECENT, emptySet()).orEmpty()
        .mapNotNull { raw ->
            val separator = raw.indexOf('|')
            if (separator <= 0) return@mapNotNull null
            val timestamp = raw.substring(0, separator).toLongOrNull() ?: return@mapNotNull null
            val uri = runCatching { Uri.parse(Uri.decode(raw.substring(separator + 1))) }.getOrNull() ?: return@mapNotNull null
            timestamp to uri
        }
        .sortedByDescending { it.first }

    private fun writeRecent(context: Context, values: List<Pair<Long, Uri>>) {
        val encoded = values.distinctBy { it.second.toString() }.take(MAX_RECENT)
            .mapTo(mutableSetOf()) { (time, uri) -> "$time|${Uri.encode(uri.toString())}" }
        prefs(context).edit().putStringSet(KEY_RECENT, encoded).apply()
    }

    private fun remapSavedUri(context: Context, oldUri: Uri, newUri: Uri) {
        val old = oldUri.toString()
        val new = newUri.toString()
        val favorites = favoriteUris(context).mapTo(mutableSetOf()) { if (it == old) new else it }
        prefs(context).edit().putStringSet(KEY_FAVORITES, favorites).apply()
        writeRecent(context, parseRecent(context).map { (time, uri) -> time to if (uri.toString() == old) newUri else uri })
    }

    private fun removeSavedUri(context: Context, uri: Uri) {
        val raw = uri.toString()
        prefs(context).edit().putStringSet(KEY_FAVORITES, favoriteUris(context) - raw).apply()
        writeRecent(context, parseRecent(context).filterNot { it.second.toString() == raw })
    }


    fun loadImagePreview(context: Context, entry: Entry, maxDimension: Int = 1280): Bitmap? {
        require(entry.isFile) { "اختر ملفًا" }
        require(FileManagerRules.previewKind(entry.name, entry.mimeType) == FileManagerRules.PreviewKind.IMAGE) {
            "هذا النوع لا يدعم معاينة الصورة"
        }
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(entry.uri).use { input ->
            requireNotNull(input) { "تعذر قراءة ${entry.name}" }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = FileManagerRules.previewSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return resolver.openInputStream(entry.uri).use { input ->
            requireNotNull(input) { "تعذر قراءة ${entry.name}" }
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    fun readTextPreview(context: Context, entry: Entry, maxBytes: Int = 160 * 1024): String {
        require(entry.isFile) { "اختر ملفًا" }
        require(FileManagerRules.previewKind(entry.name, entry.mimeType) == FileManagerRules.PreviewKind.TEXT) {
            "هذا النوع لا يدعم المعاينة النصية"
        }
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
        context.contentResolver.openInputStream(entry.uri).use { input ->
            requireNotNull(input) { "تعذر قراءة ${entry.name}" }
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (total < maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                if (count <= 0) break
                output.write(buffer, 0, count)
                total += count
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    fun open(context: Context, entry: Entry) {
        require(entry.isFile) { "اختر ملفًا" }
        recordRecent(context, entry)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(entry.uri, entry.mimeType.ifBlank { mimeTypeForName(entry.name) })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, entry.name))
    }

    fun share(context: Context, entries: List<Entry>) {
        val files = entries.filter { it.isFile }.distinctBy { it.uri.toString() }
        require(files.isNotEmpty()) { "لا توجد ملفات صالحة للمشاركة" }
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = files.first().mimeType.ifBlank { mimeTypeForName(files.first().name) }
                putExtra(Intent.EXTRA_STREAM, files.first().uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = if (files.map { it.mimeType }.distinct().size == 1) files.first().mimeType else "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { it.uri }))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(Intent.createChooser(intent, "مشاركة الملفات"))
    }

    fun mimeTypeForName(name: String): String {
        val extension = FileManagerRules.extensionOf(name)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "txt", "md", "log" -> "text/plain"
                "csv" -> "text/csv"
                else -> "application/octet-stream"
            }
    }

    private fun android.database.Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getString(index)
    }

    private fun android.database.Cursor.longOr(column: String, fallback: Long): Long {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return fallback
        return getLong(index)
    }

    private fun android.database.Cursor.intOr(column: String, fallback: Int): Int {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return fallback
        return getInt(index)
    }
}
