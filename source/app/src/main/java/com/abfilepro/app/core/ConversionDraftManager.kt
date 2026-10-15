package com.abfilepro.app.core

import android.content.Context
import java.io.File

/**
 * Keeps newly generated conversion results outside the permanent workspace until the
 * user reviews and explicitly accepts them. The source/original document is never moved.
 */
object ConversionDraftManager {
    private const val DRAFT_FOLDER = "conversion_drafts"
    private const val MAX_DRAFT_AGE_MS = 24L * 60L * 60L * 1000L

    fun stage(context: Context, generated: File): File {
        require(generated.exists() && generated.isFile) { "ملف التحويل المؤقت غير موجود" }
        val dir = File(context.cacheDir, DRAFT_FOLDER).apply { mkdirs() }
        cleanup(context)
        val target = uniqueTarget(dir, generated.name)
        if (!generated.renameTo(target)) {
            try {
                generated.inputStream().buffered().use { input ->
                    target.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                if (!generated.delete()) {
                    target.delete()
                    error("تعذر نقل نتيجة التحويل إلى المعاينة بأمان")
                }
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }
        return target
    }

    fun commit(context: Context, draft: File): File {
        require(isDraft(context, draft) && draft.exists() && draft.isFile) { "نتيجة المعاينة غير صالحة" }
        val target = targetFor(context, draft)
        if (!draft.renameTo(target)) {
            try {
                draft.inputStream().buffered().use { input ->
                    target.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                if (!draft.delete()) {
                    target.delete()
                    error("تعذر اعتماد الحفظ بأمان")
                }
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }
        return target
    }

    fun commitBatch(context: Context, drafts: List<File>): List<File> {
        require(drafts.isNotEmpty()) { "لا توجد نتائج لاعتمادها" }
        drafts.forEach { draft ->
            require(isDraft(context, draft) && draft.exists() && draft.isFile) { "إحدى نتائج المعاينة غير صالحة" }
        }
        val targets = drafts.map { draft -> targetFor(context, draft) }
        val tempFiles = targets.mapIndexed { index, target ->
            File(target.parentFile, ".ab_commit_${System.currentTimeMillis()}_${index}_${target.name}")
        }
        val committed = mutableListOf<File>()
        try {
            drafts.zip(tempFiles).forEach { (draft, temp) ->
                draft.inputStream().buffered().use { input ->
                    temp.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
            tempFiles.zip(targets).forEach { (temp, target) ->
                check(temp.renameTo(target)) { "تعذر اعتماد مجموعة الملفات بأمان" }
                committed += target
            }
            drafts.forEach { it.delete() }
            return committed
        } catch (error: Throwable) {
            tempFiles.forEach { it.delete() }
            committed.forEach { it.delete() }
            throw error
        }
    }

    fun discard(context: Context, draft: File) {
        if (isDraft(context, draft) && draft.exists()) draft.delete()
    }

    fun discardAll(context: Context, drafts: List<File>) {
        drafts.forEach { discard(context, it) }
    }

    fun clearAbandoned(context: Context) {
        val dir = File(context.cacheDir, DRAFT_FOLDER)
        dir.listFiles().orEmpty().forEach { file -> if (file.isFile) file.delete() }
    }

    fun cleanup(context: Context) {
        val dir = File(context.cacheDir, DRAFT_FOLDER)
        val cutoff = System.currentTimeMillis() - MAX_DRAFT_AGE_MS
        dir.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun isDraft(context: Context, file: File): Boolean {
        val dir = File(context.cacheDir, DRAFT_FOLDER).canonicalFile
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return target.parentFile == dir
    }

    private fun targetFor(context: Context, draft: File): File {
        val folder = when (draft.extension.lowercase()) {
            "pdf" -> "pdf"
            "docx", "xlsx" -> "office"
            "png", "jpg", "jpeg", "webp" -> "images"
            else -> "converted"
        }
        return uniqueTarget(FileUtils.outputDir(context, folder), draft.name)
    }

    private fun uniqueTarget(directory: File, rawName: String): File {
        val safe = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "converted_file" }
        var candidate = File(directory, safe)
        if (!candidate.exists()) return candidate
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var counter = 2
        while (candidate.exists()) {
            candidate = File(directory, "${base}_$counter$ext")
            counter++
        }
        return candidate
    }
}
