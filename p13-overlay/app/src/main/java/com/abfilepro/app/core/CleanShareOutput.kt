package com.abfilepro.app.core

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Owns only one Clean Share attempt. Existing workspace files are never writable here. */
internal object CleanShareOutput {
    data class Selection(val file: File, val compressed: Boolean)

    suspend fun <T> create(outputDirectory: () -> File, block: suspend (Draft) -> T): T {
        var owner: Draft? = null
        try {
            return withContext(Dispatchers.IO) {
                Draft(outputDirectory()).also { owner = it }.use { block(it) }
            }
        } catch (error: Throwable) {
            // withContext can discard a completed result when its caller cancels
            // during dispatch back. Keep the owner outside that boundary.
            withContext(NonCancellable + Dispatchers.IO) { owner?.discardUnchangedPublication() }
            throw error
        }
    }

    class Draft internal constructor(private val outputDirectory: File) : Closeable {
        val directory: File = Files.createTempDirectory(outputDirectory.toPath(), ".ab_clean_share_").toFile()
        private var published: File? = null
        private var publicationToken: String? = null

        fun file(name: String): File {
            require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name)
            return File(directory, name)
        }

        private fun owns(file: File): Boolean = file.parentFile?.canonicalFile == directory.canonicalFile &&
            file.canonicalFile.toPath() == file.toPath().toAbsolutePath().normalize()

        suspend fun publish(staged: File, base: String, extension: String): File {
            require(owns(staged) && staged.isFile && staged.length() > 0) { "تعذر اعتماد نسخة المشاركة" }
            require(base.isNotBlank() && '/' !in base && '\\' !in base && extension.matches(Regex("[A-Za-z0-9]+")))
            check(published == null) { "تم اعتماد ناتج هذه المحاولة بالفعل" }
            val proof = OwnedOutputSnapshot.capture(staged).token
            // A fresh name also separates simultaneous attempts using the same
            // display name. Never request replacement of an existing path.
            val target = File(outputDirectory, "${base}_${UUID.randomUUID()}.$extension")
            currentCoroutineContext().ensureActive()
            Files.move(staged.toPath(), target.toPath())
            published = target
            publicationToken = proof
            currentCoroutineContext().ensureActive()
            return target
        }

        suspend fun preferSmallerPdf(original: File, candidate: File, pages: Int, compress: suspend () -> Unit): Selection {
            require(owns(original) && owns(candidate) && original.canonicalFile != candidate.canonicalFile)
            verifyPdf(original, pages)
            try {
                currentCoroutineContext().ensureActive()
                compress()
                currentCoroutineContext().ensureActive()
                verifyPdf(candidate, pages)
                if (candidate.length() < original.length()) return Selection(candidate, true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed or invalid optional compression never discards the
                // complete cleaned PDF. Both drafts still belong to this owner.
            }
            return Selection(original, false)
        }

        suspend fun discardUnchangedPublication() {
            val file = published ?: return
            val token = publicationToken ?: return
            OwnedOutputSnapshot.deleteIfUnchanged(file, token)
        }

        override fun close() { directory.deleteRecursively() }
    }

    fun verifyPdf(file: File, pages: Int) {
        require(pages > 0 && file.isFile && file.length() > 0) { "تعذر التحقق من نسخة المشاركة" }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount == pages) { "نسخة المشاركة لا تحتوي جميع الصفحات المطلوبة" }
            }
        }
    }
}
