package com.abfilepro.app.ui.i18n

import android.content.Context
import com.abfilepro.app.core.OcrProcessor
import com.abfilepro.app.core.DocumentFrameAnalyzer
import com.abfilepro.app.core.ScanCaptureMode
import com.abfilepro.app.core.ScanFilter
import com.abfilepro.app.core.ScanPdfPageSize
import com.abfilepro.app.core.SmartScanClassifier
import com.abfilepro.app.core.RuntimeSafety
import kotlinx.coroutines.CancellationException

/** Resource-backed UI localization. Screen and core code contain stable keys only; user-facing copy lives in XML resources. */
fun Context.uiText(languageCode: String, key: String, vararg args: Any?): String {
    val requested = resources.getIdentifier("${key}_${languageCode}", "string", packageName)
    val fallback = resources.getIdentifier("${key}_en", "string", packageName)
    val id = if (requested != 0) requested else fallback
    if (id == 0) return key
    return if (args.isEmpty()) getString(id) else getString(id, *args)
}

fun Context.uiText(language: AppLanguage, key: String, vararg args: Any?): String =
    uiText(language.code, key, *args)

fun Context.localizedLabel(language: AppLanguage, mode: ScanCaptureMode): String = uiText(language, when (mode) {
    ScanCaptureMode.DOCUMENT -> "scan_mode_document"
    ScanCaptureMode.BATCH -> "scan_mode_batch"
    ScanCaptureMode.BOOK -> "scan_mode_book"
    ScanCaptureMode.ID_CARD -> "scan_mode_id_card"
    ScanCaptureMode.DUPLEX -> "scan_mode_duplex"
    ScanCaptureMode.TRANSLATION -> "scan_mode_translation"
    ScanCaptureMode.PASSPORT -> "scan_mode_passport"
    ScanCaptureMode.RECEIPT -> "scan_mode_receipt"
    ScanCaptureMode.WHITEBOARD -> "scan_mode_whiteboard"
    ScanCaptureMode.PHOTO -> "scan_mode_photo"
})

fun Context.localizedLabel(language: AppLanguage, filter: ScanFilter): String = uiText(language, when (filter) {
    ScanFilter.ORIGINAL -> "scan_filter_original"
    ScanFilter.DOCUMENT -> "scan_filter_document"
    ScanFilter.COLOR -> "scan_filter_color"
    ScanFilter.GRAYSCALE -> "scan_filter_grayscale"
    ScanFilter.BW -> "scan_filter_bw"
    ScanFilter.PHOTO -> "scan_filter_photo"
    ScanFilter.SHADOW_REMOVE -> "scan_filter_shadow_remove"
    ScanFilter.FADED_TEXT -> "scan_filter_faded_text"
})

fun Context.localizedLabel(language: AppLanguage, size: ScanPdfPageSize): String = uiText(language, when (size) {
    ScanPdfPageSize.AUTO -> "scan_page_auto"
    ScanPdfPageSize.A4 -> "scan_page_a4"
    ScanPdfPageSize.LETTER -> "scan_page_letter"
})

fun Context.localizedLabel(language: AppLanguage, value: OcrProcessor.Language): String = uiText(language, when (value) {
    OcrProcessor.Language.ARABIC -> "ocr_lang_ar"
    OcrProcessor.Language.ENGLISH -> "ocr_lang_en"
    OcrProcessor.Language.FRENCH -> "ocr_lang_fr"
    OcrProcessor.Language.ARABIC_ENGLISH -> "ocr_lang_ar_en"
    OcrProcessor.Language.ENGLISH_FRENCH -> "ocr_lang_en_fr"
    OcrProcessor.Language.ALL -> "ocr_lang_all"
})

fun Context.localizedLabel(language: AppLanguage, value: OcrProcessor.Model): String = uiText(language, when (value) {
    OcrProcessor.Model.ARABIC -> "ocr_model_ar"
    OcrProcessor.Model.ENGLISH -> "ocr_model_en"
    OcrProcessor.Model.FRENCH -> "ocr_model_fr"
})

fun Context.localizedLabel(language: AppLanguage, kind: SmartScanClassifier.Kind): String = uiText(language, when (kind) {
    SmartScanClassifier.Kind.DOCUMENT -> "magic_kind_document"
    SmartScanClassifier.Kind.CARD -> "magic_kind_card"
    SmartScanClassifier.Kind.INVOICE -> "magic_kind_invoice"
    SmartScanClassifier.Kind.RECEIPT -> "magic_kind_receipt"
    SmartScanClassifier.Kind.TABLE -> "magic_kind_table"
    SmartScanClassifier.Kind.BUSINESS_CARD -> "magic_kind_business"
})

fun Context.localizedOcrCategory(language: AppLanguage, value: OcrProcessor.Category): String = uiText(language, when (value) {
    OcrProcessor.Category.INVOICES -> "magic_folder_invoices"
    OcrProcessor.Category.RECEIPTS -> "magic_folder_receipts"
    OcrProcessor.Category.CONTRACTS -> "magic_folder_contracts"
    OcrProcessor.Category.PERSONAL -> "magic_folder_personal"
    OcrProcessor.Category.CORRESPONDENCE -> "magic_folder_correspondence"
    OcrProcessor.Category.DOCUMENTS -> "magic_folder_documents"
})

fun Context.localizedScannerWarning(language: AppLanguage, warning: DocumentFrameAnalyzer.Warning): String = uiText(language, when (warning) {
    DocumentFrameAnalyzer.Warning.LOW_LIGHT -> "scanner_warn_low_light"
    DocumentFrameAnalyzer.Warning.GLARE -> "scanner_warn_glare"
    DocumentFrameAnalyzer.Warning.BLUR_OR_SHAKE -> "scanner_warn_blur"
    DocumentFrameAnalyzer.Warning.NO_DOCUMENT -> "scanner_warn_no_document"
    DocumentFrameAnalyzer.Warning.MOVE_CLOSER -> "scanner_warn_move_closer"
    DocumentFrameAnalyzer.Warning.HOLD_STILL -> "scanner_warn_hold_still"
})


fun Context.localizedOperationFailure(language: AppLanguage, error: Throwable, fallbackKey: String): String = when (error) {
    is RuntimeSafety.InsufficientStorageException -> uiText(language, "v15_storage_low")
    is CancellationException -> uiText(language, "v15_cancelled")
    else -> uiText(language, fallbackKey)
}
