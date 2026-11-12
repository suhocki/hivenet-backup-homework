package com.sukhotski.backupprep.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [BatchEntity::class, ItemEntity::class], version = 1, exportSchema = true)
abstract class BackupDatabase : RoomDatabase() {
    abstract fun dao(): BackupDao
}
