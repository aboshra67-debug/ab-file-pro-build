package com.abfilepro.app.core

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** Test-only provider. All documents are disposable fixtures in the test app's cache. */
class MaintenanceDocumentsProvider : DocumentsProvider() {
    private lateinit var root: File
    private var fault = ""
    private var faultFired = false

    override fun onCreate(): Boolean {
        root = File(requireNotNull(context).cacheDir, "maintenance_documents")
        root.mkdirs()
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "reset_fixtures") return super.call(method, arg, extras)
        root.deleteRecursively()
        check(root.mkdirs())
        File(root, "source").mkdirs()
        File(root, "source/seed.txt").writeText("ORIGINAL_CHILD")
        File(root, "input.txt").writeText("ORIGINAL_INPUT")
        File(root, "destination").mkdirs()
        fault = arg.orEmpty()
        faultFired = false
        return Bundle().apply { putBoolean("ready", true) }
    }

    private fun file(id: String): File = (if (id == "root") root else File(root, id)).also {
        val canonical = it.canonicalFile
        require(canonical == root.canonicalFile || canonical.path.startsWith(root.canonicalPath + File.separator))
    }

    private fun id(file: File): String = file.relativeTo(root).path.replace(File.separatorChar, '/')

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        return MatrixCursor(columns).apply {
            addRow(columns.map { when (it) {
                Root.COLUMN_ROOT_ID -> "maintenance"
                Root.COLUMN_DOCUMENT_ID -> "root"
                Root.COLUMN_TITLE -> "Maintenance fixtures"
                Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE
                else -> null
            } }.toTypedArray())
        }
    }

    private fun documentCursor(projection: Array<out String>?): MatrixCursor = MatrixCursor(projection ?: arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS
    ))

    private fun row(cursor: MatrixCursor, documentId: String) {
        val document = file(documentId)
        if (!document.exists()) return
        cursor.addRow(cursor.columnNames.map { when (it) {
            Document.COLUMN_DOCUMENT_ID -> documentId
            Document.COLUMN_DISPLAY_NAME -> document.name
            Document.COLUMN_MIME_TYPE -> if (document.isDirectory) Document.MIME_TYPE_DIR else "text/plain"
            Document.COLUMN_SIZE -> document.length()
            Document.COLUMN_LAST_MODIFIED -> document.lastModified()
            Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_DELETE or
                (if (document.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE)
            else -> null
        } }.toTypedArray())
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val queried = file(documentId)
        if (!faultFired && documentId.startsWith("destination/") && queried.isFile && queried.length() > 0) {
            when (fault) {
                "mutate_input_after_copy" -> { faultFired = true; File(root, "input.txt").writeText("UPDATED_INPUT") }
                "add_child_after_copy" -> { faultFired = true; File(root, "source/late.txt").writeText("LATE_CHILD") }
                "corrupt_destination" -> { faultFired = true; queried.writeText("BROKEN") }
            }
        }
        return documentCursor(projection).apply { row(this, documentId) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor? {
        if (fault == "null_source_listing" && parentDocumentId == "source") return null
        return documentCursor(projection).apply {
            for (child in file(parentDocumentId).listFiles().orEmpty().sortedBy { it.name }) row(this, id(child))
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = file(parentDocumentId).canonicalFile
        val child = file(documentId).canonicalFile
        return child.path.startsWith(parent.path + File.separator)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(!displayName.contains('/') && !displayName.contains('\\'))
        val child = File(file(parentDocumentId), displayName)
        check(!child.exists())
        check(if (mimeType == Document.MIME_TYPE_DIR) child.mkdir() else child.createNewFile())
        return id(child)
    }

    override fun deleteDocument(documentId: String) {
        check(file(documentId).deleteRecursively())
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
}
