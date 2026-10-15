package com.abfilepro.app.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** User-chosen Android document export. The approved original stays untouched. */
object ConversionCopyExporter {
    suspend fun exportVerified(context: Context, source: File, target: Uri) = withContext(Dispatchers.IO) {
        require(FileUtils.verifySavedFile(context, source)) { "ملف المصدر غير صالح" }
        val expected = MessageDigest.getInstance("SHA-256")
        val written = context.contentResolver.openOutputStream(target, "w")
            ?: error("تعذر فتح مكان الحفظ المحدد")
        source.inputStream().buffered().use { input ->
            written.buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val bytes = input.read(buffer)
                    if (bytes < 0) break
                    if (bytes > 0) {
                        expected.update(buffer, 0, bytes)
                        output.write(buffer, 0, bytes)
                    }
                }
                output.flush()
            }
        }
        val actual = MessageDigest.getInstance("SHA-256")
        val readBack = context.contentResolver.openInputStream(target)
            ?: error("لا يمكن قراءة الملف للتأكد من اكتمال الحفظ")
        readBack.buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val bytes = input.read(buffer)
                if (bytes < 0) break
                if (bytes > 0) actual.update(buffer, 0, bytes)
            }
        }
        check(expected.digest().contentEquals(actual.digest())) { "فشل التحقق من سلامة النسخة المحفوظة" }
    }
}
