package com.sukhotski.backupprep.copy

import com.sukhotski.backupprep.data.BackupStore
import com.sukhotski.backupprep.data.BatchEntity
import com.sukhotski.backupprep.data.BatchStatus
import com.sukhotski.backupprep.data.BatchWithItems
import com.sukhotski.backupprep.data.FailureReason
import com.sukhotski.backupprep.data.ItemEntity
import com.sukhotski.backupprep.data.ItemStatus
import com.sukhotski.backupprep.data.NewItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class FakeDocument(
    val bytes: ByteArray,
    val name: String? = "file.bin",
    /** Size the provider reports. Defaults to the real size. */
    val reportedSize: Long? = bytes.size.toLong(),
    val isVirtual: Boolean = false,
    /** Thrown by open(). IOException for a deleted source, SecurityException for a revoked grant. */
    val openError: Exception? = null,
    /** Throw IOException after this many bytes were read. */
    val failReadAfter: Int? = null,
)

class FakeDocumentSource(private val docs: Map<String, FakeDocument>) : DocumentSource {
    val opened = mutableListOf<String>()

    override fun describe(uri: String): SourceInfo {
        val doc = docs[uri] ?: return SourceInfo(null, null, false)
        return SourceInfo(doc.name, doc.reportedSize, doc.isVirtual)
    }

    override fun open(uri: String): InputStream {
        opened += uri
        val doc = docs[uri] ?: throw IOException("unknown uri")
        doc.openError?.let { throw it }
        val stream = ByteArrayInputStream(doc.bytes)
        val limit = doc.failReadAfter ?: return stream
        return object : FilterInputStream(stream) {
            var consumed = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (consumed >= limit) throw IOException("read failed")
                val n = super.read(b, off, minOf(len, limit - consumed))
                if (n > 0) consumed += n
                return n
            }
        }
    }
}

/** Destination whose output stream fails with the given exception on the first write. */
class FailingDestination(private val delegate: BackupDestination, private val error: IOException) : BackupDestination {
    override fun open(batchId: Long, fileName: String): BackupDestination.Target {
        val target = delegate.open(batchId, fileName)
        return object : BackupDestination.Target by target {
            override val output: OutputStream = object : OutputStream() {
                override fun write(b: Int) = throw error
                override fun write(b: ByteArray, off: Int, len: Int) = throw error
            }
        }
    }
}

class InMemoryBackupStore : BackupStore {
    private val state = MutableStateFlow<List<BatchWithItems>>(emptyList())
    private var nextBatchId = 1L
    private var nextItemId = 1L

    val batches: List<BatchWithItems> get() = state.value
    fun items(batchId: Long): List<ItemEntity> = batches.first { it.batch.id == batchId }.items

    fun seed(batch: BatchEntity, items: List<ItemEntity>) {
        state.value = state.value + BatchWithItems(batch, items)
    }

    override fun observeLatestBatch(): Flow<BatchWithItems?> = state.map { it.lastOrNull() }

    override suspend fun createBatch(items: List<NewItem>): BatchWithItems {
        check(state.value.none { it.batch.status == BatchStatus.RUNNING }) { "a batch was created while another was running" }
        val batch = BatchEntity(id = nextBatchId++, createdAt = 0, status = BatchStatus.RUNNING)
        val entities = items.map {
            ItemEntity(id = nextItemId++, batchId = batch.id, sourceUri = it.sourceUri, name = it.name, expectedSize = it.expectedSize)
        }
        val created = BatchWithItems(batch, entities)
        state.value = state.value + created
        return created
    }

    override suspend fun updateItem(id: Long, status: ItemStatus, reason: FailureReason?, copiedBytes: Long) {
        yield() // Room suspends here too. Lets tests see two batches interleave.
        state.value = state.value.map { b ->
            b.copy(items = b.items.map { if (it.id == id) it.copy(status = status, failureReason = reason, copiedBytes = copiedBytes) else it })
        }
    }

    override suspend fun updateBatch(id: Long, status: BatchStatus) {
        state.value = state.value.map { if (it.batch.id == id) it.copy(batch = it.batch.copy(status = status)) else it }
    }

    override suspend fun interruptUnfinished(): List<ItemEntity> {
        val unfinished = state.value.flatMap { it.items }.filter { it.status == ItemStatus.PENDING || it.status == ItemStatus.COPYING }
        state.value = state.value.map { b ->
            val batch = if (b.batch.status == BatchStatus.RUNNING) b.batch.copy(status = BatchStatus.INTERRUPTED) else b.batch
            val items = b.items.map {
                if (it.status == ItemStatus.PENDING || it.status == ItemStatus.COPYING) it.copy(status = ItemStatus.INTERRUPTED) else it
            }
            BatchWithItems(batch, items)
        }
        return unfinished
    }
}

/** Delegates everything, but the n-th updateItem call throws, like SQLite on a full disk. */
class FailingOnceStore(private val delegate: BackupStore, private val failingCall: Int) : BackupStore by delegate {
    private var calls = 0

    override suspend fun updateItem(id: Long, status: ItemStatus, reason: FailureReason?, copiedBytes: Long) {
        if (++calls == failingCall) throw IllegalStateException("database or disk is full")
        delegate.updateItem(id, status, reason, copiedBytes)
    }
}
