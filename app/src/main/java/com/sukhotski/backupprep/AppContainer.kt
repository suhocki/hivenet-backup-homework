package com.sukhotski.backupprep

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.sukhotski.backupprep.copy.BackupService
import com.sukhotski.backupprep.copy.ContentResolverDocumentSource
import com.sukhotski.backupprep.copy.FileCopier
import com.sukhotski.backupprep.copy.StagingDestination
import com.sukhotski.backupprep.data.BackupDatabase
import com.sukhotski.backupprep.data.RoomBackupStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Hand-wired dependencies. Small enough that a DI framework would only add noise. */
class AppContainer(app: Application) {

    /** Lives as long as the process, so a running copy survives rotation and screen changes. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logUnhandled)

    private val database = Room.databaseBuilder(app, BackupDatabase::class.java, "backup.db").build()

    private val source = ContentResolverDocumentSource(app.contentResolver)

    private val destination = StagingDestination(File(app.filesDir, "staging"))

    val backupService = BackupService(
        store = RoomBackupStore(database),
        source = source,
        copier = FileCopier(source, destination),
        destination = destination,
        scope = appScope,
    )

    private companion object {
        /** Last line of defence: a bug in background work must not crash the app. Only the exception type is logged, its message may contain a path. */
        val logUnhandled = CoroutineExceptionHandler { _, e -> Log.e("BackupApp", "Background work failed: ${e::class.java.simpleName}") }
    }
}
