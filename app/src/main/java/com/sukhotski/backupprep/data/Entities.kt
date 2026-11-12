package com.sukhotski.backupprep.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class BatchStatus { RUNNING, FINISHED, INTERRUPTED }

enum class ItemStatus { PENDING, COPYING, DONE, FAILED, INTERRUPTED }

enum class FailureReason {
    SOURCE_UNREADABLE,
    NO_SPACE,
    WRITE_FAILED,
    SIZE_MISMATCH,
    VIRTUAL_DOCUMENT,
    UNEXPECTED,
}

@Entity(tableName = "batches")
data class BatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val status: BatchStatus,
)

@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = BatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("batchId")],
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val sourceUri: String,
    /** Final file name inside the batch folder. Sanitized and unique within the batch. */
    val name: String,
    /** Size reported by the provider (OpenableColumns.SIZE). Null when unknown. */
    val expectedSize: Long?,
    val status: ItemStatus = ItemStatus.PENDING,
    val failureReason: FailureReason? = null,
    val copiedBytes: Long = 0,
)

data class BatchWithItems(
    @Embedded val batch: BatchEntity,
    @Relation(parentColumn = "id", entityColumn = "batchId") val items: List<ItemEntity>,
)
