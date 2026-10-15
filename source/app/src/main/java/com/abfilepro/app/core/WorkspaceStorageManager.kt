package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import kotlin.math.min

/**
 * User-owned AB File Pro workspace stored directly under Internal Storage.
 * Example: /storage/emulated/0/Ahmed Files
 *
 * The folder is visible to the phone's file manager, while the AB File Pro UI
 * keeps navigation bounded to this chosen folder.
 */
object WorkspaceStorageManager {
    private const val PREFS = "ab_file_pro_external_workspace"
    private const val KEY_NAME = "workspace_name"
    private const val KEY_PATH = "workspace_path"
    private const val DEFAULT_NAME = "AB File Pro"
    private const val PROFILE_FILE = ".abfilepro_profile.properties"
    private const val PROFILE_MARKER = "workspace_marker"
    private const val PROFILE_DEFAULT_SAVE = "default_save_relative"
    private const val PROFILE_THEME = "explorer_theme"
    private const val PROFILE_STYLE = "explorer_style"
    private const val PROFILE_SHORTCUT_ICON = "shortcut_icon"

    fun savedName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null)
            ?.takeIf { it.isNotBlank() }

    private fun savedPath(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PATH, null)
            ?.takeIf { it.isNotBlank() }

    private fun rememberWorkspace(context: Context, folder: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, folder.name)
            .putString(KEY_PATH, folder.canonicalPath)
            .apply()
        ensureProfile(folder)
    }

    private fun profileFile(root: File): File = File(root, PROFILE_FILE)

    private fun ensureProfile(root: File) {
        runCatching {
            if (!root.exists()) root.mkdirs()
            val file = profileFile(root)
            val props = Properties()
            if (file.isFile) file.inputStream().use(props::load)
            props.setProperty(PROFILE_MARKER, "AB_FILE_PRO")
            file.outputStream().use { props.store(it, "AB File Pro persistent workspace") }
        }
    }

    private fun readProfile(root: File): Properties {
        val props = Properties()
        runCatching {
            val file = profileFile(root)
            if (file.isFile) file.inputStream().use(props::load)
        }
        return props
    }

    private fun writeProfile(root: File, key: String, value: String) {
        runCatching {
            val props = readProfile(root)
            props.setProperty(PROFILE_MARKER, "AB_FILE_PRO")
            props.setProperty(key, value)
            profileFile(root).outputStream().use { props.store(it, "AB File Pro persistent workspace") }
        }
    }

    private fun isMarkedWorkspace(folder: File): Boolean = runCatching {
        folder.isDirectory && readProfile(folder).getProperty(PROFILE_MARKER) == "AB_FILE_PRO"
    }.getOrDefault(false)

    private fun discoverWorkspace(): File? {
        val storageRoot = DeviceStorageManager.rootDir().canonicalFile
        val candidates = buildList {
            add(File(storageRoot, DEFAULT_NAME))
            File(storageRoot, "Documents").takeIf { it.isDirectory }?.listFiles().orEmpty().forEach(::add)
            storageRoot.listFiles().orEmpty().take(256).forEach(::add)
        }
        return candidates.asSequence()
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .distinctBy { it.path }
            .firstOrNull(::isMarkedWorkspace)
            ?: File(storageRoot, DEFAULT_NAME).takeIf { it.isDirectory }
    }

    fun workspaceDir(context: Context): File? {
        if (!DeviceStorageManager.hasAllFilesAccess()) return null
        val root = DeviceStorageManager.rootDir().canonicalFile
        val remembered = savedPath(context)?.let(::File)?.let { runCatching { it.canonicalFile }.getOrNull() }
            ?.takeIf { DeviceStorageManager.isInsideRoot(it) && (it.isDirectory || !it.exists()) }
        val legacy = savedName(context)?.let { File(root, it) }?.let { runCatching { it.canonicalFile }.getOrNull() }
            ?.takeIf { it.parentFile?.canonicalFile == root && (it.isDirectory || !it.exists()) }
        val folder = remembered ?: legacy ?: discoverWorkspace() ?: return null
        if (folder.exists() && !folder.isDirectory) return null
        if (!folder.exists() && !folder.mkdirs()) return null
        rememberWorkspace(context, folder)
        return folder
    }

    fun ensureDefaultWorkspace(context: Context): File? {
        if (!DeviceStorageManager.hasAllFilesAccess()) return null
        workspaceDir(context)?.let { return it }
        return createWorkspace(context, DEFAULT_NAME)
    }

    fun createWorkspace(context: Context, rawName: String): File {
        require(DeviceStorageManager.hasAllFilesAccess()) { "يجب السماح بالوصول للملفات أولاً" }
        val safe = FileManagerRules.safeName(rawName).trim().ifBlank { DEFAULT_NAME }
        require(safe != "." && safe != "..") { "اسم المجلد غير صالح" }
        val storageRoot = DeviceStorageManager.rootDir().canonicalFile
        val folder = File(storageRoot, safe).canonicalFile
        require(folder.parentFile?.canonicalFile == storageRoot) { "اسم المجلد غير صالح" }
        require(!folder.exists() || folder.isDirectory) { "يوجد ملف بنفس الاسم" }
        require(folder.exists() || folder.mkdirs()) { "تعذر إنشاء المجلد" }
        rememberWorkspace(context, folder)
        return folder
    }

    fun defaultSaveRelative(context: Context): String =
        workspaceDir(context)?.let { readProfile(it).getProperty(PROFILE_DEFAULT_SAVE).orEmpty() }.orEmpty()

    fun setDefaultSaveRelative(context: Context, relative: String) {
        workspaceDir(context)?.let { writeProfile(it, PROFILE_DEFAULT_SAVE, relative) }
    }

    fun explorerTheme(context: Context, fallback: String = "default"): String =
        workspaceDir(context)?.let { readProfile(it).getProperty(PROFILE_THEME) }?.takeIf { it.isNotBlank() } ?: fallback

    fun setExplorerTheme(context: Context, value: String) {
        workspaceDir(context)?.let { writeProfile(it, PROFILE_THEME, value) }
    }

    fun explorerStyle(context: Context, fallback: String = "windows"): String =
        workspaceDir(context)?.let { readProfile(it).getProperty(PROFILE_STYLE) }?.takeIf { it.isNotBlank() } ?: fallback

    fun setExplorerStyle(context: Context, value: String) {
        workspaceDir(context)?.let { writeProfile(it, PROFILE_STYLE, value) }
    }

    fun shortcutIconStyle(context: Context): String =
        workspaceDir(context)?.let { readProfile(it).getProperty(PROFILE_SHORTCUT_ICON) }?.takeIf { it.isNotBlank() } ?: "color"

    fun setShortcutIconStyle(context: Context, value: String) {
        workspaceDir(context)?.let { writeProfile(it, PROFILE_SHORTCUT_ICON, value) }
    }

    fun isInsideWorkspace(root: File, file: File): Boolean {
        val r = runCatching { root.canonicalFile.path }.getOrElse { return false }
        val f = runCatching { file.canonicalFile.path }.getOrElse { return false }
        return f == r || f.startsWith(r + File.separator)
    }

    suspend fun importUris(context: Context, uris: List<Uri>, destination: File): List<DeviceStorageManager.Entry> {
        require(uris.isNotEmpty()) { "لم يتم اختيار ملفات" }
        DeviceStorageManager.requireInsideRoot(destination)
        require(destination.isDirectory) { "مجلد الوجهة غير صالح" }
        val resolver = context.contentResolver
        val imported = ArrayList<DeviceStorageManager.Entry>()
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            val displayName = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
            }.getOrNull().orEmpty().ifBlank { "Imported file" }
            val safe = FileManagerRules.safeName(displayName).ifBlank { "Imported file" }
            val target = uniqueTarget(destination, safe)
            try {
                resolver.openInputStream(uri)?.use { input ->
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
                } ?: error("تعذر قراءة الملف: $displayName")
                imported += DeviceStorageManager.Entry(target)
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }
        return imported
    }

    fun isImage(entry: DeviceStorageManager.Entry): Boolean =
        entry.isFile && (entry.mimeType.startsWith("image/") ||
            FileManagerRules.extensionOf(entry.name) in setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif"))

    suspend fun imagesToPdf(
        selected: List<DeviceStorageManager.Entry>,
        parent: File,
        rawName: String
    ): DeviceStorageManager.Entry {
        val images = selected.filter(::isImage)
        require(images.isNotEmpty()) { "حدد صورة واحدة على الأقل" }
        require(images.size == selected.size) { "دمج PDF يقبل الصور فقط" }
        DeviceStorageManager.requireInsideRoot(parent)
        require(parent.isDirectory) { "مجلد الحفظ غير صالح" }

        val base = FileManagerRules.safeName(rawName).ifBlank { "Images.pdf" }
            .let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        val output = uniqueTarget(parent, base)
        val pdf = PdfDocument()
        val pageWidth = 1240
        val pageHeight = 1754
        val margin = 64f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        try {
            images.forEachIndexed { index, entry ->
                currentCoroutineContext().ensureActive()
                val bitmap = decodeForPdf(entry.file) ?: error("تعذر قراءة الصورة: ${entry.name}")
                try {
                    val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                    val page = pdf.startPage(info)
                    page.canvas.drawColor(Color.WHITE)
                    val maxW = pageWidth - margin * 2
                    val maxH = pageHeight - margin * 2
                    val scale = min(maxW / bitmap.width.toFloat(), maxH / bitmap.height.toFloat())
                    val w = bitmap.width * scale
                    val h = bitmap.height * scale
                    val left = (pageWidth - w) / 2f
                    val top = (pageHeight - h) / 2f
                    page.canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + w, top + h), paint)
                    pdf.finishPage(page)
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
            FileOutputStream(output).use { pdf.writeTo(it) }
            return DeviceStorageManager.Entry(output)
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            pdf.close()
        }
    }

    private fun decodeForPdf(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = FileManagerRules.previewSampleSize(bounds.outWidth, bounds.outHeight, 2400)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun uniqueTarget(parent: File, rawName: String): File {
        DeviceStorageManager.requireInsideRoot(parent)
        val names = parent.listFiles().orEmpty().map { it.name }
        return File(parent, FileManagerRules.uniqueName(rawName, names)).also {
            DeviceStorageManager.requireInsideRoot(it)
        }
    }
}
