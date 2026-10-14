package com.abfilepro.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Tracks only the bytes this attempt wrote, never adopting a later user edit. */
internal class OwnedFileWrite private constructor(
    val file: File,
    private val channel: FileChannel,
    private val identity: String
) : Closeable {
    private val delegate = Channels.newOutputStream(channel)
    private val digest = MessageDigest.getInstance("SHA-256")
    private var bytes = 0L
    private var closed = false
    private var hash: String? = null
    private var completedToken: String? = null

    val stream: OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            delegate.write(value)
            digest.update(value.toByte())
            bytes++
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            digest.update(buffer, offset, length)
            bytes += length
        }
        override fun flush() = delegate.flush()
        override fun close() = this@OwnedFileWrite.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        try { channel.force(true) } finally { channel.close() }
    }

    private suspend fun writtenProof(): OwnedOutputSnapshot.Snapshot {
        val expected = hash ?: digest.digest().joinToString("") { "%02x".format(it) }.also { hash = it }
        val snapshot = OwnedOutputSnapshot.capture(file)
        val node = snapshot.nodes.single()
        require(!node.directory && identity.isNotBlank() && node.identity == identity &&
            node.signature.substringAfterLast(':') == expected && Files.size(file.toPath()) == bytes) {
            "تغير ناتج الكتابة؛ تم الاحتفاظ به دون حذفه"
        }
        return snapshot
    }

    suspend fun seal(): OwnedOutputSnapshot.Snapshot {
        close()
        return writtenProof().also { completedToken = it.token }
    }

    suspend fun rollback(): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        try {
            close()
            val token = completedToken ?: writtenProof().token
            OwnedOutputSnapshot.deleteIfUnchanged(file, token).complete
        } catch (_: Exception) { false }
    }

    companion object {
        fun create(file: File): OwnedFileWrite {
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            try {
                val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                require(attributes.isRegularFile && !attributes.isSymbolicLink) { "ناتج الكتابة غير صالح" }
                return OwnedFileWrite(file, channel, attributes.fileKey()?.toString().orEmpty())
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }
    }
}
