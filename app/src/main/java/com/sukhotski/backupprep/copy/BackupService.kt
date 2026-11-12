package com.sukhotski.backupprep.copy

import com.sukhotski.backupprep.data.BackupStore
import com.sukhotski.backupprep.data.BatchStatus
import com.sukhotski.backupprep.data.BatchWithItems
import com.sukhotski.backupprep.data.FailureReason
import com.sukhotski.backupprep.data.ItemEntity
import com.sukhotski.backupprep.data.ItemStatus
import com.sukhotski.backupprep.data.NewItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Runs one batch at a time: describes the picked documents, records the batch,
 * then copies the files one by one and records each outcome.
 */
class BackupService(
    private val store: BackupStore,
    private val source: DocumentSource,
    private val copier: FileCopier,
    private val destination: StagingDestination,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Startup cleanup runs first. A batch started right after launch waits for it. */
    private val startup: Job = scope.launch(ioDispatcher) { reconcileOnStart() }

    val latestBatch: Flow<BatchWithItems?> get() = store.observeLatestBatch()

    /**
     * The process was killed or crashed while a batch was running.
     * Items that never finished become INTERRUPTED and their partial files are removed.
     */
    suspend fun reconcileOnStart() {
        val interruptedBatches = store.interruptUnfinished()
        destination.deleteLeftoverParts(interruptedBatches)
    }

    fun start(uris: List<String>): Job = scope.launch(ioDispatcher) {
        startup.join()
        runBatch(uris)
    }

    suspend fun runBatch(uris: List<String>) {
        val distinctUris = uris.distinct()
        val infos = distinctUris.map(source::describe)
        val names = FileNames.unique(infos.map { FileNames.sanitize(it.displayName) })
        val newItems = distinctUris.indices.map { NewItem(distinctUris[it], names[it], infos[it].size) }

        val batch = store.createBatch(newItems)
        batch.items.zip(infos).forEach { (item, info) -> copyItem(item, info) }
        store.updateBatch(batch.batch.id, BatchStatus.FINISHED)
    }

    private suspend fun copyItem(item: ItemEntity, info: SourceInfo) {
        store.updateItem(item.id, ItemStatus.COPYING)
        val result = try {
            copier.copy(item.batchId, item.sourceUri, item.name, info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CopyResult.Failed(FailureReason.UNEXPECTED)
        }
        when (result) {
            is CopyResult.Done -> store.updateItem(item.id, ItemStatus.DONE, copiedBytes = result.bytes)
            is CopyResult.Failed -> store.updateItem(item.id, ItemStatus.FAILED, reason = result.reason)
        }
    }
}
