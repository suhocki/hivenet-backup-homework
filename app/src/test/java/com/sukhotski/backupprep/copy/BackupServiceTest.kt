package com.sukhotski.backupprep.copy

import com.sukhotski.backupprep.data.BackupStore
import com.sukhotski.backupprep.data.BatchEntity
import com.sukhotski.backupprep.data.BatchStatus
import com.sukhotski.backupprep.data.FailureReason
import com.sukhotski.backupprep.data.ItemEntity
import com.sukhotski.backupprep.data.ItemStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BackupServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val store = InMemoryBackupStore()
    private lateinit var destination: StagingDestination

    @org.junit.Before
    fun setUp() {
        destination = StagingDestination(tmp.newFolder("staging"))
    }

    private fun kotlinx.coroutines.test.TestScope.service(
        docs: Map<String, FakeDocument>,
        store: BackupStore = this@BackupServiceTest.store,
    ): BackupService {
        val source = FakeDocumentSource(docs)
        val dispatcher = StandardTestDispatcher(testScheduler)
        return BackupService(store, source, FileCopier(source, destination), destination, backgroundScope, dispatcher)
    }

    @Test
    fun `one failing file does not stop the rest of the batch`() = runTest {
        val service = service(
            mapOf(
                "u1" to FakeDocument("one".toByteArray(), name = "one.txt"),
                "u2" to FakeDocument("two".toByteArray(), name = "two.txt", openError = IOException("source gone")),
                "u3" to FakeDocument("three".toByteArray(), name = "three.txt"),
            ),
        )

        service.start(listOf("u1", "u2", "u3")).join()

        val batch = store.batches.single()
        assertEquals(BatchStatus.FINISHED, batch.batch.status)
        assertEquals(
            listOf(ItemStatus.DONE, ItemStatus.FAILED, ItemStatus.DONE),
            batch.items.map { it.status },
        )
        assertEquals(FailureReason.SOURCE_UNREADABLE, batch.items[1].failureReason)
        assertEquals(5L, batch.items[2].copiedBytes)
        assertEquals("three", File(destination.batchDir(batch.batch.id), "three.txt").readText())
    }

    @Test
    fun `same uri picked twice is copied once and equal names get a suffix`() = runTest {
        val service = service(
            mapOf(
                "folderA/photo" to FakeDocument("A".toByteArray(), name = "photo.jpg"),
                "folderB/photo" to FakeDocument("B".toByteArray(), name = "photo.jpg"),
            ),
        )

        service.start(listOf("folderA/photo", "folderA/photo", "folderB/photo")).join()

        val batch = store.batches.single()
        assertEquals(listOf("photo.jpg", "photo (1).jpg"), batch.items.map { it.name })
        assertTrue(batch.items.all { it.status == ItemStatus.DONE })
        val dir = destination.batchDir(batch.batch.id)
        assertEquals("A", File(dir, "photo.jpg").readText())
        assertEquals("B", File(dir, "photo (1).jpg").readText())
    }

    @Test
    fun `unsafe display names are sanitized and missing names get a fallback`() = runTest {
        val service = service(
            mapOf(
                "u1" to FakeDocument("x".toByteArray(), name = "../../etc/passwd"),
                "u2" to FakeDocument("y".toByteArray(), name = null),
            ),
        )

        service.start(listOf("u1", "u2")).join()

        assertEquals(listOf(".._.._etc_passwd", "file"), store.batches.single().items.map { it.name })
    }

    @Test
    fun `a file named foo part does not share its temp file with a file named foo`() = runTest {
        val service = service(
            mapOf(
                "u1" to FakeDocument("A".toByteArray(), name = "foo.part"),
                "u2" to FakeDocument("B".toByteArray(), name = "foo"),
            ),
        )

        service.start(listOf("u1", "u2")).join()

        val batch = store.batches.single()
        assertEquals(listOf("foo.part_", "foo"), batch.items.map { it.name })
        assertTrue(batch.items.all { it.status == ItemStatus.DONE })
        val dir = destination.batchDir(batch.batch.id)
        assertEquals("A", File(dir, "foo.part_").readText())
        assertEquals("B", File(dir, "foo").readText())
        assertEquals(listOf("foo", "foo.part_"), dir.list()!!.sorted())
    }

    @Test
    fun `a store failure mid-batch does not crash and leaves no file without a DONE row`() = runTest {
        val service = service(
            mapOf(
                "u1" to FakeDocument("one".toByteArray(), name = "one.txt"),
                "u2" to FakeDocument("two".toByteArray(), name = "two.txt"),
            ),
            // Call 1 marks item 1 COPYING, call 2 would mark it DONE after the file was committed.
            store = FailingOnceStore(store, failingCall = 2),
        )

        service.start(listOf("u1", "u2")).join()

        val batch = store.batches.single()
        assertEquals(BatchStatus.INTERRUPTED, batch.batch.status)
        assertEquals(listOf(ItemStatus.INTERRUPTED, ItemStatus.INTERRUPTED), batch.items.map { it.status })
        assertEquals(emptyList<String>(), destination.batchDir(batch.batch.id).list()!!.toList())
    }

    @Test
    fun `two start calls run one batch after the other`() = runTest {
        val service = service(
            mapOf(
                "u1" to FakeDocument("A".toByteArray(), name = "a.txt"),
                "u2" to FakeDocument("B".toByteArray(), name = "b.txt"),
            ),
        )

        val first = service.start(listOf("u1"))
        val second = service.start(listOf("u2"))
        first.join()
        second.join()

        // InMemoryBackupStore.createBatch fails the test if a batch starts while another is RUNNING.
        assertEquals(listOf("a.txt", "b.txt"), store.batches.map { it.items.single().name })
        assertTrue(store.batches.all { it.batch.status == BatchStatus.FINISHED })
    }

    @Test
    fun `startup marks unfinished work as interrupted and deletes part files`() = runTest {
        val batchId = 42L
        store.seed(
            BatchEntity(id = batchId, createdAt = 0, status = BatchStatus.RUNNING),
            listOf(
                ItemEntity(id = 1, batchId = batchId, sourceUri = "a", name = "a", expectedSize = 1, status = ItemStatus.DONE),
                ItemEntity(id = 2, batchId = batchId, sourceUri = "b", name = "b", expectedSize = 1, status = ItemStatus.COPYING),
                ItemEntity(id = 3, batchId = batchId, sourceUri = "c", name = "c", expectedSize = 1, status = ItemStatus.PENDING),
            ),
        )
        val dir = destination.batchDir(batchId).apply { mkdirs() }
        File(dir, "a").writeText("done")
        File(dir, "b.part").writeText("half")

        service(emptyMap()).reconcile()

        val batch = store.batches.single()
        assertEquals(BatchStatus.INTERRUPTED, batch.batch.status)
        assertEquals(listOf(ItemStatus.DONE, ItemStatus.INTERRUPTED, ItemStatus.INTERRUPTED), batch.items.map { it.status })
        assertTrue(File(dir, "a").exists())
        assertFalse(File(dir, "b.part").exists())
    }

    @Test
    fun `startup deletes the final file of an item that was committed but never recorded as done`() = runTest {
        val batchId = 43L
        store.seed(
            BatchEntity(id = batchId, createdAt = 0, status = BatchStatus.RUNNING),
            listOf(
                ItemEntity(id = 1, batchId = batchId, sourceUri = "a", name = "a", expectedSize = 1, status = ItemStatus.DONE),
                ItemEntity(id = 2, batchId = batchId, sourceUri = "b", name = "b", expectedSize = 1, status = ItemStatus.COPYING),
            ),
        )
        val dir = destination.batchDir(batchId).apply { mkdirs() }
        File(dir, "a").writeText("done")
        File(dir, "b").writeText("renamed, but the process died before the DONE update")

        service(emptyMap()).reconcile()

        assertEquals(ItemStatus.INTERRUPTED, store.items(batchId)[1].status)
        assertEquals(listOf("a"), dir.list()!!.toList())
    }

    @Test
    fun `finished batches are left alone on startup`() = runTest {
        store.seed(
            BatchEntity(id = 1, createdAt = 0, status = BatchStatus.FINISHED),
            listOf(ItemEntity(id = 1, batchId = 1, sourceUri = "a", name = "a", expectedSize = 1, status = ItemStatus.FAILED)),
        )

        service(emptyMap()).reconcile()

        assertEquals(BatchStatus.FINISHED, store.batches.single().batch.status)
        assertEquals(ItemStatus.FAILED, store.batches.single().items.single().status)
    }
}
