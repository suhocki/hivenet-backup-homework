package com.sukhotski.backupprep.copy

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream

/** What the provider tells us about a picked document before we read it. */
data class SourceInfo(
    val displayName: String?,
    /** Null when the provider does not report a size. */
    val size: Long?,
    /** FLAG_VIRTUAL_DOCUMENT: the document has no byte stream (for example Google Docs). */
    val isVirtual: Boolean,
)

/** Read side of a copy. Uris are plain strings so the logic can be unit-tested on the JVM. */
interface DocumentSource {
    /** Never throws. Unknown fields are null/false. */
    fun describe(uri: String): SourceInfo

    /** @throws java.io.IOException or SecurityException when the source cannot be opened. */
    fun open(uri: String): InputStream

    /** Keeps read access beyond the picker result. Best effort: providers may refuse. */
    fun retain(uri: String) {}

    fun release(uri: String) {}
}

class ContentResolverDocumentSource(private val resolver: ContentResolver) : DocumentSource {

    override fun describe(uri: String): SourceInfo =
        try {
            describeOrThrow(uri)
        } catch (e: Exception) {
            // A broken provider cursor must fail one row later, not drop the whole selection.
            SourceInfo(null, null, false)
        }

    private fun describeOrThrow(uri: String): SourceInfo {
        val parsed = Uri.parse(uri)
        val cursor = query(parsed, WITH_FLAGS) ?: query(parsed, OPENABLE_ONLY) ?: return SourceInfo(null, null, false)
        cursor.use {
            if (!it.moveToFirst()) return SourceInfo(null, null, false)
            val flags = it.optInt(DocumentsContract.Document.COLUMN_FLAGS) ?: 0
            return SourceInfo(
                displayName = it.optString(OpenableColumns.DISPLAY_NAME),
                size = it.optLong(OpenableColumns.SIZE)?.takeIf { size -> size >= 0 },
                isVirtual = flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0,
            )
        }
    }

    override fun open(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException("Provider returned no stream")

    override fun retain(uri: String) {
        runCatching { resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    override fun release(uri: String) {
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun query(uri: Uri, projection: Array<String>): Cursor? =
        try {
            resolver.query(uri, projection, null, null, null)
        } catch (e: Exception) {
            // Providers may reject unknown columns or the grant may be gone. Both are handled by the caller.
            null
        }

    private fun Cursor.optString(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.optLong(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    private fun Cursor.optInt(column: String): Int? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getInt)

    private companion object {
        val OPENABLE_ONLY = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val WITH_FLAGS = OPENABLE_ONLY + DocumentsContract.Document.COLUMN_FLAGS
    }
}
