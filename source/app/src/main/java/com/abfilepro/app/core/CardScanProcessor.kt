package com.abfilepro.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class CardPageLayout {
    HORIZONTAL, VERTICAL
}

object CardScanProcessor {
    fun cacheSide(context: Context, uri: Uri, side: String): File {
        val dir = File(context.cacheDir, "card_scan/${System.currentTimeMillis()}").apply { mkdirs() }
        val out = File(dir, "${side}.jpg")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر قراءة صورة البطاقة" }
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        require(out.length() > 0L) { "تعذر حفظ صورة البطاقة مؤقتًا" }
        return out
    }

    fun loadPreview(file: File, maxDimension: Int = 1500): Bitmap = decodeScaled(file, maxDimension)

    fun saveCombinedPdf(
        context: Context,
        front: File,
        back: File,
        layout: CardPageLayout,
        baseName: String
    ): File {
        require(front.exists() && back.exists()) { "صوّر وجهي البطاقة أولًا" }
        val safeName = baseName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Card" }
        val output = File(FileUtils.outputDir(context, "card_scans"), "${safeName}_${System.currentTimeMillis()}.pdf")

        val first = decodeScaled(front, 2200)
        val second = decodeScaled(back, 2200)
        val pdf = PdfDocument()
        try {
            val pageWidth = if (layout == CardPageLayout.HORIZONTAL) 842 else 595
            val pageHeight = if (layout == CardPageLayout.HORIZONTAL) 595 else 842
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val margin = 34f
            val gap = 24f

            if (layout == CardPageLayout.HORIZONTAL) {
                val availableWidth = (pageWidth - margin * 2f - gap) / 2f
                val availableHeight = pageHeight - margin * 2f
                val leftBox = RectF(margin, margin, margin + availableWidth, margin + availableHeight)
                val rightBox = RectF(leftBox.right + gap, margin, pageWidth - margin, margin + availableHeight)
                canvas.drawBitmap(first, null, fitInside(first, leftBox), paint)
                canvas.drawBitmap(second, null, fitInside(second, rightBox), paint)
            } else {
                val availableWidth = pageWidth - margin * 2f
                val availableHeight = (pageHeight - margin * 2f - gap) / 2f
                val topBox = RectF(margin, margin, margin + availableWidth, margin + availableHeight)
                val bottomBox = RectF(margin, topBox.bottom + gap, margin + availableWidth, pageHeight - margin)
                canvas.drawBitmap(first, null, fitInside(first, topBox), paint)
                canvas.drawBitmap(second, null, fitInside(second, bottomBox), paint)
            }

            pdf.finishPage(page)
            FileOutputStream(output).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
            first.recycle()
            second.recycle()
        }
        require(output.length() > 0L) { "تعذر إنشاء ملف البطاقة" }
        return output
    }

    fun cleanup(vararg files: File?) {
        files.filterNotNull().mapNotNull { it.parentFile }.distinct().forEach { dir ->
            runCatching { dir.deleteRecursively() }
        }
    }

    private fun fitInside(bitmap: Bitmap, box: RectF): RectF {
        val scale = min(box.width() / bitmap.width.toFloat(), box.height() / bitmap.height.toFloat())
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = box.left + (box.width() - width) / 2f
        val top = box.top + (box.height() - height) / 2f
        return RectF(left, top, left + width, top + height)
    }

    private fun decodeScaled(file: File, maxDimension: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "تعذر قراءة صورة البطاقة" }
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: error("تعذر قراءة صورة البطاقة")
        val currentMax = max(decoded.width, decoded.height)
        if (currentMax <= maxDimension) return decoded
        val scale = maxDimension.toFloat() / currentMax.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}
