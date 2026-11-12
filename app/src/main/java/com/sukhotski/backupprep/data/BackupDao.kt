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

    @Query("SELECT * FROM items WHERE status IN (:statuses)")
    suspend fun itemsWithStatus(statuses: List<ItemStatus>): List<ItemEntity>

    @Query("UPDATE items SET status = :to WHERE status IN (:from)")
    suspend fun updateItemsWithStatus(from: List<ItemStatus>, to: ItemStatus)

    @Query("UPDATE batches SET status = :to WHERE status = :from")
    suspend fun updateBatchesWithStatus(from: BatchStatus, to: BatchStatus)
}
