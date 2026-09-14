package com.iris.irisshell

import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * DocumentsProvider that exposes Iris Shell's internal Ubuntu rootfs to the
 * Android Storage Access Framework (SAF) — e.g. the "Files" app's "Open by app"
 * picker, or ACTION_OPEN_DOCUMENT.
 *
 * Root: "Iris Shell" → `$context.filesDir/ubuntu/`
 * Document IDs are absolute file paths (consistent with IrisCode's
 * IrisDocumentsProvider pattern).
 *
 * Inspired by: ~/projects/IrisCode/app/.../documents/IrisDocumentsProvider.kt
 * Adapted for Iris Shell — com.iris.irisshell
 */
class IrisShellDocumentsProvider : DocumentsProvider() {

    private val ALL_MIME_TYPES = "*/*"

    private val baseDir: File
        get() = File(context!!.filesDir, "ubuntu").canonicalFile

    private val defaultRootProjection = arrayOf(
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_FLAGS,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_AVAILABLE_BYTES,
    )

    private val defaultDocumentProjection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE,
    )

    override fun onCreate(): Boolean = baseDir.exists()

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: defaultRootProjection)
        val row = result.newRow()
        val docId = getDocIdForFile(baseDir)
        row.add(Root.COLUMN_ROOT_ID, docId)
        row.add(Root.COLUMN_DOCUMENT_ID, docId)
        row.add(Root.COLUMN_SUMMARY, null)
        row.add(
            Root.COLUMN_FLAGS,
            Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD,
        )
        row.add(Root.COLUMN_TITLE, "Iris Shell")
        row.add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES)
        row.add(Root.COLUMN_AVAILABLE_BYTES, baseDir.freeSpace)
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: defaultDocumentProjection)
        includeFile(result, documentId, null)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val result = MatrixCursor(projection ?: defaultDocumentProjection)
        val parent = getFileForDocId(parentDocumentId)
        parent.listFiles()?.forEach { includeFile(result, null, it) }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = getFileForDocId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?,
    ): android.content.res.AssetFileDescriptor? {
        val file = getFileForDocId(documentId)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return android.content.res.AssetFileDescriptor(pfd, 0, file.length())
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = getFileForDocId(parentDocumentId)
        var newFile = File(parent, displayName)
        var noConflictId = 2
        while (newFile.exists()) {
            newFile = File(parent, "$displayName ($noConflictId)")
            noConflictId++
        }
        val succeeded = if (Document.MIME_TYPE_DIR == mimeType) {
            newFile.mkdir()
        } else {
            newFile.createNewFile()
        }
        if (!succeeded) throw FileNotFoundException("Failed to create $newFile")
        return newFile.absolutePath
    }

    override fun deleteDocument(documentId: String) {
        val file = getFileForDocId(documentId)
        if (!file.delete()) throw FileNotFoundException("Failed to delete $documentId")
    }

    override fun getDocumentType(documentId: String): String {
        val file = getFileForDocId(documentId)
        return getMimeType(file)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return documentId.startsWith(parentDocumentId)
    }

    // ─── Helpers ─────────────────────────────────────

    private fun getDocIdForFile(file: File): String = file.absolutePath

    private fun getFileForDocId(docId: String): File {
        val f = File(docId).canonicalFile
        if (!f.exists()) throw FileNotFoundException("${f.absolutePath} not found")
        if (!f.toPath().startsWith(baseDir.toPath())) {
            throw FileNotFoundException("Path outside ubuntu rootfs: $docId")
        }
        return f
    }

    private fun getMimeType(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val name = file.name
        val lastDot = name.lastIndexOf('.')
        if (lastDot >= 0) {
            val ext = name.substring(lastDot + 1).lowercase()
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return "application/octet-stream"
    }

    private fun includeFile(result: MatrixCursor, docId: String?, fileArg: File?) {
        val docIdResolved: String
        val fileResolved: File
        if (docId == null) {
            fileResolved = fileArg!!
            docIdResolved = getDocIdForFile(fileResolved)
        } else {
            docIdResolved = docId
            fileResolved = getFileForDocId(docId)
        }

        var flags = 0
        if (fileResolved.isDirectory) {
            if (fileResolved.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (fileResolved.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (fileResolved.parentFile?.canWrite() == true) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE
        }

        val mimeType = getMimeType(fileResolved)
        if (mimeType.startsWith("image/")) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL

        val row = result.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, docIdResolved)
        row.add(Document.COLUMN_DISPLAY_NAME, fileResolved.name)
        row.add(Document.COLUMN_SIZE, fileResolved.length())
        row.add(Document.COLUMN_MIME_TYPE, mimeType)
        row.add(Document.COLUMN_LAST_MODIFIED, fileResolved.lastModified())
        row.add(Document.COLUMN_FLAGS, flags)
    }
}
