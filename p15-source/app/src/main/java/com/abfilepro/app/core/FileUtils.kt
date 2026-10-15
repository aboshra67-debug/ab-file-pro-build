package com.abfilepro.app.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque

object FileUtils {
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp")
    private val textExtensions = setOf("txt", "md", "json", "xml", "csv", "log")
    private val officeExtensions = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv")

    private fun appPrivateRoot(context: Context): File =
        (context.getExternalFilesDir(null) ?: context.filesDir).apply { mkdirs() }

    /**
     * Alpha37: prefer the user-owned persistent workspace when Android has granted
     * device-file access. The fallback keeps the app functional before permission is granted.
     */
    fun rootDir(context: Context): File =
        runCatching { WorkspaceStorageManager.ensureDefaultWorkspace(context) }.getOrNull()
            ?: appPrivateRoot(context)

    private fun savedRelative(context: Context): String {
        val prefs = context.getSharedPreferences("ab_file_pro_files", 0)
        val local = prefs.getString("default_save_relative", "").orEmpty().trim().trim('/', '\\')
        if (local.isNotBlank()) return local
        val persistent = runCatching { WorkspaceStorageManager.defaultSaveRelative(context) }.getOrDefault("")
            .trim().trim('/', '\\')
        if (persistent.isNotBlank()) prefs.edit().putString("default_save_relative", persistent).apply()
        return persistent
    }

    fun outputDir(context: Context, folder: String): File {
        val root = rootDir(context)
        val preferredRelative = savedRelative(context)
        val preferredBase = if (preferredRelative.isNotBlank() && !folder.startsWith(".")) {
            val candidate = File(root, preferredRelative)
            if (runCatching { isInsideRoot(context, candidate) }.getOrDefault(false)) candidate.apply { mkdirs() } else root
        } else root
        return File(preferredBase, folder).apply { mkdirs() }
    }

    fun defaultSaveDirectory(context: Context): File {
        val root = rootDir(context)
        val relative = savedRelative(context)
        val candidate = if (relative.isBlank()) root else File(root, relative)
        return if (runCatching { isInsideRoot(context, candidate) }.getOrDefault(false)) candidate.apply { mkdirs() } else root
    }

    fun setDefaultSaveDirectory(context: Context, directory: File) {
        require(isInsideRoot(context, directory) && directory.isDirectory) { "مجلد حفظ غير صالح" }
        val root = rootDir(context).canonicalFile
        val target = directory.canonicalFile
        val relative = if (target == root) "" else target.path.removePrefix(root.path).trimStart(File.separatorChar)
        context.getSharedPreferences("ab_file_pro_files", 0).edit().putString("default_save_relative", relative).apply()
        runCatching { WorkspaceStorageManager.setDefaultSaveRelative(context, relative) }
    }

    fun verifySavedFile(context: Context, file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return false
        if (!runCatching { isInsideRoot(context, file) }.getOrDefault(false)) return false
        return runCatching {
            when (file.extension.lowercase()) {
                "pdf" -> file.inputStream().use { input ->
                    val header = ByteArray(5)
                    input.read(header) == 5 && header.toString(Charsets.US_ASCII) == "%PDF-"
                }
                "jpg", "jpeg" -> file.inputStream().use { input -> input.read() == 0xFF && input.read() == 0xD8 }
                "png" -> file.inputStream().use { input ->
                    val header = ByteArray(8)
                    input.read(header) == 8 && header.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                }
                else -> true
            }
        }.getOrDefault(false)
    }

