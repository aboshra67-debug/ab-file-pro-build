package com.abfilepro.app.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Direct device-storage engine used only after Android grants All Files Access. */
object DeviceStorageManager {
    private const val MAX_ZIP_ENTRIES = 20_000
    private const val MAX_ZIP_EXPANDED_BYTES = 8L * 1024L * 1024L * 1024L

    data class Entry(val file: File) {
        val path: String get() = file.absolutePath
        val name: String get() = file.name.ifBlank { file.absolutePath }
        val isDirectory: Boolean get() = file.isDirectory
        val isFile: Boolean get() = file.isFile
        val size: Long get() = if (isFile) file.length() else 0L
        val lastModified: Long get() = file.lastModified()
        val mimeType: String get() = mimeTypeForName(name)
    }

    fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun permissionIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun rootDir(): File = Environment.getExternalStorageDirectory()

    private fun canonicalRoot(): String = rootDir().canonicalFile.path

    fun isInsideRoot(file: File): Boolean {
        val root = canonicalRoot()
        val candidate = runCatching { file.canonicalFile.path }.getOrElse { return false }
        return candidate == root || candidate.startsWith(root + File.separator)
    }

    fun requireInsideRoot(file: File): File {
        require(isInsideRoot(file)) { "المسار خارج ذاكرة الهاتف" }
        return file
    }

