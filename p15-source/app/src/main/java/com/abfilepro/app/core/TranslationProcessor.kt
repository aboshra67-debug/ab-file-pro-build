package com.abfilepro.app.core

import android.content.Context
import android.net.Uri
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

enum class TranslationTarget(val languageTag: String) {
    ARABIC("ar"), ENGLISH("en"), FRENCH("fr")
}

data class ScanTranslationResult(
    val sourceLanguage: String,
    val target: TranslationTarget,
    val sourceText: String,
    val translatedText: String,
    val file: File
)

object TranslationProcessor {
    suspend fun translateReviewedPage(
        context: Context,
        page: ScanDraftPage,
        quality: Int,
        preferredTarget: TranslationTarget? = null
    ): ScanTranslationResult = withContext(Dispatchers.IO) {
        val exported = ScanProcessor.exportEditedPageToCache(context, page, quality)
        try {
            val sourceText = OcrProcessor.recognize(
                context = context,
                uri = Uri.fromFile(exported),
                language = OcrProcessor.Language.ALL
            ).trim()
            require(sourceText.isNotBlank()) { "لم يتم العثور على نص واضح للترجمة" }
            val sourceLanguage = identifyLanguage(sourceText)
            val target = preferredTarget ?: defaultTarget(sourceLanguage)
            translateAndSave(context, sourceText, sourceLanguage, target)
        } finally {
            runCatching { exported.parentFile?.deleteRecursively() }
        }
    }

    suspend fun retranslate(
        context: Context,
        current: ScanTranslationResult,
        target: TranslationTarget
    ): ScanTranslationResult = withContext(Dispatchers.IO) {
        translateAndSave(context, current.sourceText, current.sourceLanguage, target)
    }

    private suspend fun identifyLanguage(text: String): String {
        val identifier = LanguageIdentification.getClient()
        return try {
            val identified = identifier.identifyLanguage(text).await()
            if (identified == "und") fallbackLanguage(text) else identified
        } finally {
            identifier.close()
        }
    }

    private fun fallbackLanguage(text: String): String = when {
        text.any { it.code in 0x0600..0x06FF } -> "ar"
        else -> "en"
    }

    private fun defaultTarget(sourceLanguage: String): TranslationTarget =
        if (sourceLanguage.startsWith("ar", ignoreCase = true)) TranslationTarget.ENGLISH else TranslationTarget.ARABIC

    private suspend fun translateAndSave(
        context: Context,
        sourceText: String,
        sourceLanguage: String,
        requestedTarget: TranslationTarget
    ): ScanTranslationResult {
        val sourceCode = TranslateLanguage.fromLanguageTag(sourceLanguage)
            ?: if (sourceLanguage.startsWith("ar")) TranslateLanguage.ARABIC else TranslateLanguage.ENGLISH
        val effectiveTarget = if (sourceCode == requestedTarget.languageTag) {
            if (requestedTarget == TranslationTarget.ARABIC) TranslationTarget.ENGLISH else TranslationTarget.ARABIC
        } else requestedTarget
        val targetCode = TranslateLanguage.fromLanguageTag(effectiveTarget.languageTag)
            ?: error("لغة الترجمة غير مدعومة")
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceCode)
                .setTargetLanguage(targetCode)
                .build()
        )
        val translated = try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            translator.translate(sourceText).await().trim()
        } finally {
            translator.close()
        }
        require(translated.isNotBlank()) { "تعذر إنشاء الترجمة" }
        val safeSource = sourceLanguage.replace(Regex("[^A-Za-z-]"), "").ifBlank { "auto" }
        val file = File(
            FileUtils.outputDir(context, "translations"),
            "Translation_${safeSource}_to_${effectiveTarget.languageTag}_${System.currentTimeMillis()}.txt"
        )
        file.writeText(
            buildString {
                append("Source ($sourceLanguage):\n")
                append(sourceText)
                append("\n\nTranslation (${effectiveTarget.languageTag}):\n")
                append(translated)
            },
            Charsets.UTF_8
        )
        return ScanTranslationResult(sourceLanguage, effectiveTarget, sourceText, translated, file)
    }
}
