package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

object PdfEditorEngine {
    data class PagePatch(
        val pageNumber: Int,
        val imageFile: File,
        val replaceOriginal: Boolean
    )

    /**
     * Applies multiple edited pages in one document pass and saves one new PDF copy.
     * Normal pages receive a transparent overlay. Redacted pages replace their original
     * content/resources/annotations with a fully flattened raster page.
     */
    suspend fun applyPagePatches(
        context: Context,
        pdfUri: Uri,
        patches: List<PagePatch>
    ): File = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        require(patches.isNotEmpty()) { "لا توجد تعديلات للحفظ" }
        patches.forEach { require(it.imageFile.isFile) { "إحدى طبقات التعديل غير صالحة" } }
        val hasRedaction = patches.any { it.replaceOriginal }
        val prefix = if (hasRedaction) "Redacted_Edited" else "Edited"
        val out = File(FileUtils.outputDir(context, "pdf"), "${prefix}_${System.currentTimeMillis()}.pdf")
        openPdfInput(context, pdfUri).use { stream ->
            requireNotNull(stream) { "تعذر فتح ملف PDF" }
            PDDocument.load(stream).use { document ->
                patches.sortedBy { it.pageNumber }.forEach { patch ->
                    currentCoroutineContext().ensureActive()
                    require(patch.pageNumber in 1..document.numberOfPages) { "رقم الصفحة غير صحيح" }
                    val page = document.getPage(patch.pageNumber - 1)
                    val box = page.mediaBox
                    val bitmap = requireNotNull(BitmapFactory.decodeFile(patch.imageFile.absolutePath)) {
                        "تعذر قراءة طبقة تعديل الصفحة ${patch.pageNumber}"
                    }
                    try {
                        val image = LosslessFactory.createFromImage(document, bitmap)
                        if (patch.replaceOriginal) {
                            page.resources = PDResources()
                            runCatching { page.annotations.clear() }
                            PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.OVERWRITE, true, true
                            ).use { content ->
                                content.drawImage(image, 0f, 0f, box.width, box.height)
                            }
                        } else {
                            PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true
                            ).use { content ->
                                content.drawImage(image, 0f, 0f, box.width, box.height)
                            }
                        }
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
                currentCoroutineContext().ensureActive()
                document.save(out)
            }
        }
        out
    }

    suspend fun flattenOverlay(
        context: Context,
        pdfUri: Uri,
        pageNumber: Int,
        overlay: Bitmap
    ): File = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        require(!overlay.isRecycled) { "طبقة التعديل غير صالحة" }
        val out = File(FileUtils.outputDir(context, "pdf"), "Edited_${System.currentTimeMillis()}.pdf")
        openPdfInput(context, pdfUri).use { stream ->
            requireNotNull(stream) { "تعذر فتح ملف PDF" }
            PDDocument.load(stream).use { document ->
                require(pageNumber in 1..document.numberOfPages) { "رقم الصفحة غير صحيح" }
                val page = document.getPage(pageNumber - 1)
                val box = page.mediaBox
                val image = LosslessFactory.createFromImage(document, overlay)
                PDPageContentStream(
                    document,
                    page,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
                ).use { content ->
                    content.drawImage(image, 0f, 0f, box.width, box.height)
                }
                document.save(out)
            }
        }
        out
    }

    /**
     * Used only when a page contains a redaction. The complete visible page is rasterized,
     * then the original page content/resources/annotations are replaced before saving.
     * This prevents the covered text or image from remaining extractable underneath a
     * cosmetic black rectangle.
     */
    suspend fun replacePageWithRaster(
        context: Context,
        pdfUri: Uri,
        pageNumber: Int,
        flattenedPage: Bitmap
    ): File = withContext(Dispatchers.IO) {
        RuntimeSafety.ensureStorage(context)
        currentCoroutineContext().ensureActive()
        require(!flattenedPage.isRecycled) { "صورة الصفحة المعدلة غير صالحة" }
        val out = File(FileUtils.outputDir(context, "pdf"), "Redacted_${System.currentTimeMillis()}.pdf")
        openPdfInput(context, pdfUri).use { stream ->
            requireNotNull(stream) { "تعذر فتح ملف PDF" }
            PDDocument.load(stream).use { document ->
                require(pageNumber in 1..document.numberOfPages) { "رقم الصفحة غير صحيح" }
                val page = document.getPage(pageNumber - 1)
                val box = page.mediaBox

                // Drop the old page resources and annotations before writing the flattened page.
                // This deliberately sacrifices text selection on this page when true redaction is used.
                page.resources = PDResources()
                runCatching { page.annotations.clear() }

                val image = LosslessFactory.createFromImage(document, flattenedPage)
                PDPageContentStream(
                    document,
                    page,
                    PDPageContentStream.AppendMode.OVERWRITE,
                    true,
                    true
                ).use { content ->
                    content.drawImage(image, 0f, 0f, box.width, box.height)
                }
                document.save(out)
            }
        }
        out
    }

    private fun openPdfInput(context: Context, pdfUri: Uri) =
        if (pdfUri.scheme == "file") FileInputStream(requireNotNull(pdfUri.path))
        else context.contentResolver.openInputStream(pdfUri)
}
