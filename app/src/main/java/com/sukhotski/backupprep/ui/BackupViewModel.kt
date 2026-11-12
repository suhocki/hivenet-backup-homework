package com.sukhotski.backupprep.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sukhotski.backupprep.BackupApp
import com.sukhotski.backupprep.copy.BackupService
import com.sukhotski.backupprep.data.BatchStatus
import com.sukhotski.backupprep.data.BatchWithItems
import com.sukhotski.backupprep.data.ItemEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class BackupUiState(
    val isLoading: Boolean = false,
    val batch: BatchWithItems? = null,
) {
    val isRunning: Boolean get() = batch?.batch?.status == BatchStatus.RUNNING
}

/** Thin: the copy runs in the app scope, the screen only observes the database. */
class BackupViewModel(private val service: BackupService) : ViewModel() {

    val state: StateFlow<BackupUiState> = service.latestBatch
        .map { BackupUiState(batch = it?.copy(items = it.items.sortedBy(ItemEntity::id))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState(isLoading = true))

    fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        service.start(uris.map(Uri::toString))
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BackupApp
                BackupViewModel(app.container.backupService)
            }
        }
    }
}
