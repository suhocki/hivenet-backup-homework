package com.sukhotski.backupprep.copy

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Write side of a copy. This is the seam for the later pipeline steps:
 * an encrypting, chunking or uploading destination implements the same two-phase contract.
 */
interface BackupDestination {
    /** @throws IOException when the target cannot be created. */
    fun open(batchId: Long, fileName: String): Target

    interface Target {
        val output: OutputStream

        /** All bytes were written and verified. Make them final. */
        fun commit()

        /** Something went wrong. Remove everything written so far. Must not throw. */
        fun abort()
    }
}

/** Writes "name.part" into filesDir/staging/<batchId>/ and renames it to "name" on commit. */
class StagingDestination(private val root: File) : BackupDestination {

    fun batchDir(batchId: Long): File = File(root, batchId.toString())

    override fun open(batchId: Long, fileName: String): BackupDestination.Target {
        val dir = batchDir(batchId)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create batch folder")
        val part = File(dir, fileName + PART_SUFFIX)
        val final = File(dir, fileName)
        val stream = FileOutputStream(part)
        return object : BackupDestination.Target {
            override val output: OutputStream = stream

            override fun commit() {
                stream.flush()
                stream.fd.sync()
                stream.close()
                if (!part.renameTo(final)) throw IOException("Rename to final name failed")
            }

            override fun abort() {
                runCatching { stream.close() }
                part.delete()
            }
        }
    }

    /** Removes what an interrupted item left behind: its partial file and, if it got that far, the final file. */
    fun discard(batchId: Long, fileName: String) {
        File(batchDir(batchId), fileName + PART_SUFFIX).delete()
        File(batchDir(batchId), fileName).delete()
    }

    companion object {
        const val PART_SUFFIX = ".part"
    }
}
