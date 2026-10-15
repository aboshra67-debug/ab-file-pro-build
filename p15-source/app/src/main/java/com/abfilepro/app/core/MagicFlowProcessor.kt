package com.abfilepro.app.core

import android.content.Context
import android.net.Uri
import com.abfilepro.app.ui.i18n.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

object MagicFlowProcessor {
    data class Proposal(
        val kind: SmartScanClassifier.Kind,
        val typeLabel: String,
        val confidence: Int,
        val suggestedName: String,
        val suggestedFolder: String,
        val action: String,
        val textPreview: String,
        val language: OcrProcessor.Language,
        val pageCount: Int
    )

    data class Result(
        val pdf: File,
        val textFile: File,
        val historyId: String,
        val pageCount: Int,
        val folder: String,
        val correctedPages: Int
    )

    suspend fun analyze(
        context: Context,
        imageUris: List<Uri>,
        languageCode: String,
        onProgress: suspend (String) -> Unit = {}
    ): Proposal = withContext(Dispatchers.IO) {
        require(imageUris.isNotEmpty()) { context.uiText(languageCode, "magic_error_select") }
        require(imageUris.size <= 30) { context.uiText(languageCode, "magic_error_limit") }

        val samples = imageUris.take(3)
        val classifications = buildList {
            samples.forEachIndexed { index, uri ->
                currentCoroutineContext().ensureActive()
                onProgress(context.uiText(languageCode, "magic_progress_type", index + 1, samples.size))
                add(SmartScanClassifier.analyze(context, uri))
            }
        }
        val primary = classifications.maxByOrNull { it.confidence } ?: error(context.uiText(languageCode, "magic_error_analyze"))
        val preliminaryText = classifications.joinToString("\n") { it.extractedPreview }.trim()

        val readyLanguage = preferredReadyLanguage(context)
        val richerText = if (readyLanguage != null) {
            val chunks = buildList {
                samples.forEachIndexed { index, uri ->
                    currentCoroutineContext().ensureActive()
                    onProgress(context.uiText(languageCode, "magic_progress_ocr_sample", index + 1, samples.size))
                    add(try {
                        OcrProcessor.recognize(context, uri, readyLanguage)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        ""
                    })
                }
            }
            chunks.joinToString("\n").trim().ifBlank { preliminaryText }
        } else preliminaryText

        val fallbackName = context.uiText(languageCode, when (primary.kind) {
            SmartScanClassifier.Kind.INVOICE -> "magic_name_invoice"
            SmartScanClassifier.Kind.RECEIPT -> "magic_name_receipt"
            SmartScanClassifier.Kind.CARD -> "magic_name_card"
            SmartScanClassifier.Kind.BUSINESS_CARD -> "magic_name_business"
            SmartScanClassifier.Kind.TABLE -> "magic_name_table"
            SmartScanClassifier.Kind.DOCUMENT -> "magic_name_document"
        })
        val suggestedName = OcrProcessor.suggestBaseName(richerText).takeUnless { it.equals("OCR", true) } ?: fallbackName
        val detectedCategory = OcrProcessor.suggestCategory(richerText)
        val folder = context.uiText(languageCode, when (detectedCategory) {
            OcrProcessor.Category.INVOICES -> "magic_folder_invoices"
            OcrProcessor.Category.RECEIPTS -> "magic_folder_receipts"
            OcrProcessor.Category.CONTRACTS -> "magic_folder_contracts"
            OcrProcessor.Category.PERSONAL -> "magic_folder_personal"
            OcrProcessor.Category.CORRESPONDENCE -> "magic_folder_correspondence"
            OcrProcessor.Category.DOCUMENTS -> when (primary.kind) {
                SmartScanClassifier.Kind.INVOICE -> "magic_folder_invoices"
                SmartScanClassifier.Kind.RECEIPT -> "magic_folder_receipts"
                SmartScanClassifier.Kind.CARD, SmartScanClassifier.Kind.BUSINESS_CARD -> "magic_folder_cards"
                else -> "magic_folder_documents"
            }
        })

        Proposal(
            kind = primary.kind,
            typeLabel = context.uiText(languageCode, when (primary.kind) {
                SmartScanClassifier.Kind.DOCUMENT -> "magic_kind_document"
                SmartScanClassifier.Kind.CARD -> "magic_kind_card"
                SmartScanClassifier.Kind.INVOICE -> "magic_kind_invoice"
                SmartScanClassifier.Kind.RECEIPT -> "magic_kind_receipt"
                SmartScanClassifier.Kind.TABLE -> "magic_kind_table"
                SmartScanClassifier.Kind.BUSINESS_CARD -> "magic_kind_business"
            }),
            confidence = primary.confidence,
            suggestedName = sanitize(suggestedName).ifBlank { fallbackName },
            suggestedFolder = sanitize(folder).ifBlank { context.uiText(languageCode, "magic_folder_documents") },
            action = context.uiText(languageCode, "magic_action_full"),
            textPreview = richerText.take(900),
            language = readyLanguage ?: OcrProcessor.Language.ALL,
            pageCount = imageUris.size
        )
    }

