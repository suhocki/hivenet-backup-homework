package com.sukhotski.backupprep.copy

import com.sukhotski.backupprep.data.FailureReason
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

sealed interface CopyResult {
    data class Done(val bytes: Long) : CopyResult
    data class Failed(val reason: FailureReason) : CopyResult
}

/** Streams one document into the destination and maps every failure to a [FailureReason]. */
class FileCopier(
    private val source: DocumentSource,
    private val destination: BackupDestination,
    private val bufferSize: Int = 256 * 1024,
) {

    suspend fun copy(batchId: Long, uri: String, fileName: String, info: SourceInfo): CopyResult {
        if (info.isVirtual) return CopyResult.Failed(FailureReason.VIRTUAL_DOCUMENT)

        val input = try {
            source.open(uri)
        } catch (e: Exception) {
            // FileNotFoundException, SecurityException (grant revoked), IllegalArgumentException (bad uri)
            return CopyResult.Failed(FailureReason.SOURCE_UNREADABLE)
        }

        input.use {
            val target = try {
                destination.open(batchId, fileName)
            } catch (e: IOException) {
                return CopyResult.Failed(classifyWriteFailure(e))
            }
            try {
                val copied = pump(input, target.output)
                if (info.size != null && copied != info.size) {
                    target.abort()
                    return CopyResult.Failed(FailureReason.SIZE_MISMATCH)
                }
                target.commit()
                return CopyResult.Done(copied)
            } catch (e: SourceReadException) {
                target.abort()
                return CopyResult.Failed(FailureReason.SOURCE_UNREADABLE)
            } catch (e: IOException) {
                target.abort()
                return CopyResult.Failed(classifyWriteFailure(e))
            } catch (e: Throwable) {
                target.abort()
                throw e
            }
        }
    }

    /** Copies in chunks so memory stays flat for large files. Checks for cancellation between chunks. */
    private suspend fun pump(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = try {
                input.read(buffer)
            } catch (e: IOException) {
                throw SourceReadException(e)
            }
            if (read < 0) return total
            output.write(buffer, 0, read)
            total += read
        }
    }

    private fun classifyWriteFailure(e: IOException): FailureReason {
        val message = e.message.orEmpty()
        val noSpace = message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true)
        return if (noSpace) FailureReason.NO_SPACE else FailureReason.WRITE_FAILED
    }

    private class SourceReadException(cause: IOException) : Exception(cause)
}