    fun listChildren(directory: File): List<Entry> {
        requireInsideRoot(directory)
        require(directory.isDirectory) { "المجلد غير صالح" }
        return directory.listFiles().orEmpty()
            .filter { isInsideRoot(it) && it.exists() && !it.name.startsWith(".abfilepro_") }
            .map(::Entry)
            .sortedWith(compareBy<Entry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun createFolder(parent: File, rawName: String): Entry {
        requireInsideRoot(parent)
        val name = FileManagerRules.safeName(rawName).ifBlank { "New folder" }
        val target = uniqueTarget(parent, name)
        require(target.mkdirs()) { "تعذر إنشاء المجلد" }
        return Entry(target)
    }

    fun createTextFile(parent: File, rawName: String, content: String = ""): Entry {
        requireInsideRoot(parent)
        require(parent.isDirectory) { "مجلد الحفظ غير صالح" }
        val name = ExplorerPremiumRules.textFileName(rawName)
        val target = uniqueTarget(parent, name)
        FileOutputStream(target).bufferedWriter(Charsets.UTF_8).use { it.write(content) }
        return Entry(target)
    }

    fun rename(entry: Entry, rawName: String): Entry {
        requireInsideRoot(entry.file)
        val parent = requireNotNull(entry.file.parentFile) { "تعذر تحديد المجلد الأب" }
        val safe = FileManagerRules.safeName(rawName).ifBlank { entry.name }
        val target = File(parent, safe)
        require(!target.exists()) { "يوجد عنصر بنفس الاسم" }
        require(isInsideRoot(target)) { "الاسم غير صالح" }
        require(entry.file.renameTo(target)) { "تعذر إعادة التسمية" }
        return Entry(target)
    }

    fun delete(entry: Entry): Boolean {
        requireInsideRoot(entry.file)
        if (entry.file.canonicalFile == rootDir().canonicalFile) return false
        return deleteRecursivelySafe(entry.file)
    }

    private fun deleteRecursivelySafe(file: File): Boolean {
        if (!isInsideRoot(file)) return false
        if (file.isDirectory) file.listFiles().orEmpty().forEach { if (!deleteRecursivelySafe(it)) return false }
        return !file.exists() || file.delete()
    }

    suspend fun copy(entry: Entry, destination: File): Entry {
        requireInsideRoot(entry.file); requireInsideRoot(destination)
        require(destination.isDirectory) { "مجلد الوجهة غير صالح" }
        requireOutsideSource(entry, destination)
        currentCoroutineContext().ensureActive()
        // Publish only a complete copy. Cleanup belongs solely to this attempt.
        val staging = File(destination, ".abfilepro_copy_${UUID.randomUUID()}")
        require(staging.mkdir()) { "تعذر إنشاء مساحة النسخ المؤقتة" }
        try {
            val staged = File(staging, entry.name)
            copyRecursively(entry.file, staged)
            currentCoroutineContext().ensureActive()
            val target = uniqueTarget(destination, entry.name)
            // No REPLACE_EXISTING: an existing destination is never overwritten.
            Files.move(staged.toPath(), target.toPath())
            return Entry(target)
        } finally {
            deleteRecursivelySafe(staging)
        }
    }

    suspend fun move(entry: Entry, destination: File): Entry {
        requireInsideRoot(entry.file); requireInsideRoot(destination)
        require(destination.isDirectory) { "مجلد الوجهة غير صالح" }
        requireOutsideSource(entry, destination)
        currentCoroutineContext().ensureActive()
        val target = uniqueTarget(destination, entry.name)
        if (entry.file.renameTo(target)) return Entry(target)
        // Capture before copying: later additions or edits do not belong to the
        // completed destination and must never be removed as source cleanup.
        val copiedSource = OwnedOutputSnapshot.capture(entry.file).token
        val copied = copy(entry, destination)
        currentCoroutineContext().ensureActive()
        if (!OwnedOutputSnapshot.deleteIfUnchanged(entry.file, copiedSource).complete) {
            // Source cleanup can fail after removing some children. The complete
            // destination is then the only copy of those children: keep it.
            error("تم الاحتفاظ بالنسخة المكتملة في ${copied.path}؛ تعذر حذف بقية المصدر")
        }
        return copied
    }

    private fun requireOutsideSource(entry: Entry, destination: File) {
        val source = entry.file.canonicalFile
        val dest = destination.canonicalFile
        require(!(entry.isDirectory && (dest == source || dest.path.startsWith(source.path + File.separator)))) {
            "لا يمكن نسخ أو نقل مجلد إلى داخله"
        }
    }

    private suspend fun copyRecursively(source: File, target: File) {
        currentCoroutineContext().ensureActive()
        requireInsideRoot(source); requireInsideRoot(target)
        if (source.isDirectory) {
            require(target.mkdirs() || target.isDirectory) { "تعذر إنشاء مجلد الوجهة" }
            val children = requireNotNull(source.listFiles()) { "تعذر قراءة مجلد المصدر" }
            children.forEach { child -> copyRecursively(child, File(target, child.name)) }
        } else {
            target.parentFile?.mkdirs()
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            target.setLastModified(source.lastModified())
        }
    }

    suspend fun createZip(selected: List<Entry>, parent: File, rawName: String): Entry {
        require(selected.isNotEmpty()) { "حدد ملفًا أو مجلدًا واحدًا على الأقل" }
        requireInsideRoot(parent)
        val base = FileManagerRules.safeName(rawName).ifBlank { "Archive.zip" }.let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        val outputFile = uniqueTarget(parent, base)
        try {
            ZipOutputStream(FileOutputStream(outputFile).buffered()).use { zip ->
                val used = HashSet<String>()
                for (entry in selected) {
                    currentCoroutineContext().ensureActive()
                    addToZip(zip, entry.file, "", used)
                }
            }
            return Entry(outputFile)
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        }
    }

    private suspend fun addToZip(zip: ZipOutputStream, file: File, prefix: String, used: MutableSet<String>) {
        currentCoroutineContext().ensureActive(); requireInsideRoot(file)
        val raw = if (prefix.isBlank()) file.name else "$prefix/${file.name}"
        val normalized = raw.replace('\\', '/')
        require(FileManagerRules.isSafeZipPath(normalized)) { "مسار ZIP غير آمن" }
        val unique = uniqueZipPath(normalized, used, file.isDirectory)
        used += unique.trimEnd('/')
        if (file.isDirectory) {
            val dirPath = unique.trimEnd('/') + "/"
            zip.putNextEntry(ZipEntry(dirPath)); zip.closeEntry()
            file.listFiles().orEmpty().sortedBy { it.name.lowercase() }.forEach { child -> addToZip(zip, child, unique.trimEnd('/'), used) }
        } else {
            zip.putNextEntry(ZipEntry(unique))
            FileInputStream(file).use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    zip.write(buffer, 0, read)
                }
            }
            zip.closeEntry()
        }
    }

