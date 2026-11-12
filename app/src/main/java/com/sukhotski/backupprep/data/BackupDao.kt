package com.sukhotski.backupprep.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupDao {

    @Insert
    suspend fun insertBatch(batch: BatchEntity): Long

    @Insert
    suspend fun insertItems(items: List<ItemEntity>): List<Long>

    @Query("UPDATE items SET status = :status, failureReason = :reason, copiedBytes = :copiedBytes WHERE id = :id")
    suspend fun updateItem(id: Long, status: ItemStatus, reason: FailureReason?, copiedBytes: Long)

    @Query("UPDATE batches SET status = :status WHERE id = :id")
    suspend fun updateBatch(id: Long, status: BatchStatus)

    @Transaction
    @Query("SELECT * FROM batches ORDER BY id DESC LIMIT 1")
    fun observeLatestBatch(): Flow<BatchWithItems?>

    @Query("SELECT id FROM batches WHERE status = 'RUNNING'")
    suspend fun runningBatchIds(): List<Long>

    @Query("UPDATE items SET status = 'INTERRUPTED' WHERE status IN ('PENDING', 'COPYING')")
    suspend fun interruptUnfinishedItems()

    @Query("UPDATE batches SET status = 'INTERRUPTED' WHERE status = 'RUNNING'")
    suspend fun interruptRunningBatches()
}