    suspend fun execute(
        context: Context,
        imageUris: List<Uri>,
        languageCode: String,
        proposal: Proposal,
        finalName: String,
        finalFolder: String,
        onProgress: suspend (String) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        require(imageUris.isNotEmpty()) { context.uiText(languageCode, "magic_error_no_pages") }
        val safeName = sanitize(finalName).ifBlank { proposal.suggestedName }
        val safeFolder = sanitize(finalFolder).ifBlank { proposal.suggestedFolder }

        onProgress(context.uiText(languageCode, "magic_progress_prepare"))
        val cachedPages = ScanProcessor.cacheScannerPages(context, imageUris)
        require(cachedPages.size == imageUris.size) {
            ScanProcessor.cleanup(cachedPages)
            context.uiText(languageCode, "magic_error_prepare")
        }
        var correctedPages = 0
        var processedFiles: List<File> = emptyList()
        val searchable = try {
            val enhancedPages = buildList {
                cachedPages.forEachIndexed { index, page ->
                    currentCoroutineContext().ensureActive()
                    onProgress(context.uiText(languageCode, "magic_progress_detect", index + 1, cachedPages.size))
                    val preview = ScanProcessor.renderSourcePreview(page, 1200)
                    val detection = try { StaticDocumentDetector.detect(preview) } finally { preview.recycle() }
                    if (detection.quad != null) correctedPages++
                    add(page.copy(
                        filter = ScanFilter.DOCUMENT,
                        perspective = detection.quad
                    ))
                }
            }
            processedFiles = ScanProcessor.savePageImages(context, enhancedPages, 90)
            val processedUris = processedFiles.map { FileUtils.contentUri(context, it) }
            onProgress(context.uiText(languageCode, "magic_progress_searchable"))
            OcrProcessor.createSearchablePdfFromImages(
                context = context,
                imageUris = processedUris,
                language = proposal.language
            ) { page, total -> onProgress(context.uiText(languageCode, "magic_progress_page", page, total)) }
        } finally {
            ScanProcessor.cleanup(cachedPages)
            processedFiles.mapNotNull { it.parentFile }.distinct().forEach { runCatching { it.deleteRecursively() } }
        }

        val destination = FileUtils.outputDir(context, "AB Magic Flow/$safeFolder")
        var pdf = searchable.pdf
        pdf = runCatching { FileUtils.renameFile(pdf, safeName) }.getOrElse { pdf }
        if (pdf.parentFile?.canonicalFile != destination.canonicalFile) {
            pdf = FileUtils.moveFile(context, pdf, destination)
        }

        var textFile = searchable.textFile
        textFile = runCatching { FileUtils.renameFile(textFile, "${safeName}_OCR") }.getOrElse { textFile }
        if (textFile.parentFile?.canonicalFile != destination.canonicalFile) {
            textFile = FileUtils.moveFile(context, textFile, destination)
        }

        val history = OperationHistoryStore.record(
            context,
            tool = "AB Magic Flow",
            title = "${proposal.typeLabel}: ${pdf.name}",
            outputs = listOf(pdf, textFile)
        )
        Result(pdf, textFile, history.id, searchable.pageCount, safeFolder, correctedPages)
    }

    private fun preferredReadyLanguage(context: Context): OcrProcessor.Language? = when {
        OcrProcessor.isModelReady(context, OcrProcessor.Language.ALL) -> OcrProcessor.Language.ALL
        OcrProcessor.isModelReady(context, OcrProcessor.Language.ARABIC_ENGLISH) -> OcrProcessor.Language.ARABIC_ENGLISH
        OcrProcessor.isModelReady(context, OcrProcessor.Language.ENGLISH_FRENCH) -> OcrProcessor.Language.ENGLISH_FRENCH
        OcrProcessor.isModelReady(context, OcrProcessor.Language.ENGLISH) -> OcrProcessor.Language.ENGLISH
        OcrProcessor.isModelReady(context, OcrProcessor.Language.ARABIC) -> OcrProcessor.Language.ARABIC
        OcrProcessor.isModelReady(context, OcrProcessor.Language.FRENCH) -> OcrProcessor.Language.FRENCH
        else -> null
    }

    private fun sanitize(value: String): String = value.trim()
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .take(60)
}