    fun confirmSavedFile(context: Context, file: File): File {
        require(verifySavedFile(context, file)) { "scan_save_output_invalid" }
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType(file)), null)
        }
        return file
    }

    fun isInsideRoot(context: Context, file: File): Boolean {
        val root = rootDir(context).canonicalFile
        val target = file.canonicalFile
        return target == root || target.path.startsWith(root.path + File.separator)
    }

    fun isImageFile(file: File): Boolean = file.isFile && file.extension.lowercase() in imageExtensions
    fun isTextFile(file: File): Boolean = file.isFile && file.extension.lowercase() in textExtensions
    fun isOfficeFile(file: File): Boolean = file.isFile && file.extension.lowercase() in officeExtensions
    fun canPreview(file: File): Boolean = isImageFile(file) || isTextFile(file)

    fun listGeneratedFiles(context: Context, limit: Int = 500): List<File> {
        val safeLimit = limit.coerceAtLeast(1)
        return scanWorkspaceAll(context, maxVisited = 50_000) { entry ->
            entry.isFile && entry.extension.lowercase() in setOf("pdf", "png", "jpg", "jpeg", "txt", "docx", "xlsx")
        }.sortedByDescending { it.lastModified() }.take(safeLimit)
    }

    fun listDirectory(context: Context, directory: File): List<File> {
        require(isInsideRoot(context, directory) && directory.isDirectory) { "مجلد غير صالح" }
        return directory.listFiles()
            ?.filterNot { it.name.startsWith(".") }
            ?.toList()
            .orEmpty()
    }

    fun listFolders(context: Context, limit: Int = 2000): List<File> {
        val root = rootDir(context).canonicalFile
        if (limit <= 1) return listOf(root)
        val folders = scanWorkspace(context, limit - 1) { it.isDirectory }
        return (listOf(root) + folders).distinctBy { it.canonicalPath }.take(limit)
    }

    fun directVisibleChildCount(context: Context, directory: File): Int {
        require(isInsideRoot(context, directory) && directory.isDirectory) { "مجلد غير صالح" }
        return directory.listFiles()?.count { !it.name.startsWith(".") } ?: 0
    }

    /**
     * Safe iterative workspace scan. It never leaves the app workspace, skips hidden entries,
     * and stops at [limit] results to keep file-manager searches bounded.
     */
    fun searchWorkspace(context: Context, rawQuery: String, limit: Int = 400): List<File> {
        val query = rawQuery.trim()
        if (query.isBlank()) return emptyList()
        return scanWorkspace(context, limit) { entry -> entry.name.contains(query, ignoreCase = true) }
            .sortedWith(
                compareBy<File> { entry ->
                    when {
                        entry.name.equals(query, ignoreCase = true) -> 0
                        entry.name.startsWith(query, ignoreCase = true) -> 1
                        else -> 2
                    }
                }.thenByDescending { it.isDirectory }.thenBy { it.name.lowercase() }
            )
    }

    fun quickAccessFiles(context: Context, kind: String, favoritePaths: Set<String> = emptySet(), limit: Int = 300): List<File> {
        return when (kind) {
            "favorites" -> favoritePaths.asSequence()
                .map(::File)
                .filter { it.exists() && isInsideRoot(context, it) && !it.name.startsWith(".") }
                .sortedByDescending { it.lastModified() }
                .take(limit)
                .toList()
            "pdf" -> scanWorkspace(context, limit) { it.isFile && it.extension.equals("pdf", true) }.sortedByDescending { it.lastModified() }
            "images" -> scanWorkspace(context, limit) { isImageFile(it) }.sortedByDescending { it.lastModified() }
            "office" -> scanWorkspace(context, limit) { isOfficeFile(it) }.sortedByDescending { it.lastModified() }
            "text" -> scanWorkspace(context, limit) { isTextFile(it) }.sortedByDescending { it.lastModified() }
            else -> emptyList()
        }
    }

    fun allWorkspaceEntries(context: Context, maxVisited: Int = 12_000): List<File> {
        return scanWorkspaceAll(context, maxVisited) { true }
    }

    private fun scanWorkspace(context: Context, limit: Int, predicate: (File) -> Boolean): List<File> {
        val root = rootDir(context).canonicalFile
        val stack = ArrayDeque<File>()
        val results = ArrayList<File>()
        stack.add(root)

        while (stack.isNotEmpty() && results.size < limit) {
            val directory = stack.removeLast()
            val children = directory.listFiles().orEmpty()
            for (child in children) {
                if (child.name.startsWith(".")) continue
                val safeChild = runCatching { child.canonicalFile }.getOrNull() ?: continue
                if (safeChild != root && !safeChild.path.startsWith(root.path + File.separator)) continue
                if (predicate(safeChild)) results += safeChild
                if (safeChild.isDirectory) stack.add(safeChild)
                if (results.size >= limit) break
            }
        }
        return results
    }

    private fun scanWorkspaceAll(context: Context, maxVisited: Int, predicate: (File) -> Boolean): List<File> {
        val root = rootDir(context).canonicalFile
        val stack = ArrayDeque<File>()
        val results = ArrayList<File>()
        var visited = 0
        stack.add(root)

        while (stack.isNotEmpty() && visited < maxVisited) {
            val directory = stack.removeLast()
            val children = directory.listFiles().orEmpty()
            for (child in children) {
                if (visited >= maxVisited) break
                if (child.name.startsWith(".")) continue
                visited++
                val safeChild = runCatching { child.canonicalFile }.getOrNull() ?: continue
                if (safeChild != root && !safeChild.path.startsWith(root.path + File.separator)) continue
                if (predicate(safeChild)) results += safeChild
                if (safeChild.isDirectory) stack.add(safeChild)
            }
        }
        return results
    }

    fun readTextPreview(file: File, maxBytes: Int = 256 * 1024): String {
        require(file.exists() && file.isFile) { "الملف غير صالح" }
        require(isTextFile(file)) { "هذا النوع لا يدعم المعاينة النصية" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (total < maxBytes) {
                val wanted = minOf(buffer.size, maxBytes - total)
                val count = input.read(buffer, 0, wanted)
                if (count <= 0) break
                output.write(buffer, 0, count)
                total += count
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    fun loadImagePreview(file: File, maxDimension: Int = 1600): Bitmap? {
        require(file.exists() && file.isFile) { "الملف غير صالح" }
        require(isImageFile(file)) { "هذا النوع لا يدعم معاينة الصورة" }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    fun createFolder(context: Context, parent: File, rawName: String): File {
        require(isInsideRoot(context, parent) && parent.isDirectory) { "مجلد غير صالح" }
        val name = safeName(rawName)
        require(name.isNotBlank()) { "اكتب اسمًا صحيحًا" }
        val target = File(parent, name)
        require(!target.exists()) { "يوجد عنصر بنفس الاسم" }
        require(target.mkdir()) { "تعذر إنشاء المجلد" }
        return target
    }

    fun copyUriToOutput(context: Context, uri: Uri, folder: String, fileName: String): File {
        val out = File(outputDir(context, folder), fileName)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "تعذر قراءة الملف" }
                FileOutputStream(out).use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
            }
            return out
        } catch (error: Throwable) {
            out.delete()
            throw error
        }
    }

    fun importUri(context: Context, uri: Uri, destination: File): File {
        require(isInsideRoot(context, destination) && destination.isDirectory) { "مجلد غير صالح" }
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
        val requestedName = safeName(displayName ?: "imported_file")
        val out = uniqueTarget(destination, requestedName.ifBlank { "imported_file" })
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "تعذر قراءة الملف" }
                FileOutputStream(out).use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
            }
            return out
        } catch (error: Throwable) {
            out.delete()
            throw error
        }
    }


    suspend fun importUriCancellable(context: Context, uri: Uri, destination: File): File {
        require(isInsideRoot(context, destination) && destination.isDirectory) { "مجلد غير صالح" }
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
        val requestedName = safeName(displayName ?: "imported_file")
        val out = uniqueTarget(destination, requestedName.ifBlank { "imported_file" })
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "تعذر قراءة الملف" }
                FileOutputStream(out).use { output -> RuntimeSafety.copyCancellable(input, output) }
            }
            currentCoroutineContext().ensureActive()
            return out
        } catch (error: Throwable) {
            out.delete()
            throw error
        }
    }

    fun copyFile(context: Context, source: File, destination: File): File {
        require(isInsideRoot(context, source) && source.isFile) { "الملف غير صالح" }
        require(isInsideRoot(context, destination) && destination.isDirectory) { "المجلد الهدف غير صالح" }
        require(source.parentFile?.canonicalFile != destination.canonicalFile) { "الملف موجود بالفعل داخل هذا المجلد" }
        val target = uniqueTarget(destination, source.name)
        try {
            source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_SIZE) } }
            target.setLastModified(source.lastModified())
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun moveFile(context: Context, source: File, destination: File): File {
        require(isInsideRoot(context, source) && source.isFile) { "الملف غير صالح" }
        require(isInsideRoot(context, destination) && destination.isDirectory) { "المجلد الهدف غير صالح" }
        require(source.parentFile?.canonicalFile != destination.canonicalFile) { "الملف موجود بالفعل داخل هذا المجلد" }
        val target = uniqueTarget(destination, source.name)
        if (source.renameTo(target)) return target
        currentCoroutineContext().ensureActive()
        val sourceProof = OwnedOutputSnapshot.capture(source)
        var writer: OwnedFileWrite? = null
        var completeCopy = false
        try {
            source.inputStream().use { input ->
                val owned = OwnedFileWrite.create(target).also { writer = it }
                owned.stream.use { output ->
                    RuntimeSafety.copyCancellable(input, output)
                    output.flush()
                }
            }
            val copiedProof = requireNotNull(writer).seal()
            require(copiedProof.nodes.single().signature.substringAfterLast(':') ==
                sourceProof.nodes.single().signature.substringAfterLast(':')) { "تعذر التحقق من اكتمال النسخ" }
            completeCopy = true
            val modified = source.lastModified()
            require(OwnedOutputSnapshot.capture(target).token == copiedProof.token) { "تغيرت الوجهة بعد النسخ؛ تم الاحتفاظ بالأصل" }
            target.setLastModified(modified)
            // Reverify owned bytes after metadata handling, before unlinking the source.
            requireNotNull(writer).seal()
            currentCoroutineContext().ensureActive()
            require(OwnedOutputSnapshot.deleteIfUnchanged(source, sourceProof.token).complete) {
                "حُفظت النسخة المكتملة في ${target.absolutePath}؛ لم يُحذف الأصل لأنه تغير أو تعذر حذفه"
            }
            return target
        } catch (error: Throwable) {
            // A completed copy may be the only copy of some source bytes.
            // Never discard it merely because source cleanup failed/cancelled.
            if (!completeCopy) writer?.rollback()
            throw error
        }
    }

    fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "txt", "md", "log" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    fun contentUri(context: Context, file: File): Uri {
        require(file.exists() && file.isFile && isInsideRoot(context, file)) { "الملف غير صالح" }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun shareFile(context: Context, file: File) {
        val uri: Uri = contentUri(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "مشاركة الملف"))
    }

    /** Share any generated file directly to WhatsApp/WhatsApp Business with a safe share-sheet fallback. */
    fun shareFileToWhatsApp(context: Context, file: File) {
        require(file.exists() && file.isFile && isInsideRoot(context, file)) { "الملف غير صالح للمشاركة" }
        val uri = contentUri(context, file)
        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        for (packageName in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val launched = runCatching {
                context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent(baseIntent).apply { setPackage(packageName) })
            }.isSuccess
            if (launched) return
        }
        shareFile(context, file)
    }

    fun shareImageToWhatsApp(context: Context, file: File) {
        require(isImageFile(file)) { "الصورة غير صالحة للمشاركة" }
        shareFileToWhatsApp(context, file)
    }

    fun shareFiles(context: Context, files: List<File>) {
        val valid = files.filter { it.exists() && it.isFile }.distinctBy { it.canonicalPath }
        require(valid.isNotEmpty()) { "لا توجد ملفات صالحة للمشاركة" }
        if (valid.size == 1) {
            shareFile(context, valid.first())
            return
        }
        val uris = ArrayList(valid.map { contentUri(context, it) })
        val types = valid.map(::mimeType).distinct()
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (types.size == 1) types.first() else "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "مشاركة ${valid.size} ملفات"))
    }

    fun openFile(context: Context, file: File) {
        val uri: Uri = contentUri(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = mimeType(file)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "فتح أو مشاركة الملف"))
            }
    }

    fun renameFile(file: File, newBaseName: String): File {
        val safe = safeName(newBaseName)
        require(safe.isNotBlank()) { "اكتب اسمًا صحيحًا" }
        val name = if (safe.endsWith(".${file.extension}", ignoreCase = true)) safe else "$safe.${file.extension}"
        val target = File(file.parentFile, name)
        require(!target.exists()) { "يوجد ملف بنفس الاسم" }
        require(file.renameTo(target)) { "تعذر إعادة تسمية الملف" }
        return target
    }

    fun previewRenamedName(file: File, rawName: String): String {
        val safe = safeName(rawName)
        if (safe.isBlank() || safe == "." || safe == "..") return ""
        return if (file.isFile && file.extension.isNotBlank() && !safe.endsWith(".${file.extension}", true)) {
            "$safe.${file.extension}"
        } else safe
    }

    fun renameEntry(file: File, rawName: String): File {
        val targetName = previewRenamedName(file, rawName)
        require(targetName.isNotBlank()) { "اكتب اسمًا صحيحًا" }
        val target = File(file.parentFile, targetName)
        if (target.canonicalFile == file.canonicalFile) return file
        require(!target.exists()) { "يوجد عنصر بنفس الاسم" }
        require(file.renameTo(target)) { "تعذر إعادة التسمية" }
        return target
    }

    fun deleteFile(file: File): Boolean = file.exists() && file.delete()

    fun deleteEntrySafely(context: Context, entry: File): Boolean {
        require(isInsideRoot(context, entry)) { "العنصر غير صالح" }
        if (entry.isDirectory && !entry.listFiles().isNullOrEmpty()) {
            throw IllegalStateException("المجلد غير فارغ. انقل أو احذف محتوياته أولاً")
        }
        return entry.exists() && entry.delete()
    }

    private fun safeName(value: String): String = value.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun uniqueTarget(directory: File, requestedName: String): File {
        val safe = safeName(requestedName).ifBlank { "file" }
        var candidate = File(directory, safe)
        if (!candidate.exists()) return candidate
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var index = 2
        while (candidate.exists()) {
            candidate = File(directory, "$base ($index)$ext")
            index++
        }
        return candidate
    }
}
