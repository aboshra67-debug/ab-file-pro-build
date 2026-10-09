package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

object PdfTools {
    suspend fun imagesToPdf(context: Context, imageUris: List<Uri>): File = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        require(imageUris.isNotEmpty())
        val out = File(FileUtils.outputDir(context, "pdf"), "ABFile_${System.currentTimeMillis()}.pdf")
        val pdf = PdfDocument()
        imageUris.forEachIndexed { index, uri ->
            currentCoroutineContext().ensureActive()
            val bitmap = decodeSampledImage(context, uri, maxDimension = 2400)
                ?: return@forEachIndexed
            try {
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                val page = pdf.startPage(pageInfo)
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdf.finishPage(page)
            } finally {
                bitmap.recycle()
            }
        }
        FileOutputStream(out).use { pdf.writeTo(it) }
        pdf.close()
        out
    }

    suspend fun pdfToImages(context: Context, uri: Uri): List<File> = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        val dir = FileUtils.outputDir(context, "pdf_images/${System.currentTimeMillis()}")
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("تعذر فتح ملف PDF")
        val renderer = PdfRenderer(pfd)
        val results = mutableListOf<File>()
        try {
            for (i in 0 until renderer.pageCount) {
                currentCoroutineContext().ensureActive()
                renderer.openPage(i).use { page ->
                    val maxDimension = 2400
                    val scale = minOf(1f, maxDimension.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1))
                    val renderWidth = (page.width * scale).toInt().coerceAtLeast(1)
                    val renderHeight = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                    try {
                        val matrix = android.graphics.Matrix().apply { setScale(scale, scale) }
                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val file = File(dir, "page_${i + 1}.png")
                        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        results += file
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            renderer.close()
            pfd.close()
        }
        results
    }

    suspend fun mergePdfs(context: Context, uris: List<Uri>): File = withContext(Dispatchers.IO) {
        require(uris.size >= 2)
        RuntimeSafety.ensureStorage(context)
        val out = File(FileUtils.outputDir(context, "pdf"), "Merged_${System.currentTimeMillis()}.pdf")
        val tempDir = File(context.cacheDir, "pdf_merge_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val merger = PDFMergerUtility().apply { destinationFileName = out.absolutePath }
            uris.forEachIndexed { index, uri ->
                currentCoroutineContext().ensureActive()
                val temp = File(tempDir, "source_${index + 1}.pdf")
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "pdf_source_unavailable" }
                    temp.outputStream().buffered().use { output ->
                        RuntimeSafety.copyCancellable(input, output)
                    }
                }
                merger.addSource(temp)
            }
            currentCoroutineContext().ensureActive()
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            out
        } catch (error: Throwable) {
            out.delete()
            throw error
        } finally {
            tempDir.deleteRecursively()
        }
    }

    suspend fun splitPdf(context: Context, uri: Uri): List<File> = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        val dir = FileUtils.outputDir(context, "split/${System.currentTimeMillis()}")
        val input = context.contentResolver.openInputStream(uri) ?: error("تعذر فتح الملف")
        val document = PDDocument.load(input)
        try {
            val parts = Splitter().split(document)
            parts.mapIndexed { index, part ->
                val file = File(dir, "page_${index + 1}.pdf")
                part.save(file)
                part.close()
                file
            }
        } finally {
            document.close()
            input.close()
        }
    }

    /**
     * Visual compression: each page is rendered and rebuilt using a downscaled bitmap.
     * This strongly reduces scan-heavy PDFs while preserving the visual page.
     */
    suspend fun compressPdf(context: Context, uri: Uri, scale: Float = 0.72f, outputFile: File? = null): File = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        // Clean Share supplies its private draft target. Other callers retain
        // the existing output directory and compression behavior.
        val out = outputFile ?: File(FileUtils.outputDir(context, "pdf"), "Compressed_${System.currentTimeMillis()}.pdf")
        require(FileUtils.isInsideRoot(context, out)) { "مسار ضغط PDF غير صالح" }
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("تعذر فتح ملف PDF")
        val renderer = PdfRenderer(pfd)
        val pdf = PdfDocument()
        try {
            for (i in 0 until renderer.pageCount) {
                currentCoroutineContext().ensureActive()
                renderer.openPage(i).use { page ->
                    val renderWidth = (page.width * scale).toInt().coerceAtLeast(320)
                    val renderHeight = (page.height * scale).toInt().coerceAtLeast(420)
                    val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.RGB_565)
                    val matrix = android.graphics.Matrix().apply {
                        setScale(renderWidth.toFloat() / page.width, renderHeight.toFloat() / page.height)
                    }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val info = PdfDocument.PageInfo.Builder(page.width, page.height, i + 1).create()
                    val newPage = pdf.startPage(info)
                    val dst = android.graphics.RectF(0f, 0f, page.width.toFloat(), page.height.toFloat())
                    newPage.canvas.drawBitmap(bitmap, null, dst, null)
                    pdf.finishPage(newPage)
                    bitmap.recycle()
                }
            }
            FileOutputStream(out).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
            renderer.close()
            pfd.close()
        }
        out
    }

    suspend fun addPageNumbers(
        context: Context,
        uri: Uri,
        startAt: Int = 1
    ): File = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        require(startAt >= 0) { "رقم البداية غير صحيح" }
        val out = File(FileUtils.outputDir(context, "pdf"), "Numbered_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر فتح ملف PDF" }
            PDDocument.load(input).use { document ->
                require(document.numberOfPages > 0) { "المستند لا يحتوي صفحات" }
                for (index in 0 until document.numberOfPages) {
                    currentCoroutineContext().ensureActive()
                    val page = document.getPage(index)
                    val media = page.mediaBox
                    val label = "${startAt + index} / ${startAt + document.numberOfPages - 1}"
                    PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 10f)
                        stream.newLineAtOffset((media.width / 2f - 18f).coerceAtLeast(8f), 12f)
                        stream.showText(label)
                        stream.endText()
                    }
                }
                document.save(out)
            }
        }
        out
    }

    suspend fun pdfToLongImage(
        context: Context,
        uri: Uri,
        preferredWidth: Int = 1080
    ): File = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("تعذر فتح ملف PDF")
        val renderer = PdfRenderer(pfd)
        try {
            require(renderer.pageCount in 1..30) { "الصورة الطويلة تدعم من 1 إلى 30 صفحة في العملية الواحدة" }
            val sourceSizes = buildList {
                for (index in 0 until renderer.pageCount) {
                    currentCoroutineContext().ensureActive()
                    add(renderer.openPage(index).use { it.width.coerceAtLeast(1) to it.height.coerceAtLeast(1) })
                }
            }
            var targetWidth = preferredWidth.coerceIn(480, 1440)
            fun projectedHeight(width: Int): Int = sourceSizes.sumOf { (w, h) ->
                (h * (width.toDouble() / w.toDouble())).toInt().coerceAtLeast(1)
            } + (sourceSizes.size - 1).coerceAtLeast(0) * 8

            val maxHeight = 30_000
            val maxPixels = 24_000_000L
            var totalHeight = projectedHeight(targetWidth)
            if (totalHeight > maxHeight || targetWidth.toLong() * totalHeight > maxPixels) {
                val byHeight = maxHeight.toDouble() / totalHeight.coerceAtLeast(1)
                val byPixels = kotlin.math.sqrt(maxPixels.toDouble() / (targetWidth.toDouble() * totalHeight.coerceAtLeast(1)))
                val factor = minOf(1.0, byHeight, byPixels)
                targetWidth = (targetWidth * factor).toInt().coerceAtLeast(480)
                totalHeight = projectedHeight(targetWidth)
            }
            require(totalHeight <= maxHeight && targetWidth.toLong() * totalHeight <= maxPixels) {
                "الملف كبير جدًا لإنشاء صورة طويلة بأمان على الهاتف"
            }

            val combined = Bitmap.createBitmap(targetWidth, totalHeight, Bitmap.Config.RGB_565)
            val canvas = android.graphics.Canvas(combined)
            canvas.drawColor(android.graphics.Color.WHITE)
            var y = 0
            try {
                sourceSizes.forEachIndexed { index, (sourceW, sourceH) ->
                    currentCoroutineContext().ensureActive()
                    val height = (sourceH * (targetWidth.toDouble() / sourceW.toDouble())).toInt().coerceAtLeast(1)
                    val pageBitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.RGB_565)
                    try {
                        renderer.openPage(index).use { page ->
                            val matrix = android.graphics.Matrix().apply {
                                setScale(targetWidth.toFloat() / page.width.coerceAtLeast(1), height.toFloat() / page.height.coerceAtLeast(1))
                            }
                            page.render(pageBitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                        canvas.drawBitmap(pageBitmap, 0f, y.toFloat(), null)
                        y += height
                        if (index != sourceSizes.lastIndex) y += 8
                    } finally {
                        pageBitmap.recycle()
                    }
                }
                val out = File(FileUtils.outputDir(context, "long_image"), "Long_${System.currentTimeMillis()}.jpg")
                FileOutputStream(out).use { output ->
                    check(combined.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "تعذر حفظ الصورة الطويلة" }
                }
                out
            } finally {
                combined.recycle()
            }
        } finally {
            renderer.close()
            pfd.close()
        }
    }

    suspend fun rotateAllPages(context: Context, uri: Uri, degrees: Int = 90): File = withContext(Dispatchers.IO) {
        val out = File(FileUtils.outputDir(context, "pdf"), "Rotated_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر فتح الملف" }
            PDDocument.load(input).use { document ->
                for (page in document.pages) {
                    val current = page.rotation
                    page.rotation = ((current + degrees) % 360 + 360) % 360
                }
                document.save(out)
            }
        }
        out
    }

    suspend fun rotatePages(
        context: Context,
        uri: Uri,
        oneBasedPages: List<Int>,
        degrees: Int = 90
    ): File = withContext(Dispatchers.IO) {
        require(oneBasedPages.isNotEmpty()) { "اكتب أرقام الصفحات المطلوب تدويرها" }
        require(degrees % 90 == 0) { "زاوية التدوير يجب أن تكون من مضاعفات 90" }
        val out = File(FileUtils.outputDir(context, "pdf"), "RotatedSelected_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر فتح الملف" }
            PDDocument.load(input).use { document ->
                oneBasedPages.distinct().forEach { pageNumber ->
                    require(pageNumber in 1..document.numberOfPages) { "رقم صفحة غير صحيح: $pageNumber" }
                    val page = document.getPage(pageNumber - 1)
                    page.rotation = ((page.rotation + degrees) % 360 + 360) % 360
                }
                document.save(out)
            }
        }
        out
    }

    suspend fun extractPages(context: Context, uri: Uri, oneBasedPages: List<Int>): File = withContext(Dispatchers.IO) {
        require(oneBasedPages.isNotEmpty()) { "اكتب أرقام الصفحات المطلوب استخراجها" }
        val out = File(FileUtils.outputDir(context, "pdf"), "Extracted_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر فتح الملف" }
            PDDocument.load(input).use { source ->
                val pages = oneBasedPages.distinct()
                pages.forEach { pageNumber ->
                    require(pageNumber in 1..source.numberOfPages) { "رقم صفحة غير صحيح: $pageNumber" }
                }
                PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { target ->
                    pages.forEach { pageNumber ->
                        currentCoroutineContext().ensureActive()
                        target.importPage(source.getPage(pageNumber - 1))
                    }
                    target.save(out)
                }
            }
        }
        out
    }

    suspend fun deletePages(context: Context, uri: Uri, oneBasedPages: List<Int>): File = withContext(Dispatchers.IO) {
        require(oneBasedPages.isNotEmpty()) { "اكتب أرقام الصفحات المطلوب حذفها" }
        val out = File(FileUtils.outputDir(context, "pdf"), "PagesRemoved_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر فتح الملف" }
            PDDocument.load(input).use { document ->
                val indexes = oneBasedPages.distinct().map { it - 1 }.sortedDescending()
                indexes.forEach { index ->
                    require(index in 0 until document.numberOfPages) { "رقم صفحة غير صحيح: ${index + 1}" }
                    document.removePage(index)
                }
                require(document.numberOfPages > 0) { "لا يمكن حذف جميع صفحات المستند" }
                document.save(out)
            }
        }
        out
    }

    suspend fun reorderPages(context: Context, uri: Uri, oneBasedOrder: List<Int>): File = withContext(Dispatchers.IO) {
        require(oneBasedOrder.isNotEmpty()) { "اكتب ترتيب الصفحات" }
        val out = File(FileUtils.outputDir(context, "pdf"), "Reordered_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر فتح الملف" }
            PDDocument.load(input).use { source ->
                require(oneBasedOrder.size == source.numberOfPages) { "يجب كتابة كل الصفحات مرة واحدة" }
                val expected = (1..source.numberOfPages).toSet()
                require(oneBasedOrder.toSet() == expected) { "الترتيب يجب أن يحتوي كل أرقام الصفحات بدون تكرار" }
                PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { target ->
                    oneBasedOrder.forEach { pageNumber ->
                        currentCoroutineContext().ensureActive()
                        target.importPage(source.getPage(pageNumber - 1))
                    }
                    target.save(out)
                }
            }
        }
        out
    }

    suspend fun protectPdf(context: Context, uri: Uri, password: String): File = withContext(Dispatchers.IO) {
        require(password.length >= 4) { "كلمة المرور يجب أن تكون 4 أحرف على الأقل" }
        val out = File(FileUtils.outputDir(context, "pdf"), "Protected_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر فتح الملف" }
            PDDocument.load(input).use { document ->
                val access = AccessPermission()
                val ownerPassword = "ABFP-${System.currentTimeMillis()}-$password"
                val policy = StandardProtectionPolicy(ownerPassword, password, access).apply {
                    encryptionKeyLength = 128
                    permissions = access
                }
                document.protect(policy)
                document.save(out)
            }
        }
        out
    }

    suspend fun unlockPdf(context: Context, uri: Uri, password: String): File = withContext(Dispatchers.IO) {
        require(password.isNotBlank()) { "اكتب كلمة المرور" }
        val out = File(FileUtils.outputDir(context, "pdf"), "Unlocked_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر فتح الملف" }
            PDDocument.load(input, password).use { document ->
                document.isAllSecurityToBeRemoved = true
                document.save(out)
            }
        }
        out
    }

    suspend fun addSignatureImage(context: Context, pdfUri: Uri, imageUri: Uri): File = withContext(Dispatchers.IO) {
        val bitmap = context.contentResolver.openInputStream(imageUri).use { BitmapFactory.decodeStream(it) }
            ?: error("تعذر قراءة صورة التوقيع")
        val out = File(FileUtils.outputDir(context, "pdf"), "Signed_${System.currentTimeMillis()}.pdf")
        try {
            context.contentResolver.openInputStream(pdfUri).use { input ->
                requireNotNull(input) { "تعذر فتح ملف PDF" }
                PDDocument.load(input).use { document ->
                    require(document.numberOfPages > 0) { "المستند لا يحتوي صفحات" }
                    val page = document.getPage(document.numberOfPages - 1)
                    val media = page.mediaBox
                    val maxWidth = media.width * 0.28f
                    val maxHeight = media.height * 0.14f
                    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
                    var width = maxWidth
                    var height = width / ratio
                    if (height > maxHeight) {
                        height = maxHeight
                        width = height * ratio
                    }
                    val x = media.width - width - 28f
                    val y = 28f
                    val image = LosslessFactory.createFromImage(document, bitmap)
                    PDPageContentStream(
                        document,
                        page,
                        PDPageContentStream.AppendMode.APPEND,
                        true,
                        true
                    ).use { stream ->
                        stream.drawImage(image, x, y, width, height)
                    }
                    document.save(out)
                }
            }
        } finally {
            bitmap.recycle()
        }
        out
    }

    suspend fun getPageCount(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر فتح ملف PDF" }
            PDDocument.load(input).use { it.numberOfPages }
        }
    }

    suspend fun addSignatureBitmap(
        context: Context,
        pdfUri: Uri,
        bitmap: Bitmap,
        pageNumber: Int,
        xFraction: Float,
        yFraction: Float,
        widthFraction: Float
    ): File = withContext(Dispatchers.IO) {
        val out = File(FileUtils.outputDir(context, "pdf"), "SignedDrawn_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(pdfUri).use { input ->
            requireNotNull(input) { "تعذر فتح ملف PDF" }
            PDDocument.load(input).use { document ->
                require(pageNumber in 1..document.numberOfPages) { "رقم الصفحة غير صحيح" }
                val page = document.getPage(pageNumber - 1)
                val media = page.mediaBox
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
                val width = (media.width * widthFraction.coerceIn(.12f, .65f)).coerceAtLeast(50f)
                val height = width / ratio
                val x = ((media.width - width) * xFraction.coerceIn(0f, 1f)).coerceAtLeast(0f)
                val y = ((media.height - height) * yFraction.coerceIn(0f, 1f)).coerceAtLeast(0f)
                val image = LosslessFactory.createFromImage(document, bitmap)
                PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                    stream.drawImage(image, x, y, width, height)
                }
                document.save(out)
            }
        }
        out
    }

    private fun resolvePageIndexes(document: PDDocument, oneBasedPages: List<Int>?): Set<Int>? {
        if (oneBasedPages == null) return null
        require(oneBasedPages.isNotEmpty()) { "اكتب أرقام صفحات صحيحة أو اترك الحقل فارغًا لتطبيقها على الكل" }
        val unique = oneBasedPages.distinct()
        unique.forEach { pageNumber ->
            require(pageNumber in 1..document.numberOfPages) { "رقم صفحة غير صحيح: $pageNumber" }
        }
        return unique.map { it - 1 }.toSet()
    }

    suspend fun addTextWatermark(
        context: Context,
        pdfUri: Uri,
        text: String,
        opacityPercent: Int = 30,
        widthFraction: Float = .52f,
        oneBasedPages: List<Int>? = null
    ): File = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "اكتب نص العلامة المائية" }
        val alpha = (255f * (opacityPercent.coerceIn(10, 90) / 100f)).toInt()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(alpha, 30, 91, 255)
            textSize = 72f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val bitmapWidth = (bounds.width() + 80).coerceAtLeast(320)
        val bitmapHeight = (bounds.height() + 80).coerceAtLeast(140)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).drawText(
            text,
            bitmapWidth / 2f,
            bitmapHeight / 2f - (paint.ascent() + paint.descent()) / 2f,
            paint
        )
        try {
            addImageWatermarkBitmap(context, pdfUri, bitmap, widthFraction, "WatermarkText", oneBasedPages)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun addImageWatermark(
        context: Context,
        pdfUri: Uri,
        imageUri: Uri,
        opacityPercent: Int = 30,
        widthFraction: Float = .42f,
        oneBasedPages: List<Int>? = null
    ): File = withContext(Dispatchers.IO) {
        val source = context.contentResolver.openInputStream(imageUri).use { BitmapFactory.decodeStream(it) }
            ?: error("تعذر قراءة صورة العلامة المائية")
        val prepared = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (255f * (opacityPercent.coerceIn(10, 90) / 100f)).toInt()
            }
            android.graphics.Canvas(prepared).drawBitmap(source, 0f, 0f, paint)
            addImageWatermarkBitmap(context, pdfUri, prepared, widthFraction, "WatermarkImage", oneBasedPages)
        } finally {
            prepared.recycle()
            source.recycle()
        }
    }

    private suspend fun addImageWatermarkBitmap(
        context: Context,
        pdfUri: Uri,
        bitmap: Bitmap,
        widthFraction: Float,
        prefix: String,
        oneBasedPages: List<Int>? = null
    ): File {
        val out = File(FileUtils.outputDir(context, "pdf"), "${prefix}_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(pdfUri).use { input ->
            requireNotNull(input) { "تعذر فتح ملف PDF" }
            PDDocument.load(input).use { document ->
                val selectedIndexes = resolvePageIndexes(document, oneBasedPages)
                val image = LosslessFactory.createFromImage(document, bitmap)
                for (index in 0 until document.numberOfPages) {
                    currentCoroutineContext().ensureActive()
                    if (selectedIndexes != null && index !in selectedIndexes) continue
                    val page = document.getPage(index)
                    val media = page.mediaBox
                    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
                    val width = media.width * widthFraction.coerceIn(.15f, .8f)
                    val height = width / ratio
                    val x = (media.width - width) / 2f
                    val y = (media.height - height) / 2f
                    PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                        stream.drawImage(image, x, y, width, height)
                    }
                }
                document.save(out)
            }
        }
        return out
    }

    private fun decodeSampledImage(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input -> BitmapFactory.decodeStream(input, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri).use { input -> BitmapFactory.decodeStream(input, null, options) } ?: return null
        val largest = maxOf(decoded.width, decoded.height)
        if (largest <= maxDimension) return decoded
        val ratio = maxDimension.toFloat() / largest
        val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt().coerceAtLeast(1), (decoded.height * ratio).toInt().coerceAtLeast(1), true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

}
