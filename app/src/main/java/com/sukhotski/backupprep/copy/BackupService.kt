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
    private val destination: BackupDestination,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Batches run one after another. The chain starts with the startup cleanup. */
    private var last: Job = scope.launch(ioDispatcher) { reconcile() }

    val latestBatch: Flow<BatchWithItems?> get() = store.observeLatestBatch()

    /**
     * Makes the database and the disk agree after a kill, a crash or a failed store call.
     * Unfinished items become INTERRUPTED and their files are removed, so a file on disk always means DONE.
     */
    suspend fun reconcile() {
        store.interruptUnfinished().forEach {
            destination.discard(it.batchId, it.name)
            // The finally block in runBatch never ran after a kill, so give the grant back here.
            source.release(it.sourceUri)
        }
    }

    fun start(uris: List<String>): Job = synchronized(this) {
        // Take the grant now, while the picker result is still valid, even if this batch has to wait in the queue.
        uris.distinct().forEach(source::retain)
        val previous = last
        scope.launch(ioDispatcher) {
            previous.join()
            runBatch(uris)
        }.also { last = it }
    }

    private suspend fun runBatch(uris: List<String>) {
        val distinctUris = uris.distinct()
        try {
            val infos = distinctUris.map(source::describe)
            val names = FileNames.unique(infos.map { FileNames.sanitize(it.displayName) })
            val newItems = distinctUris.indices.map { NewItem(distinctUris[it], names[it], infos[it].size) }
            copyAll(store.createBatch(newItems), infos)
        } finally {
            distinctUris.forEach(source::release)
        }
    }

    private suspend fun copyAll(batch: BatchWithItems, infos: List<SourceInfo>) {
        try {
            batch.items.zip(infos).forEach { (item, info) -> copyItem(item, info) }
            store.updateBatch(batch.batch.id, BatchStatus.FINISHED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The store failed, for example SQLite on a full disk. Record what we can instead of crashing the app.
            try {
                reconcile()
            } catch (ignored: Exception) {
                // Startup reconcile repairs it on the next launch.
            }
        }
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