    suspend fun extractZip(zipEntry: Entry, destination: File, intoNewFolder: Boolean): Pair<File, Int> {
        require(zipEntry.isFile && zipEntry.name.endsWith(".zip", true)) { "اختر ملف ZIP صالح" }
        requireInsideRoot(zipEntry.file); requireInsideRoot(destination)
        val baseDestination = if (intoNewFolder) {
            val baseName = zipEntry.name.substringBeforeLast('.').ifBlank { "Extracted" }
            val folder = uniqueTarget(destination, FileManagerRules.safeName(baseName).ifBlank { "Extracted" })
            require(folder.mkdirs()) { "تعذر إنشاء مجلد فك الضغط" }
            folder
        } else destination
        val canonicalBase = baseDestination.canonicalFile
        val created = ArrayList<File>()
        var count = 0
        var bytes = 0L
        try {
            ZipInputStream(FileInputStream(zipEntry.file).buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val item = zip.nextEntry ?: break
                    count++
                    require(count <= MAX_ZIP_ENTRIES) { "ملف ZIP يحتوي عدد عناصر غير آمن" }
                    val normalized = item.name.replace('\\', '/').trimStart('/')
                    require(FileManagerRules.isSafeZipPath(normalized)) { "تم إيقاف فك ZIP بسبب مسار غير آمن" }
                    val target = File(baseDestination, normalized).canonicalFile
                    require(target.path == canonicalBase.path || target.path.startsWith(canonicalBase.path + File.separator)) { "مسار ZIP غير آمن" }
                    require(isInsideRoot(target)) { "مسار ZIP خارج ذاكرة الهاتف" }
                    if (item.isDirectory || item.name.endsWith('/')) {
                        if (!target.exists()) { require(target.mkdirs()) { "تعذر إنشاء مجلد أثناء فك ZIP" }; created += target }
                    } else {
                        target.parentFile?.mkdirs()
                        val actualTarget = if (target.exists()) uniqueTarget(target.parentFile!!, target.name) else target
                        FileOutputStream(actualTarget).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                bytes += read
                                require(bytes <= MAX_ZIP_EXPANDED_BYTES) { "تم إيقاف ZIP لحماية مساحة التخزين" }
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                        }
                        created += actualTarget
                    }
                    zip.closeEntry()
                }
            }
            return baseDestination to created.count { it.isFile }
        } catch (error: Throwable) {
            created.asReversed().forEach { runCatching { if (it.isDirectory) it.deleteRecursively() else it.delete() } }
            if (intoNewFolder) runCatching { baseDestination.deleteRecursively() }
            throw error
        }
    }

    fun loadImagePreview(entry: Entry, maxDimension: Int = 1280): Bitmap? {
        require(entry.isFile)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(entry.file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = FileManagerRules.previewSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(entry.file.absolutePath, options)
    }

    fun open(context: Context, entry: Entry) {
        val uri = fileUri(context, entry.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, entry.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, entry.name))
    }

    fun share(context: Context, entries: List<Entry>) {
        val files = entries.filter { it.isFile }
        require(files.isNotEmpty()) { "لا توجد ملفات للمشاركة" }
        if (files.size == 1) {
            val entry = files.first(); val uri = fileUri(context, entry.file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = entry.mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, entry.name))
        } else {
            val uris = ArrayList(files.map { fileUri(context, it.file) })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share files"))
        }
    }

    private fun fileUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private fun uniqueTarget(parent: File, rawName: String): File {
        requireInsideRoot(parent)
        val names = parent.listFiles().orEmpty().map { it.name }
        return File(parent, FileManagerRules.uniqueName(rawName, names)).also { require(isInsideRoot(it)) }
    }

    private fun uniqueZipPath(path: String, used: Set<String>, directory: Boolean): String {
        val clean = path.trimEnd('/')
        if (clean !in used) return if (directory) "$clean/" else clean
        val parent = clean.substringBeforeLast('/', "")
        val name = clean.substringAfterLast('/')
        val extension = if (directory) "" else name.substringAfterLast('.', "").takeIf { it.isNotBlank() && it != name }.orEmpty()
        val stem = if (extension.isBlank()) name else name.removeSuffix(".$extension")
        var index = 2
        while (true) {
            val candidateName = if (extension.isBlank()) "$stem ($index)" else "$stem ($index).$extension"
            val candidate = if (parent.isBlank()) candidateName else "$parent/$candidateName"
            if (candidate !in used) return if (directory) "$candidate/" else candidate
            index++
        }
    }

    private fun mimeTypeForName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "zip" -> "application/zip"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}
