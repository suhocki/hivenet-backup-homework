package com.sukhotski.backupprep.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

data class NewItem(val sourceUri: String, val name: String, val expectedSize: Long?)

/** Persistence used by the copy logic. An interface so the logic is unit-tested without Room. */
interface BackupStore {
    fun observeLatestBatch(): Flow<BatchWithItems?>

    /** Creates a RUNNING batch with all items PENDING and returns it with database ids. */
    suspend fun createBatch(items: List<NewItem>): BatchWithItems

    suspend fun updateItem(id: Long, status: ItemStatus, reason: FailureReason? = null, copiedBytes: Long = 0)

    suspend fun updateBatch(id: Long, status: BatchStatus)

    /**
     * Marks RUNNING batches and PENDING/COPYING items as INTERRUPTED.
     * Returns the ids of the batches that were still running.
     */
    suspend fun interruptUnfinished(): List<Long>
}

class RoomBackupStore(private val db: BackupDatabase) : BackupStore {
    private val dao = db.dao()

    override fun observeLatestBatch(): Flow<BatchWithItems?> = dao.observeLatestBatch()

    override suspend fun createBatch(items: List<NewItem>): BatchWithItems = db.withTransaction {
        val batch = BatchEntity(createdAt = System.currentTimeMillis(), status = BatchStatus.RUNNING)
        val batchId = dao.insertBatch(batch)
        val entities = items.map {
            ItemEntity(batchId = batchId, sourceUri = it.sourceUri, name = it.name, expectedSize = it.expectedSize)
        }
        val ids = dao.insertItems(entities)
        BatchWithItems(batch.copy(id = batchId), entities.zip(ids) { entity, id -> entity.copy(id = id) })
    }

    override suspend fun updateItem(id: Long, status: ItemStatus, reason: FailureReason?, copiedBytes: Long) =
        dao.updateItem(id, status, reason, copiedBytes)

    override suspend fun updateBatch(id: Long, status: BatchStatus) = dao.updateBatch(id, status)

    override suspend fun interruptUnfinished(): List<Long> = db.withTransaction {
        val running = dao.runningBatchIds()
        dao.interruptUnfinishedItems()
        dao.interruptRunningBatches()
        running
    }
}
