package com.abfilepro.app.core

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** P24: a text block seen by the camera, normalized to the upright frame (0..1). */
data class LiveTextBlock(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** P24: the same block with its translation, ready to draw over the preview. */
data class LiveTranslatedBlock(val block: LiveTextBlock, val translated: String)

enum class LiveTranslationStatus { IDLE, DOWNLOADING, READY, SAME_LANGUAGE, NEEDS_INTERNET, FAILED }

/** Pure helpers (JVM-testable). */
object LiveTranslationGeometry {
    /**
     * Normalizes an ML Kit bounding box given in the upright image to 0..1 and drops tiny or
     * empty blocks (noise) so the overlay stays readable.
     */
    fun normalize(text: String, left: Int, top: Int, right: Int, bottom: Int, uprightWidth: Int, uprightHeight: Int): LiveTextBlock? {
        if (uprightWidth <= 0 || uprightHeight <= 0) return null
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length < 2 || clean.none { it.isLetter() }) return null
        val l = (left.toFloat() / uprightWidth).coerceIn(0f, 1f)
        val t = (top.toFloat() / uprightHeight).coerceIn(0f, 1f)
        val r = (right.toFloat() / uprightWidth).coerceIn(0f, 1f)
        val b = (bottom.toFloat() / uprightHeight).coerceIn(0f, 1f)
        if (r - l < .02f || b - t < .01f) return null
        return LiveTextBlock(clean, l, t, r, b)
    }

    /** Upright size of a camera frame of [width] x [height] rotated by [rotationDegrees]. */
    fun uprightSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (((rotationDegrees % 180) + 180) % 180 == 90) height to width else width to height

    /** Picks the translation target: Arabic text goes to English, anything else to Arabic. */
    fun targetFor(sourceTag: String, appLanguage: String): String = when {
        sourceTag.startsWith("ar") -> if (appLanguage == "fr") "fr" else "en"
        appLanguage == "ar" || appLanguage.isBlank() -> "ar"
        sourceTag.startsWith(appLanguage) -> "ar"
        else -> appLanguage
    }
}

/**
 * P24: live camera translation with ML Kit (on-device after the language file is downloaded
 * once). Keeps a small cache so the same line is not translated on every frame.
 */
class LiveTranslator(private val appLanguage: String) : AutoCloseable {
    private val identifier = LanguageIdentification.getClient()
    private val translators = mutableMapOf<String, Translator>()
    private val ready = mutableSetOf<String>()
    private val cache = object : LinkedHashMap<String, String>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 400
    }
    private val lock = Mutex()
    @Volatile var status: LiveTranslationStatus = LiveTranslationStatus.IDLE
        private set

    suspend fun translate(blocks: List<LiveTextBlock>): List<LiveTranslatedBlock> = lock.withLock {
        if (blocks.isEmpty()) return emptyList()
        val sample = blocks.joinToString(" ") { it.text }.take(600)
        val detected = runCatching { identifier.identifyLanguage(sample).await() }.getOrDefault("und")
        val source = if (detected == "und") {
            if (sample.any { it.code in 0x0600..0x06FF }) "ar" else "en"
        } else detected
        val target = LiveTranslationGeometry.targetFor(source, appLanguage)
        val sourceCode = TranslateLanguage.fromLanguageTag(source)
        val targetCode = TranslateLanguage.fromLanguageTag(target)
        if (sourceCode == null || targetCode == null || sourceCode == targetCode) {
            status = LiveTranslationStatus.SAME_LANGUAGE
            return emptyList()
        }
        val key = "$sourceCode>$targetCode"
        val translator = translators.getOrPut(key) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(sourceCode).setTargetLanguage(targetCode).build())
        }
        if (key !in ready) {
            status = LiveTranslationStatus.DOWNLOADING
            val downloaded = runCatching { translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
            if (downloaded.isFailure) {
                status = LiveTranslationStatus.NEEDS_INTERNET
                return emptyList()
            }
            ready += key
        }
        status = LiveTranslationStatus.READY
        blocks.mapNotNull { block ->
            val cacheKey = "$key|${block.text}"
            val translated = cache[cacheKey] ?: runCatching { translator.translate(block.text).await() }.getOrNull()
                ?.trim()?.takeIf { it.isNotBlank() }?.also { cache[cacheKey] = it }
            translated?.let { LiveTranslatedBlock(block, it) }
        }
    }

    override fun close() {
        runCatching { identifier.close() }
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }
}
