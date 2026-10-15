package com.abfilepro.app.core

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import java.io.File

object PdfViewerUtils {
    fun pageCount(file: File): Int {
        require(file.exists()) { "ملف PDF غير موجود" }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            SafePdfRenderer.open(descriptor).use { renderer ->
                return renderer.pageCount
            }
        }
    }

    fun renderPage(file: File, pageIndex: Int, targetWidth: Int): Bitmap {
        require(file.exists()) { "ملف PDF غير موجود" }
        require(targetWidth > 0) { "عرض الصفحة غير صالح" }

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            SafePdfRenderer.open(descriptor).use { renderer ->
                require(pageIndex in 0 until renderer.pageCount) { "رقم الصفحة غير صالح" }
                renderer.openPage(pageIndex).use { page ->
                    val width = targetWidth.coerceAtLeast(180)
                    val ratio = page.height.toFloat() / page.width.toFloat()
                    val height = (width * ratio).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null)
                    return bitmap
                }
            }
        }
    }
}
