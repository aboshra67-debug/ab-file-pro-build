package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object OcrProcessor {
    enum class Language(val code: String) {
        ARABIC("ara"),
        ENGLISH("eng"),
        FRENCH("fra"),
        ARABIC_ENGLISH("ara+eng"),
        ENGLISH_FRENCH("eng+fra"),
        ALL("ara+eng+fra")
    }

    enum class Model(val code: String) {
        ARABIC("ara"), ENGLISH("eng"), FRENCH("fra")
    }

    enum class Category { INVOICES, RECEIPTS, CONTRACTS, PERSONAL, CORRESPONDENCE, DOCUMENTS }

    data class CropSpec(
        val left: Float = 0f,
        val top: Float = 0f,
        val right: Float = 0f,
        val bottom: Float = 0f
    ) {
        fun isEmpty(): Boolean = left + top + right + bottom < .002f
    }

    data class SearchablePdfResult(
        val pdf: File,
        val textFile: File,
        val text: String,
        val pageCount: Int
    )

    data class OcrWord(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val confidence: Float
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val width: Int get() = (right - left).coerceAtLeast(1)
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    data class OcrLayout(
        val plainText: String,
        val words: List<OcrWord>,
        val imageWidth: Int,
        val imageHeight: Int
    )

    suspend fun recognize(
        context: Context,
        uri: Uri,
        language: Language,
        crop: CropSpec = CropSpec()
    ): String = withContext(Dispatchers.IO) {
        ensureLanguage(context, language)
        val bitmap = loadBitmap(context, uri, crop = crop)
        try {
            recognizeBitmap(context, bitmap, language)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Image -> editable Word is deliberately separate from generic OCR.
     * A screenshot with foreground dialog and background text needs an explicit crop;
     * the old full-page PSM_AUTO dropped whole Arabic lines in this case.
     */
    suspend fun recognizeForWord(
        context: Context,
        uri: Uri,
        language: Language,
        crop: CropSpec
    ): String = withContext(Dispatchers.IO) {
        ensureLanguage(context, language)
        val loaded = loadBitmap(context, uri, maxDimension = 3200, crop = crop)
        val bigger = if (max(loaded.width, loaded.height) < 1600) {
            val factor = (1600f / max(loaded.width, loaded.height)).coerceIn(1f, 2f)
            Bitmap.createScaledBitmap(
                loaded,
                (loaded.width * factor).roundToInt().coerceAtLeast(1),
                (loaded.height * factor).roundToInt().coerceAtLeast(1),
                true
            )
        } else loaded
        try {
            val api = createApi(context, language)
            try {
                // PSM_SINGLE_BLOCK is only used when the user isolates a text region.
                if (!crop.isEmpty()) api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                api.setImage(bigger)
                api.getUTF8Text()?.trim().orEmpty()
            } finally {
                runCatching { api.recycle() }
            }
        } finally {
            if (bigger !== loaded) bigger.recycle()
            loaded.recycle()
        }
    }

    suspend fun recognizeLayout(
        context: Context,
        uri: Uri,
        language: Language,
        crop: CropSpec = CropSpec()
    ): OcrLayout = withContext(Dispatchers.IO) {
        ensureLanguage(context, language)
        val bitmap = loadBitmap(context, uri, maxDimension = 3200, crop = crop)
        try {
            recognizeBitmapLayout(context, bitmap, language)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun loadPreview(context: Context, uri: Uri, crop: CropSpec = CropSpec()): Bitmap = withContext(Dispatchers.IO) {
        loadBitmap(context, uri, maxDimension = 1200, crop = crop)
    }

    /**
     * Detects a high-contrast light dialog on a dimmed mobile screenshot.
     * Returns null for normal photos and scans; never silently crops the source.
     * The user may review and change the suggested boundaries before OCR.
     */
    suspend fun suggestDialogCrop(context: Context, uri: Uri): CropSpec? = withContext(Dispatchers.IO) {
        val thumb = loadBitmap(context, uri, maxDimension = 760)
        try {
            detectLightDialog(thumb)
        } finally {
            thumb.recycle()
        }
    }

    private fun detectLightDialog(image: Bitmap): CropSpec? {
        val w = image.width
        val h = image.height
        if (w < 120 || h < 240) return null
        fun luminosity(x: Int, y: Int): Int {
            val pixel = image.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
            val red = (pixel shr 16) and 255
            val green = (pixel shr 8) and 255
            val blue = pixel and 255
            return (red * 30 + green * 59 + blue * 11) / 100
        }
        fun mid(a: Int, b: Int, c: Int): Int = a + b + c - minOf(a, b, c) - maxOf(a, b, c)
        fun fx(fraction: Float) = (w * fraction).roundToInt().coerceIn(0, w - 1)
        fun fy(fraction: Float) = (h * fraction).roundToInt().coerceIn(0, h - 1)

        // Guard against cropping normal white-page photographs or all-white scans.
        val outside = listOf(
            luminosity(fx(.03f), fy(.03f)), luminosity(fx(.97f), fy(.03f)),
            luminosity(fx(.03f), fy(.97f)), luminosity(fx(.97f), fy(.97f))
        ).sorted()[1]
        val center = mid(
            luminosity(fx(.25f), fy(.50f)),
            luminosity(fx(.50f), fy(.50f)),
            luminosity(fx(.75f), fy(.50f))
        )
        if (center < 175 || center - outside < 55) return null
        val threshold = ((center + outside) / 2).coerceIn(155, 205)
        // Use the dialog's inner side margins; text can cross the visual center of a row.
        fun row(y: Int): Int = intArrayOf(
            luminosity(fx(.11f), y), luminosity(fx(.18f), y),
            luminosity(fx(.82f), y), luminosity(fx(.89f), y)
        ).sorted()[2]
        val middleY = fy(.50f)
        if (row(middleY) <= threshold) return null
        var top = middleY
        var bottom = middleY
        while (top > 0 && row(top - 1) > threshold) top--
        while (bottom < h - 1 && row(bottom + 1) > threshold) bottom++
        if (bottom - top < (h * .22f).roundToInt() || bottom - top > (h * .87f).roundToInt()) return null
        if (top < (h * .025f).roundToInt() || bottom > (h * .975f).roundToInt()) return null

        val band = bottom - top
        fun column(x: Int): Int = intArrayOf(
            luminosity(x, top + band / 10), luminosity(x, top + band / 4),
            luminosity(x, bottom - band / 4), luminosity(x, bottom - band / 10)
        ).sorted()[2]
        val middleX = fx(.50f)
        if (column(middleX) <= threshold) return null
        var left = middleX
        var right = middleX
        while (left > 0 && column(left - 1) > threshold) left--
        while (right < w - 1 && column(right + 1) > threshold) right++
        if (right - left < (w * .45f).roundToInt() || right - left > (w * .95f).roundToInt()) return null
        if (left < (w * .025f).roundToInt() || right > (w * .975f).roundToInt()) return null

        val safeX = (w * .012f).roundToInt()
        val safeY = (h * .008f).roundToInt()
        val x1 = (left - safeX).coerceAtLeast(0)
        val x2 = (right + safeX).coerceAtMost(w - 1)
        val y1 = (top - safeY).coerceAtLeast(0)
        val y2 = (bottom + safeY).coerceAtMost(h - 1)
        val suggestion = CropSpec(x1.toFloat() / w, y1.toFloat() / h,
            (w - 1 - x2).toFloat() / w, (h - 1 - y2).toFloat() / h)
        // Existing crop sliders support at most 45% per side; keep them editable.
        if (listOf(suggestion.left, suggestion.top, suggestion.right, suggestion.bottom).any { it > .45f }) return null
        return suggestion
    }

    suspend fun downloadModel(context: Context, model: Model) = withContext(Dispatchers.IO) {
        val tessData = tessDataDir(context)
        ensureModel(context, tessData, model.code)
    }

    fun removeModel(context: Context, model: Model): Boolean {
        val file = File(tessDataDir(context), "${model.code}.traineddata")
        return !file.exists() || file.delete()
    }

    fun isModelReady(context: Context, language: Language): Boolean {
        val tessData = tessDataDir(context)
        return language.code.split('+').all { File(tessData, "$it.traineddata").length() > 100_000 }
    }

    fun isModelReady(context: Context, model: Model): Boolean =
        File(tessDataDir(context), "${model.code}.traineddata").length() > 100_000

    suspend fun createSearchablePdf(
        context: Context,
        uri: Uri,
        language: Language,
        onProgress: suspend (page: Int, total: Int) -> Unit = { _, _ -> }
    ): SearchablePdfResult = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        ensureLanguage(context, language)
        val sourceFile = File(context.cacheDir, "ocr_pdf_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر قراءة ملف PDF" }
            FileOutputStream(sourceFile).use { output -> input.copyTo(output) }
        }

        try {
            PDDocument.load(sourceFile).use { document ->
                require(document.numberOfPages > 0) { "ملف PDF لا يحتوي على صفحات" }
                require(document.numberOfPages <= 80) { "للحفاظ على ثبات الهاتف، OCR PDF يدعم حتى 80 صفحة في العملية الواحدة حاليًا" }

                val renderer = PDFRenderer(document)
                val arabicFont = loadSystemFont(document, true)
                val latinFont = loadSystemFont(document, false) ?: arabicFont
                val api = createApi(context, language)
                val allText = StringBuilder()
                try {
                    for (index in 0 until document.numberOfPages) {
                        currentCoroutineContext().ensureActive()
                        onProgress(index + 1, document.numberOfPages)
                        val bitmap = renderer.renderImageWithDPI(index, 160f)
                        val pageText = try {
                            api.setImage(bitmap)
                            api.getUTF8Text()?.trim().orEmpty()
                        } finally {
                            bitmap.recycle()
                        }
                        if (pageText.isNotBlank()) {
                            if (allText.isNotEmpty()) allText.append("\n\n")
                            allText.append("--- صفحة ${index + 1} ---\n").append(pageText)
                            appendInvisibleText(
                                document = document,
                                pageIndex = index,
                                text = pageText,
                                arabicFont = arabicFont,
                                latinFont = latinFont
                            )
                        }
                    }
                } finally {
                    runCatching { api.recycle() }
                }

                val out = File(FileUtils.outputDir(context, "ocr_pdf"), "Searchable_${System.currentTimeMillis()}.pdf")
                document.save(out)
                val txt = File(FileUtils.outputDir(context, "ocr"), "PDF_OCR_${System.currentTimeMillis()}.txt")
                txt.writeText(allText.toString(), Charsets.UTF_8)
                SearchablePdfResult(out, txt, allText.toString(), document.numberOfPages)
            }
        } finally {
            sourceFile.delete()
        }
    }

    suspend fun createSearchablePdfFromImages(
        context: Context,
        imageUris: List<Uri>,
        language: Language,
        onProgress: suspend (page: Int, total: Int) -> Unit = { _, _ -> }
    ): SearchablePdfResult = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        require(imageUris.isNotEmpty()) { "اختر صورة واحدة على الأقل" }
        require(imageUris.size <= 60) { "للحفاظ على ثبات الهاتف، العملية تدعم حتى 60 صورة حاليًا" }
        ensureLanguage(context, language)

        val out = File(FileUtils.outputDir(context, "ocr_pdf"), "Searchable_Images_${System.currentTimeMillis()}.pdf")
        val txt = File(FileUtils.outputDir(context, "ocr"), "Images_OCR_${System.currentTimeMillis()}.txt")
        val allText = StringBuilder()

        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { document ->
            val arabicFont = loadSystemFont(document, true)
            val latinFont = loadSystemFont(document, false) ?: arabicFont
            imageUris.forEachIndexed { index, uri ->
                currentCoroutineContext().ensureActive()
                onProgress(index + 1, imageUris.size)
                val bitmap = loadBitmap(context, uri, maxDimension = 2400)
                try {
                    val layout = recognizeBitmapLayout(context, bitmap, language)
                    val maxPageSide = 842f
                    val pageWidth: Float
                    val pageHeight: Float
                    if (bitmap.height >= bitmap.width) {
                        pageHeight = maxPageSide
                        pageWidth = (maxPageSide * bitmap.width / bitmap.height.toFloat()).coerceAtLeast(220f)
                    } else {
                        pageWidth = maxPageSide
                        pageHeight = (maxPageSide * bitmap.height / bitmap.width.toFloat()).coerceAtLeast(220f)
                    }
                    val page = PDPage(PDRectangle(pageWidth, pageHeight))
                    document.addPage(page)
                    val image = LosslessFactory.createFromImage(document, bitmap)
                    PDPageContentStream(document, page).use { stream ->
                        stream.drawImage(image, 0f, 0f, pageWidth, pageHeight)
                    }
                    appendInvisibleLayoutText(
                        document = document,
                        page = page,
                        layout = layout,
                        pageWidth = pageWidth,
                        pageHeight = pageHeight,
                        arabicFont = arabicFont,
                        latinFont = latinFont
                    )
                    if (layout.plainText.isNotBlank()) {
                        if (allText.isNotEmpty()) allText.append("\n\n")
                        allText.append("--- صفحة ${index + 1} ---\n").append(layout.plainText)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            require(document.numberOfPages > 0) { "تعذر إنشاء صفحات PDF" }
            document.save(out)
        }
        txt.writeText(allText.toString(), Charsets.UTF_8)
        SearchablePdfResult(out, txt, allText.toString(), imageUris.size)
    }

    fun saveText(context: Context, text: String, baseName: String = "OCR"): File {
        require(text.isNotBlank()) { "لا يوجد نص للحفظ" }
        val safe = baseName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "OCR" }
        return File(FileUtils.outputDir(context, "ocr"), "${safe}_${System.currentTimeMillis()}.txt").apply {
            writeText(text, Charsets.UTF_8)
        }
    }

    private fun recognizeBitmap(context: Context, bitmap: Bitmap, language: Language): String {
        val api = createApi(context, language)
        return try {
            api.setImage(bitmap)
            api.getUTF8Text()?.trim().orEmpty()
        } finally {
            runCatching { api.recycle() }
        }
    }

    private fun recognizeBitmapLayout(context: Context, bitmap: Bitmap, language: Language): OcrLayout {
        val api = createApi(context, language)
        return try {
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            api.setImage(bitmap)
            val plainText = api.getUTF8Text()?.trim().orEmpty()
            val words = mutableListOf<OcrWord>()
            val iterator = api.getResultIterator()
            if (iterator != null) {
                try {
                    iterator.begin()
                    do {
                        val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                        val rect = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        val confidence = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        if (text.isNotBlank() && rect != null && rect.width() > 1 && rect.height() > 1) {
                            words += OcrWord(
                                text = text,
                                left = rect.left,
                                top = rect.top,
                                right = rect.right,
                                bottom = rect.bottom,
                                confidence = confidence
                            )
                        }
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                } finally {
                    runCatching { iterator.delete() }
                }
            }
            OcrLayout(plainText, words, bitmap.width, bitmap.height)
        } finally {
            runCatching { api.recycle() }
        }
    }

    private fun createApi(context: Context, language: Language): TessBaseAPI {
        val dataRoot = File(context.filesDir, "tesseract").apply { mkdirs() }
        val api = TessBaseAPI()
        check(api.init(dataRoot.absolutePath, language.code, TessBaseAPI.OEM_LSTM_ONLY)) {
            "تعذر تشغيل محرك OCR"
        }
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        api.setVariable("preserve_interword_spaces", "1")
        return api
    }

    private fun ensureLanguage(context: Context, language: Language) {
        val tessData = tessDataDir(context)
        language.code.split('+').forEach { ensureModel(context, tessData, it) }
    }

    private fun tessDataDir(context: Context): File =
        File(File(context.filesDir, "tesseract").apply { mkdirs() }, "tessdata").apply { mkdirs() }

    private fun ensureModel(context: Context, tessDataDir: File, code: String) {
        val target = File(tessDataDir, "$code.traineddata")
        if (target.length() > 100_000) return

        val temp = File(tessDataDir, "$code.traineddata.asset")
        runCatching { temp.delete() }
        try {
            context.assets.open("tessdata/$code.traineddata").use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            check(temp.length() > 100_000) { "ملف نموذج OCR المدمج غير صالح: ${modelLabel(code)}" }
            if (target.exists()) target.delete()
            check(temp.renameTo(target)) { "تعذر تجهيز نموذج OCR المدمج: ${modelLabel(code)}" }
        } catch (error: Throwable) {
            runCatching { temp.delete() }
            throw IllegalStateException(
                "نموذج OCR المدمج غير متاح: ${modelLabel(code)}. ${error.message.orEmpty()}",
                error
            )
        }
    }

    private fun modelLabel(code: String): String = when (code) {
        "ara" -> "العربية"
        "fra" -> "Français"
        else -> "English"
    }

    fun suggestBaseName(text: String): String {
        val normalized = text.lineSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .firstOrNull { it.length in 4..72 && it.any(Char::isLetter) }
            .orEmpty()
        val cleaned = normalized
            .replace(Regex("[\\/:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(48)
        return cleaned.ifBlank { "OCR" }
    }

    fun findMatches(text: String, query: String): Int {
        val needle = query.trim()
        if (needle.isEmpty()) return 0
        return Regex(Regex.escape(needle), RegexOption.IGNORE_CASE).findAll(text).count()
    }

    fun suggestCategory(text: String): Category {
        val value = text.lowercase()
        fun hasAny(vararg terms: String): Boolean = terms.any { value.contains(it) }
        return when {
            hasAny("invoice", "facture", "فاتورة", "vat", "tva", "ضريبة") -> Category.INVOICES
            hasAny("receipt", "reçu", "إيصال", "paid", "payé", "مدفوع") -> Category.RECEIPTS
            hasAny("contract", "contrat", "عقد", "agreement", "اتفاق") -> Category.CONTRACTS
            hasAny("passport", "passeport", "جواز", "identity", "identité", "هوية") -> Category.PERSONAL
            hasAny("letter", "courrier", "مراسلة", "خطاب", "memo", "مذكرة") -> Category.CORRESPONDENCE
            else -> Category.DOCUMENTS
        }
    }

    private fun loadBitmap(
        context: Context,
        uri: Uri,
        maxDimension: Int = 2800,
        crop: CropSpec = CropSpec()
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "image_unavailable" }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "image_decode_failed" }

        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "image_unavailable" }
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample.coerceAtLeast(1)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } ?: error("image_decode_failed")

        val cropped = cropBitmap(decoded, crop)
        val maxSide = max(cropped.width, cropped.height)
        if (maxSide <= maxDimension) return cropped

        val scale = maxDimension.toFloat() / maxSide.toFloat()
        val resized = Bitmap.createScaledBitmap(
            cropped,
            (cropped.width * scale).roundToInt().coerceAtLeast(1),
            (cropped.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
        if (resized !== cropped) cropped.recycle()
        return resized
    }

    private fun cropBitmap(source: Bitmap, crop: CropSpec): Bitmap {
        if (crop.isEmpty()) return source
        val left = crop.left.coerceIn(0f, .45f)
        val top = crop.top.coerceIn(0f, .45f)
        val right = crop.right.coerceIn(0f, .45f)
        val bottom = crop.bottom.coerceIn(0f, .45f)
        require(left + right < .9f && top + bottom < .9f) { "منطقة القص صغيرة جدًا" }
        val x = (source.width * left).roundToInt().coerceIn(0, source.width - 1)
        val y = (source.height * top).roundToInt().coerceIn(0, source.height - 1)
        val w = (source.width * (1f - left - right)).roundToInt().coerceAtLeast(1).coerceAtMost(source.width - x)
        val h = (source.height * (1f - top - bottom)).roundToInt().coerceAtLeast(1).coerceAtMost(source.height - y)
        val cropped = Bitmap.createBitmap(source, x, y, w, h)
        if (cropped !== source) source.recycle()
        return cropped
    }

    private fun loadSystemFont(document: PDDocument, arabic: Boolean): PDType0Font? {
        val candidates = if (arabic) {
            listOf(
                "/system/fonts/NotoNaskhArabic-Regular.ttf",
                "/system/fonts/NotoSansArabic-Regular.ttf",
                "/system/fonts/DroidSans.ttf",
                "/system/fonts/Roboto-Regular.ttf"
            )
        } else {
            listOf(
                "/system/fonts/Roboto-Regular.ttf",
                "/system/fonts/NotoSans-Regular.ttf",
                "/system/fonts/DroidSans.ttf"
            )
        }
        for (path in candidates) {
            val file = File(path)
            if (file.exists() && file.length() > 10_000) {
                runCatching { return PDType0Font.load(document, file) }
            }
        }
        return null
    }

    private fun appendInvisibleLayoutText(
        document: PDDocument,
        page: PDPage,
        layout: OcrLayout,
        pageWidth: Float,
        pageHeight: Float,
        arabicFont: PDType0Font?,
        latinFont: PDType0Font?
    ) {
        if (layout.words.isEmpty() || (arabicFont == null && latinFont == null)) return
        val scaleX = pageWidth / layout.imageWidth.coerceAtLeast(1).toFloat()
        val scaleY = pageHeight / layout.imageHeight.coerceAtLeast(1).toFloat()
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
            stream.setRenderingMode(RenderingMode.NEITHER)
            layout.words.take(2500).forEach { word ->
                val value = word.text.filter { ch -> ch.code >= 32 }.trim()
                if (value.isBlank()) return@forEach
                val containsArabic = value.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\u08A0'..'\u08FF' }
                val font = if (containsArabic) (arabicFont ?: latinFont) else (latinFont ?: arabicFont)
                if (font == null) return@forEach
                val fontSize = (word.height * scaleY * .82f).coerceIn(4f, 42f)
                val x = (word.left * scaleX).coerceIn(0f, (pageWidth - 2f).coerceAtLeast(0f))
                val y = (pageHeight - word.bottom * scaleY).coerceIn(0f, (pageHeight - 2f).coerceAtLeast(0f))
                var textOpen = false
                runCatching {
                    stream.beginText()
                    textOpen = true
                    stream.setFont(font, fontSize)
                    stream.newLineAtOffset(x, y)
                    stream.showText(value)
                    stream.endText()
                    textOpen = false
                }.onFailure {
                    if (textOpen) runCatching { stream.endText() }
                }
            }
        }
    }

    private fun appendInvisibleText(
        document: PDDocument,
        pageIndex: Int,
        text: String,
        arabicFont: PDType0Font?,
        latinFont: PDType0Font?
    ) {
        val page = document.getPage(pageIndex)
        if (arabicFont == null && latinFont == null) return
        val media = page.mediaBox
        val maxLines = 140
        val lines = text.lines().flatMap { raw ->
            val clean = raw.replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), " ").trim()
            if (clean.length <= 180) listOf(clean) else clean.chunked(180)
        }.filter { it.isNotBlank() }.take(maxLines)
        if (lines.isEmpty()) return

        PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        ).use { stream ->
            stream.setRenderingMode(RenderingMode.NEITHER)
            var y = (media.height - 10f).coerceAtLeast(10f)
            for (line in lines) {
                val containsArabic = line.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\u08A0'..'\u08FF' }
                val font = if (containsArabic) (arabicFont ?: latinFont) else (latinFont ?: arabicFont)
                if (font == null) continue
                val safe = line.filter { ch -> ch == '\t' || ch.code >= 32 }.replace('\t', ' ')
                if (safe.isBlank()) continue
                runCatching {
                    stream.beginText()
                    stream.setFont(font, 7f)
                    stream.newLineAtOffset(8f, y)
                    stream.showText(safe)
                    stream.endText()
                }
                y -= 7.5f
                if (y < 8f) y = 8f
            }
        }
    }
}
