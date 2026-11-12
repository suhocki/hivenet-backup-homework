package com.sukhotski.backupprep.copy

import com.sukhotski.backupprep.data.FailureReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.random.Random

class FileCopierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val batchId = 7L
    private lateinit var destination: StagingDestination

    private fun copier(doc: FakeDocument, destination: BackupDestination = this.destination): Pair<FileCopier, SourceInfo> {
        val source = FakeDocumentSource(mapOf("uri" to doc))
        val copier = FileCopier(source, destination, bufferSize = 1024, isNoSpace = { it.message == "ENOSPC" })
        return copier to source.describe("uri")
    }

    private fun batchFiles(): List<String> = destination.batchDir(batchId).listFiles()?.map { it.name }?.sorted().orEmpty()

    @org.junit.Before
    fun setUp() {
        destination = StagingDestination(tmp.newFolder("staging"))
    }

    @Test
    fun `successful copy writes identical bytes and leaves no part file`() = runTest {
        val bytes = Random(1).nextBytes(10_000) // several buffers
        val (copier, info) = copier(FakeDocument(bytes))

        val result = copier.copy(batchId, "uri", "data.bin", info)

        assertEquals(CopyResult.Done(10_000), result)
        assertArrayEquals(bytes, File(destination.batchDir(batchId), "data.bin").readBytes())
        assertEquals(listOf("data.bin"), batchFiles())
    }

    @Test
    fun `zero byte file is a success`() = runTest {
        val (copier, info) = copier(FakeDocument(ByteArray(0), name = "empty.txt"))

        val result = copier.copy(batchId, "uri", "empty.txt", info)

        assertEquals(CopyResult.Done(0), result)
        assertEquals(0L, File(destination.batchDir(batchId), "empty.txt").length())
    }

    @Test
    fun `unknown size skips the size check`() = runTest {
        val (copier, info) = copier(FakeDocument(ByteArray(5), reportedSize = null))

        assertEquals(CopyResult.Done(5), copier.copy(batchId, "uri", "x", info))
    }

    @Test
    fun `size mismatch fails and removes the partial file`() = runTest {
        val (copier, info) = copier(FakeDocument(ByteArray(5), reportedSize = 10))

        val result = copier.copy(batchId, "uri", "x.bin", info)

        assertEquals(CopyResult.Failed(FailureReason.SIZE_MISMATCH), result)
        assertTrue(batchFiles().isEmpty())
    }

    @Test
    fun `source that cannot be opened is reported as unreadable`() = runTest {
        val (copier, info) = copier(FakeDocument(ByteArray(5), openError = IOException("source gone")))

        assertEquals(CopyResult.Failed(FailureReason.SOURCE_UNREADABLE), copier.copy(batchId, "uri", "x", info))
        assertTrue(batchFiles().isEmpty())
    }

    @Test
    fun `revoked uri permission is reported as permission lost`() = runTest {
        val (copier, info) = copier(FakeDocument(ByteArray(5), openError = SecurityException("no grant")))

        assertEquals(CopyResult.Failed(FailureReason.PERMISSION_LOST), copier.copy(batchId, "uri", "x", info))
        assertTrue(batchFiles().isEmpty())
    }

    @Test
    fun `read error in the middle is reported as unreadable and cleaned up`() = runTest {
        val (copier, info) = copier(FakeDocument(ByteArray(5_000), failReadAfter = 2_000))

        assertEquals(CopyResult.Failed(FailureReason.SOURCE_UNREADABLE), copier.copy(batchId, "uri", "x", info))
        assertTrue(batchFiles().isEmpty())
    }

    @Test
    fun `virtual document fails without opening the source`() = runTest {
        val source = FakeDocumentSource(mapOf("uri" to FakeDocument(ByteArray(5), isVirtual = true)))
        val copier = FileCopier(source, destination)

        assertEquals(CopyResult.Failed(FailureReason.VIRTUAL_DOCUMENT), copier.copy(batchId, "uri", "doc", source.describe("uri")))
        assertTrue(source.opened.isEmpty())
    }

    @Test
    fun `write error with ENOSPC is reported as no space`() = runTest {
        val failing = FailingDestination(destination, IOException("ENOSPC"))
        val (copier, info) = copier(FakeDocument(ByteArray(5)), failing)

        assertEquals(CopyResult.Failed(FailureReason.NO_SPACE), copier.copy(batchId, "uri", "x", info))
        assertTrue(batchFiles().isEmpty())
    }

    @Test
    fun `other write errors are reported as write failed`() = runTest {
        val failing = FailingDestination(destination, IOException("EIO"))
        val (copier, info) = copier(FakeDocument(ByteArray(5)), failing)

        assertEquals(CopyResult.Failed(FailureReason.WRITE_FAILED), copier.copy(batchId, "uri", "x", info))
        assertTrue(batchFiles().isEmpty())
    }
}
