package com.abfilepro.app.core

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.Comparator
import java.util.UUID

/** Tests the app's real storage methods on actual Android files. Failure fixtures
 * override only rename/delete/listing outcomes, never copying or stored bytes. */
@RunWith(AndroidJUnit4::class)
class StorageSafetyRegressionTest {
    private lateinit var context: Context
    private lateinit var directRoot: File
    private lateinit var workspaceRoot: File

    @Before fun createFixtures() {
        context = ApplicationProvider.getApplicationContext()
        directRoot = File(context.getExternalFilesDir(null), "p12_test_${UUID.randomUUID()}")
        assertTrue(directRoot.mkdirs())
        workspaceRoot = File(FileUtils.rootDir(context), "P12_TEST_${UUID.randomUUID()}")
        assertTrue(workspaceRoot.mkdirs())
    }

    @After fun removeOnlyFixtures() {
        for (root in listOf(directRoot, workspaceRoot)) {
            if (root.exists()) Files.walk(root.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun folder(name: String): File = File(directRoot, name).apply { check(mkdirs()) }
    private fun file(parent: File, name: String, value: String): File = File(parent, name).apply { writeText(value) }
    private fun output(name: String, value: String): File = file(workspaceRoot, name, value)

    private class ControlledFile(
        original: File,
        private val children: (() -> Array<File>?)? = null,
        private val cannotRename: Boolean = false,
        private val cannotDelete: Boolean = false,
        private val onLastModified: (() -> Unit)? = null,
        private val afterDelete: (() -> Unit)? = null
    ) : File(original.path) {
        override fun listFiles(): Array<File>? = children?.invoke() ?: if (children == null) super.listFiles() else null
        override fun renameTo(dest: File): Boolean = if (cannotRename) false else super.renameTo(dest)
        override fun delete(): Boolean {
            val deleted = if (cannotDelete) false else super.delete()
            if (deleted) afterDelete?.invoke()
            return deleted
        }
        override fun lastModified(): Long { onLastModified?.invoke(); return super.lastModified() }
    }

    @Test fun copyIntoSelfRejectedWithoutChangingSource() = runBlocking {
        val source = folder("source")
        val original = file(source, "keep.txt", "original")
        val result = runCatching { withTimeout(1200) { DeviceStorageManager.copy(DeviceStorageManager.Entry(source), source) } }
        assertTrue("Self-copy must be rejected before writing", result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("original", original.readText())
        assertEquals(listOf("keep.txt"), source.listFiles()!!.map { it.name }.sorted())
    }

    @Test fun copyIntoDescendantRejectedWithoutWriting() = runBlocking {
        val source = folder("source")
        val destination = File(source, "child").apply { mkdirs() }
        file(source, "keep.txt", "original")
        val result = runCatching { withTimeout(1200) { DeviceStorageManager.copy(DeviceStorageManager.Entry(source), destination) } }
        assertTrue("Descendant-copy must be rejected", result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(destination.listFiles()!!.isEmpty())
    }

    @Test fun copyReadFailureCleansOnlyAttemptAndPreservesExistingDestination() = runBlocking {
        val source = folder("source")
        val first = file(source, "first.txt", "first bytes")
        val second = File(source, "missing.txt")
        val controlled = ControlledFile(source, children = { arrayOf(first, second) })
        val destination = folder("destination")
        val existing = File(destination, "source").apply { mkdirs() }
        val existingFile = file(existing, "old.txt", "existing")
        assertTrue(runCatching { DeviceStorageManager.copy(DeviceStorageManager.Entry(controlled), destination) }.isFailure)
        assertEquals("first bytes", first.readText())
        assertEquals("existing", existingFile.readText())
        assertEquals(listOf("source"), destination.listFiles()!!.map { it.name }.sorted())
    }

    @Test fun copyCancellationCleansAttemptAndKeepsSource() = runBlocking {
        val source = folder("source")
        val seed = file(source, "keep.txt", "original")
        val destination = folder("destination")
        val operation = Job()
        val controlled = ControlledFile(source, children = { operation.cancel(); arrayOf(seed) })
        val result = runCatching { withContext(Dispatchers.IO + operation) { DeviceStorageManager.copy(DeviceStorageManager.Entry(controlled), destination) } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals("original", seed.readText())
        assertTrue("Cancelled copy left an output", destination.listFiles()!!.isEmpty())
    }

    @Test fun unreadableDirectoryCannotBePublishedAsEmptyCopy() = runBlocking {
        val source = folder("source")
        val seed = file(source, "keep.txt", "original")
        val destination = folder("destination")
        val controlled = ControlledFile(source, children = { null })
        assertTrue("A failed directory listing must fail the copy", runCatching { DeviceStorageManager.copy(DeviceStorageManager.Entry(controlled), destination) }.isFailure)
        assertEquals("original", seed.readText())
        assertTrue(destination.listFiles()!!.isEmpty())
    }

    @Test fun fallbackMoveDeleteFailureKeepsCompleteDestination() = runBlocking {
        val source = folder("source")
        val first = file(source, "first.txt", "FIRST")
        val second = file(source, "second.txt", "SECOND")
        val blocked = ControlledFile(second, cannotDelete = true)
        val controlled = ControlledFile(source, children = { arrayOf(first, blocked) }, cannotRename = true)
        val destination = folder("destination")
        assertTrue(runCatching { DeviceStorageManager.move(DeviceStorageManager.Entry(controlled), destination) }.isFailure)
        val saved = File(destination, "source")
        assertTrue("The only complete copy was deleted", saved.isDirectory)
        assertEquals("FIRST", File(saved, "first.txt").readText())
        assertEquals("SECOND", File(saved, "second.txt").readText())
        assertEquals("SECOND", second.readText())
    }

    @Test fun moveCancellationBeforeSourceCleanupPreservesSource() = runBlocking {
        val source = file(directRoot, "source.txt", "SOURCE")
        val destination = folder("destination")
        val operation = Job()
        val controlled = ControlledFile(source, cannotRename = true, onLastModified = { operation.cancel() })
        val result = runCatching { withContext(Dispatchers.IO + operation) { DeviceStorageManager.move(DeviceStorageManager.Entry(controlled), destination) } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue("Cancellation still deleted the source", source.exists())
        assertEquals("SOURCE", source.readText())
    }

    @Test fun normalCopyPreservesBytesAndSource() = runBlocking {
        val source = file(directRoot, "source.txt", "source-data")
        val destination = folder("destination")
        val saved = DeviceStorageManager.copy(DeviceStorageManager.Entry(source), destination)
        assertEquals("source-data", source.readText())
        assertEquals("source-data", saved.file.readText())
        assertEquals(listOf("source.txt"), destination.listFiles()!!.map { it.name })
    }

    @Test fun normalMovePreservesBytesAtDestination() = runBlocking {
        val source = file(directRoot, "source.txt", "source-data")
        val destination = folder("destination")
        val saved = DeviceStorageManager.move(DeviceStorageManager.Entry(source), destination)
        assertFalse(source.exists())
        assertEquals("source-data", saved.file.readText())
    }

    @Test fun undoChangedSameLengthSameTimeFilePreservesNewData() = runBlocking {
        val saved = output("edited.txt", "ORIGINAL")
        val entry = OperationHistoryStore.record(context, "test", "test", listOf(saved))
        val time = saved.lastModified()
        saved.writeText("MODIFIED")
        saved.setLastModified(time)
        assertFalse(OperationHistoryStore.undo(context, entry.id))
        assertEquals("MODIFIED", saved.readText())
    }

    @Test fun undoReplacementAtSamePathPreservesReplacement() = runBlocking {
        val saved = output("replaced.txt", "original")
        val entry = OperationHistoryStore.record(context, "test", "test", listOf(saved))
        assertTrue(saved.delete())
        saved.writeText("new user file")
        assertFalse(OperationHistoryStore.undo(context, entry.id))
        assertEquals("new user file", saved.readText())
    }

    @Test fun undoFolderWithNewUserFilePreservesWholeFolder() = runBlocking {
        val directory = File(workspaceRoot, "generated").apply { mkdirs() }
        val original = file(directory, "generated.txt", "generated")
        val entry = OperationHistoryStore.record(context, "test", "test", listOf(directory))
        val added = file(directory, "user.txt", "user-added")
        assertFalse(OperationHistoryStore.undo(context, entry.id))
        assertEquals("generated", original.readText())
        assertEquals("user-added", added.readText())
    }

    @Test fun unchangedGeneratedOutputCanStillBeUndone() = runBlocking {
        val saved = output("unchanged.txt", "generated")
        val entry = OperationHistoryStore.record(context, "test", "test", listOf(saved))
        assertTrue(entry.canUndo(context))
        assertTrue(OperationHistoryStore.undo(context, entry.id))
        assertFalse(saved.exists())
    }

    @Test fun partialUndoKeepsChangedOutputAndItsHistory() = runBlocking {
        val unchanged = output("unchanged.txt", "generated")
        val changed = output("changed.txt", "original")
        val entry = OperationHistoryStore.record(context, "test", "test", listOf(unchanged, changed))
        changed.writeText("user-edited")
        assertTrue(OperationHistoryStore.undo(context, entry.id))
        assertFalse(unchanged.exists())
        assertTrue("Changed output was deleted", changed.exists())
        assertEquals("user-edited", changed.readText())
        assertTrue("Unresolved output history was discarded", OperationHistoryStore.list(context, 100).any { it.id == entry.id && changed.canonicalPath in it.outputs })
    }

    @Test fun legacyHistoryWithoutOwnershipIsKeptWithoutDeletingFile() = runBlocking {
        val saved = output("legacy.txt", "user data")
        fun encode(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
        File(context.filesDir, "ab_smart_history.tsv").writeText(listOf(encode("legacy"), encode("test"), encode("legacy"), encode(saved.canonicalPath), "1").joinToString("\t") + "\n")
        assertFalse(OperationHistoryStore.undo(context, "legacy"))
        assertEquals("user data", saved.readText())
        assertTrue(OperationHistoryStore.list(context, 100).any { it.id == "legacy" })
    }

    @Test fun partialFileMoveUndoRetainsConflictForRetry() {
        val oldA = File(workspaceRoot, "old-a.txt")
        val oldB = output("old-b.txt", "name conflict")
        val movedA = output("moved-a.txt", "A")
        val movedB = output("moved-b.txt", "B")
        FileUndoStore.recordMoves(context, linkedMapOf(oldA.absolutePath to movedA.absolutePath, oldB.absolutePath to movedB.absolutePath))
        assertEquals(1, FileUndoStore.undo(context).getOrThrow())
        assertEquals("A", oldA.readText())
        assertEquals("name conflict", oldB.readText())
        assertEquals("B", movedB.readText())
        assertTrue("Remaining undo was discarded", FileUndoStore.canUndo(context))
        assertTrue(oldB.delete())
        assertEquals(1, FileUndoStore.undo(context).getOrThrow())
        assertEquals("B", oldB.readText())
    }

    @Test fun normalFolderCopyPreservesNestedAndEmptyFolders() = runBlocking {
        val source = folder("source")
        val nested = File(source, "nested").apply { mkdirs() }
        File(source, "empty").mkdirs()
        val original = file(nested, "keep.txt", "nested bytes")
        val destination = folder("destination")
        val saved = DeviceStorageManager.copy(DeviceStorageManager.Entry(source), destination).file
        assertEquals("nested bytes", original.readText())
        assertEquals("nested bytes", File(saved, "nested/keep.txt").readText())
        assertTrue(File(saved, "empty").isDirectory)
        assertEquals(listOf("source"), destination.listFiles()!!.map { it.name })
    }

    @Test fun unchangedGeneratedFolderCanStillBeUndone() = runBlocking {
        val generated = File(workspaceRoot, "generated").apply { mkdirs() }
        val nested = File(generated, "nested").apply { mkdirs() }
        file(nested, "keep.txt", "generated bytes")
        File(generated, "empty").mkdirs()
        val entry = OperationHistoryStore.record(context, "test", "folder", listOf(generated))
        assertTrue(entry.canUndo(context))
        assertTrue(OperationHistoryStore.undo(context, entry.id))
        assertFalse(generated.exists())
        assertFalse(OperationHistoryStore.hasPending(context, entry.id))
    }

    @Test fun failedFallbackMoveCopyDoesNotDeleteAnySource() = runBlocking {
        val source = folder("source")
        val original = file(source, "keep.txt", "original")
        val controlled = ControlledFile(source, cannotRename = true, children = { arrayOf(original, File(source, "missing.txt")) })
        val destination = folder("destination")
        assertTrue(runCatching { DeviceStorageManager.move(DeviceStorageManager.Entry(controlled), destination) }.isFailure)
        assertEquals("original", original.readText())
        assertTrue(destination.listFiles()!!.isEmpty())
    }

    @Test fun cancelledSourceCleanupKeepsCompletedCopyAndRemainingSource() = runBlocking {
        val source = folder("source")
        val first = file(source, "first.txt", "FIRST")
        val second = file(source, "second.txt", "SECOND")
        val operation = Job()
        val cancelAfterFirst = ControlledFile(first, afterDelete = { operation.cancel() })
        val controlled = ControlledFile(source, cannotRename = true, children = { arrayOf(cancelAfterFirst, second) })
        val destination = folder("destination")
        val result = runCatching { withContext(Dispatchers.IO + operation) { DeviceStorageManager.move(DeviceStorageManager.Entry(controlled), destination) } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals("SECOND", second.readText())
        val saved = File(destination, "source")
        assertEquals("FIRST", File(saved, "first.txt").readText())
        assertEquals("SECOND", File(saved, "second.txt").readText())
    }
}
