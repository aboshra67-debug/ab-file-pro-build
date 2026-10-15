package com.abfilepro.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Content and identity proof for generated outputs. Never follows links. */
internal object OwnedOutputSnapshot {
    data class Node(val relative: String, val signature: String, val directory: Boolean, val identity: String, val file: File)
    data class Snapshot(val token: String, val nodes: List<Node>)
    data class DeleteResult(val changed: Boolean, val complete: Boolean)

    suspend fun capture(root: File): Snapshot {
        val nodes = ArrayList<Node>()
        suspend fun visit(file: File, relative: String) {
            currentCoroutineContext().ensureActive()
            val node = readNode(file, relative)
            nodes += node
            if (node.directory) {
                val children = checkNotNull(file.listFiles()) { "تعذر قراءة ناتج العملية" }
                for (child in children.sortedBy { it.name }) {
                    visit(child, if (relative.isEmpty()) child.name else "$relative/${child.name}")
                }
                check(readNode(file, relative).signature == node.signature) { "تغير ناتج العملية أثناء التحقق" }
            }
        }
        visit(root, "")
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        DataOutputStream(DigestOutputStream(sink, digest)).use { out ->
            for (node in nodes) {
                val path = node.relative.toByteArray(Charsets.UTF_8)
                val signature = node.signature.toByteArray(Charsets.UTF_8)
                out.writeInt(path.size); out.write(path)
                out.writeInt(signature.size); out.write(signature)
            }
        }
        return Snapshot("v1:" + hex(digest.digest()), nodes)
    }

    suspend fun deleteIfUnchanged(root: File, expected: String): DeleteResult {
        var changed = false
        try {
            val snapshot = capture(root)
            if (snapshot.token != expected) return DeleteResult(false, false)
            // Children precede parents; stable sibling order also makes partial
            // failures deterministic without re-enumerating new source files.
            for (node in snapshot.nodes.sortedByDescending { if (it.relative.isEmpty()) 0 else it.relative.count { character -> character == '/' } + 1 }) {
                currentCoroutineContext().ensureActive()
                val file = node.file
                val current = readNode(file, node.relative)
                if (node.directory) {
                    // Own removals change directory timestamps. The OS refuses
                    // deletion of a nonempty directory, preserving late additions.
                    if (!current.directory || current.identity != node.identity) return DeleteResult(changed, false)
                } else if (current.signature != node.signature) return DeleteResult(changed, false)
                currentCoroutineContext().ensureActive()
                if (!file.delete()) return DeleteResult(changed, false)
                changed = true
            }
            return DeleteResult(changed, true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DeleteResult(changed, false)
        }
    }

    private suspend fun readNode(file: File, relative: String): Node {
        val path = file.toPath().toAbsolutePath().normalize()
        check(file.canonicalFile.toPath() == path) { "مسار مرتبط غير قابل للتراجع" }
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(!before.isSymbolicLink && (before.isDirectory || before.isRegularFile)) { "نوع ناتج غير قابل للتراجع" }
        val content = if (before.isRegularFile) {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            hex(digest.digest())
        } else "directory"
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(metadata(before) == metadata(after)) { "تغير ناتج العملية أثناء التحقق" }
        return Node(relative, metadata(before) + ":" + content, before.isDirectory, before.fileKey()?.toString().orEmpty(), file)
    }

    private fun metadata(attributes: BasicFileAttributes): String =
        "${attributes.fileKey()}:${attributes.isDirectory}:${attributes.isRegularFile}:${attributes.isSymbolicLink}:${attributes.size()}:${attributes.lastModifiedTime().toMillis()}"

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 255
                append(digits[value ushr 4]); append(digits[value and 15])
            }
        }
    }
}
