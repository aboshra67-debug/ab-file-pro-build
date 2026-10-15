package com.abfilepro.app.core

import android.content.Context
import android.net.Uri
import com.abfilepro.app.ui.i18n.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object CommandEngine {
    data class Plan(
        val command: String,
        val steps: List<String>,
        val requestedName: String?,
        val requestedFolder: String?,
        val wantsWhatsApp: Boolean,
        val valid: Boolean,
        val warning: String? = null
    )

    data class Result(
        val output: File,
        val steps: List<String>,
        val sharedToWhatsApp: Boolean,
        val historyId: String
    )

    fun examples(context: Context, languageCode: String): List<String> = listOf(
        context.uiText(languageCode, "command_example_1"),
        context.uiText(languageCode, "command_example_2"),
        context.uiText(languageCode, "command_example_3")
    )


    fun plan(context: Context, languageCode: String, command: String, selectionCount: Int): Plan {
        val normalized = normalize(command)
        if (selectionCount <= 0) return Plan(command, emptyList(), null, null, false, false, context.uiText(languageCode, "command_warning_select"))
        if (normalized.isBlank()) return Plan(command, emptyList(), null, null, false, false, context.uiText(languageCode, "command_warning_enter"))

        val steps = mutableListOf<String>()
        val stitch = containsAny(normalized,
            "stitch", "screenshot", "screenshots", "assemble captures", "assembler captures", "captures d'ecran",
            "سكرين", "سكرين شوت", "لم الصور", "لم السكرين", "الصق الصور")
        val merge = containsAny(normalized,
            "merge pdf", "merge these pdf", "merge files", "fusionner pdf", "fusionne les pdf", "fusionner fichiers",
            "ادمج pdf", "دمج pdf", "ادمج ملفات", "دمج ملفات")
        val toPdf = containsAny(normalized,
            "to pdf", "convert images to pdf", "images into pdf", "convertir en pdf", "images en pdf", "vers pdf",
            "pdf", "بي دي اف", "حول الصور", "حوّل الصور")
        val compress = containsAny(normalized,
            "compress", "reduce size", "smaller file", "compresser", "reduire la taille", "réduire la taille",
            "اضغط", "ضغط", "صغر الحجم", "قلل الحجم")
        val ocr = containsAny(normalized,
            "ocr", "extract text", "read text", "searchable pdf", "extraire le texte", "lire le texte", "pdf recherchable",
            "استخرج النص", "استخراج النص", "اقرأ النص", "اقرا النص", "نص قابل للبحث")
        val whatsapp = containsAny(normalized, "واتساب", "whatsapp", "واتس اب", "واتس")
        val name = extractName(command)
        val folder = extractFolder(command)

        when {
            stitch -> steps += context.uiText(languageCode, "command_step_stitch")
            merge -> steps += context.uiText(languageCode, "command_step_merge")
            toPdf && selectionCount >= 1 -> steps += context.uiText(languageCode, "command_step_to_pdf")
            ocr -> steps += context.uiText(languageCode, "command_step_ocr")
        }
        if (compress) steps += context.uiText(languageCode, "command_step_compress")
        if (!name.isNullOrBlank()) steps += context.uiText(languageCode, "command_step_rename", name)
        if (!folder.isNullOrBlank()) steps += context.uiText(languageCode, "command_step_folder", folder)
        if (whatsapp) steps += context.uiText(languageCode, "command_step_whatsapp")

        val hasCoreAction = stitch || merge || toPdf || ocr || compress
        return Plan(
            command = command,
            steps = steps.ifEmpty { listOf(context.uiText(languageCode, "command_step_safe_import")) },
            requestedName = name,
            requestedFolder = folder,
            wantsWhatsApp = whatsapp,
            valid = hasCoreAction || whatsapp || !name.isNullOrBlank() || !folder.isNullOrBlank(),
            warning = if (!hasCoreAction && !whatsapp && name.isNullOrBlank() && folder.isNullOrBlank()) context.uiText(languageCode, "command_warning_unknown") else null
        )
    }

    suspend fun execute(
        context: Context,
        languageCode: String,
        command: String,
        uris: List<Uri>,
        onProgress: suspend (String) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        require(uris.isNotEmpty()) { context.uiText(languageCode, "command_warning_select") }
        val plan = plan(context, languageCode, command, uris.size)
        require(plan.valid) { plan.warning ?: context.uiText(languageCode, "command_warning_unclear") }
        val normalized = normalize(command)
        val types = uris.map { context.contentResolver.getType(it).orEmpty().lowercase() }
        val allImages = types.all { it.startsWith("image/") || it.isBlank() }
        val allPdf = types.all { it == "application/pdf" || it.endsWith("/pdf") }

        val stitch = containsAny(normalized,
            "stitch", "screenshot", "screenshots", "assemble captures", "assembler captures", "captures d'ecran",
            "سكرين", "سكرين شوت", "لم الصور", "لم السكرين", "الصق الصور")
        val merge = containsAny(normalized,
            "merge pdf", "merge these pdf", "merge files", "fusionner pdf", "fusionne les pdf", "fusionner fichiers",
            "ادمج pdf", "دمج pdf", "ادمج ملفات", "دمج ملفات")
        val toPdf = containsAny(normalized,
            "to pdf", "convert images to pdf", "images into pdf", "convertir en pdf", "images en pdf", "vers pdf",
            "pdf", "بي دي اف", "حول الصور", "حوّل الصور")
        val compress = containsAny(normalized,
            "compress", "reduce size", "smaller file", "compresser", "reduire la taille", "réduire la taille",
            "اضغط", "ضغط", "صغر الحجم", "قلل الحجم")
        val ocr = containsAny(normalized,
            "ocr", "extract text", "read text", "searchable pdf", "extraire le texte", "lire le texte", "pdf recherchable",
            "استخرج النص", "استخراج النص", "اقرأ النص", "اقرا النص", "نص قابل للبحث")

        var output: File
        var generatedOutput: File? = null
        try {
        when {
            stitch -> {
                require(allImages && uris.size >= 2) { context.uiText(languageCode, "command_error_stitch_min") }
                onProgress(context.uiText(languageCode, "command_progress_stitch"))
                val stitched = StitchProcessor.stitch(context, uris, smartOrder = true, languageCode = languageCode) { _ ->
                    onProgress(context.uiText(languageCode, "command_progress_stitch"))
                }
                output = if (toPdf) stitched.pdfFile else stitched.imageFile
                val unused = if (toPdf) stitched.imageFile else stitched.pdfFile
                if (unused.exists()) unused.delete()
                generatedOutput = output
            }
            merge -> {
                require(allPdf && uris.size >= 2) { context.uiText(languageCode, "command_error_merge_min") }
                onProgress(context.uiText(languageCode, "command_progress_merge"))
                output = PdfTools.mergePdfs(context, uris)
                generatedOutput = output
            }
            allImages && toPdf -> {
                onProgress(context.uiText(languageCode, "command_progress_to_pdf"))
                output = PdfTools.imagesToPdf(context, uris)
                generatedOutput = output
            }
            ocr && uris.size == 1 && allImages -> {
                onProgress(context.uiText(languageCode, "command_progress_ocr"))
                val language = bestOcrLanguage(context)
                val text = OcrProcessor.recognize(context, uris.first(), language)
                val out = File(FileUtils.outputDir(context, "AB Command"), "OCR_${System.currentTimeMillis()}.txt")
                FileOutputStream(out).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                output = out
                generatedOutput = output
            }
            ocr && uris.size == 1 && allPdf -> {
                onProgress(context.uiText(languageCode, "command_progress_searchable"))
                output = OcrProcessor.createSearchablePdf(
                    context,
                    uris.first(),
                    bestOcrLanguage(context)
                ) { page, total -> onProgress(context.uiText(languageCode, "command_progress_ocr_page", page, total)) }.pdf
                generatedOutput = output
            }
            else -> {
                onProgress(context.uiText(languageCode, "command_progress_import"))
                output = FileUtils.importUriCancellable(context, uris.first(), FileUtils.outputDir(context, "AB Command"))
                generatedOutput = output
            }
        }

        if (compress) {
            require(output.extension.equals("pdf", true)) { context.uiText(languageCode, "command_error_pdf_compress") }
            onProgress(context.uiText(languageCode, "command_progress_compress"))
            val previous = output
            val compressed = PdfTools.compressPdf(context, FileUtils.contentUri(context, previous))
            if (previous.exists() && previous.canonicalFile != compressed.canonicalFile && FileUtils.isInsideRoot(context, previous)) previous.delete()
            output = compressed
            generatedOutput = output
        }

        plan.requestedName?.takeIf { it.isNotBlank() }?.let { requested ->
            onProgress(context.uiText(languageCode, "command_progress_rename"))
            output = runCatching { FileUtils.renameFile(output, sanitize(requested)) }.getOrElse { output }
            generatedOutput = output
        }

        val destination = FileUtils.outputDir(
            context,
            "AB Command/${sanitize(plan.requestedFolder ?: "Outputs").ifBlank { "Outputs" }}"
        )
        if (output.parentFile?.canonicalFile != destination.canonicalFile) {
            onProgress(context.uiText(languageCode, "command_progress_organize"))
            output = FileUtils.moveFile(context, output, destination)
            generatedOutput = output
        }

        val history = OperationHistoryStore.record(
            context,
            tool = "AB Command",
            title = command.trim().take(100).ifBlank { output.name },
            outputs = listOf(output)
        )

        if (plan.wantsWhatsApp) {
            onProgress(context.uiText(languageCode, "command_progress_whatsapp"))
            withContext(Dispatchers.Main) { FileUtils.shareFileToWhatsApp(context, output) }
        }

        Result(output = output, steps = plan.steps, sharedToWhatsApp = plan.wantsWhatsApp, historyId = history.id)
        } catch (error: Throwable) {
            if (RuntimeSafety.isCancellation(error)) {
                generatedOutput?.let { file ->
                    runCatching {
                        if (file.exists() && FileUtils.isInsideRoot(context, file) && file.canonicalFile != FileUtils.rootDir(context).canonicalFile) {
                            if (file.isDirectory) file.deleteRecursively() else file.delete()
                        }
                    }
                }
            }
            throw error
        }
    }

    private fun bestOcrLanguage(context: Context): OcrProcessor.Language = when {
        OcrProcessor.isModelReady(context, OcrProcessor.Language.ALL) -> OcrProcessor.Language.ALL
        OcrProcessor.isModelReady(context, OcrProcessor.Language.ARABIC_ENGLISH) -> OcrProcessor.Language.ARABIC_ENGLISH
        OcrProcessor.isModelReady(context, OcrProcessor.Language.ENGLISH_FRENCH) -> OcrProcessor.Language.ENGLISH_FRENCH
        else -> OcrProcessor.Language.ALL
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('é', 'e').replace('è', 'e').replace('ê', 'e').replace('à', 'a').replace('ç', 'c')
        .replace("ّ", "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun containsAny(text: String, vararg values: String): Boolean = values.any { text.contains(normalize(it)) }

    private fun extractName(command: String): String? {
        val patterns = listOf(
            Regex("(?:سم[ّ]?يه|سميه|سمه|اسم(?:ه)?)[\\s:=-]+([^،,.;\\n]+)", RegexOption.IGNORE_CASE),
            Regex("(?:name it|call it|rename(?: it)? to)[\\s:=-]+([^,.;\\n]+)", RegexOption.IGNORE_CASE),
            Regex("(?:nomme(?:-le)?|nommer|renomme(?:-le)?)[\\s:=-]+([^,.;\\n]+)", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { regex -> regex.find(command)?.groupValues?.getOrNull(1)?.trim()?.take(60) }
    }

    private fun extractFolder(command: String): String? {
        val patterns = listOf(
            Regex("(?:مجلد|داخل مجلد|في مجلد)[\\s:=-]+([^،,.;\\n]+)", RegexOption.IGNORE_CASE),
            Regex("(?:folder|save (?:it )?in|save (?:it )?to)[\\s:=-]+([^,.;\\n]+)", RegexOption.IGNORE_CASE),
            Regex("(?:dossier|enregistre(?:r)? dans|sauvegarde(?:r)? dans)[\\s:=-]+([^,.;\\n]+)", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { regex -> regex.find(command)?.groupValues?.getOrNull(1)?.trim()?.take(50) }
    }

    private fun sanitize(value: String): String = value.trim()
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
}
