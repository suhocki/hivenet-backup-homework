package com.sukhotski.backupprep.ui

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sukhotski.backupprep.R
import com.sukhotski.backupprep.data.BatchWithItems
import com.sukhotski.backupprep.data.FailureReason
import com.sukhotski.backupprep.data.ItemEntity
import com.sukhotski.backupprep.data.ItemStatus
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(state: BackupUiState, onSelectFiles: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
        ) {
            Button(onClick = onSelectFiles, enabled = !state.isRunning, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.select_files))
            }
            Spacer(Modifier.height(16.dp))
            val batch = state.batch
            when {
                state.isLoading -> Unit
                batch == null -> Text(stringResource(R.string.empty_hint), style = MaterialTheme.typography.bodyMedium)
                else -> BatchView(batch)
            }
        }
    }
}

@Composable
private fun BatchView(batch: BatchWithItems) {
    val context = LocalContext.current
    val date = Date(batch.batch.createdAt)
    val formattedDate = "${DateFormat.getMediumDateFormat(context).format(date)} ${DateFormat.getTimeFormat(context).format(date)}"
    Text(stringResource(R.string.batch_from, formattedDate), style = MaterialTheme.typography.labelMedium)
    Text(summary(batch.items), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(batch.items, key = { it.id }) { item ->
            ItemRow(item)
            HorizontalDivider()
        }
    }
}

@Composable
private fun ItemRow(item: ItemEntity) {
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            val size = item.expectedSize?.let { Formatter.formatShortFileSize(context, it) }
                ?: stringResource(R.string.size_unknown)
            Text(size, style = MaterialTheme.typography.bodySmall)
            Text(
                text = statusText(item),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(item.status),
            )
        }
        if (item.status == ItemStatus.COPYING) CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun statusText(item: ItemEntity): String = when (item.status) {
    ItemStatus.PENDING -> stringResource(R.string.status_pending)
    ItemStatus.COPYING -> stringResource(R.string.status_copying)
    ItemStatus.DONE -> stringResource(R.string.status_done)
    ItemStatus.INTERRUPTED -> stringResource(R.string.status_interrupted)
    ItemStatus.FAILED -> stringResource(R.string.status_failed) + ": " + reasonText(item.failureReason)
}

@Composable
private fun reasonText(reason: FailureReason?): String = stringResource(
    when (reason) {
        FailureReason.SOURCE_UNREADABLE -> R.string.reason_source_unreadable
        FailureReason.NO_SPACE -> R.string.reason_no_space
        FailureReason.WRITE_FAILED -> R.string.reason_write_failed
        FailureReason.SIZE_MISMATCH -> R.string.reason_size_mismatch
        FailureReason.VIRTUAL_DOCUMENT -> R.string.reason_virtual_document
        FailureReason.UNEXPECTED, null -> R.string.reason_unexpected
    },
)

@Composable
private fun statusColor(status: ItemStatus): Color = when (status) {
    ItemStatus.DONE -> Color(0xFF2E7D32)
    ItemStatus.FAILED -> MaterialTheme.colorScheme.error
    ItemStatus.INTERRUPTED -> MaterialTheme.colorScheme.tertiary
    ItemStatus.PENDING, ItemStatus.COPYING -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** "4 copied, 1 failed". Only non-zero groups are shown. */
@Composable
private fun summary(items: List<ItemEntity>): String {
    val counts = items.groupingBy { it.status }.eachCount()
    val parts = listOfNotNull(
        counts[ItemStatus.DONE]?.let { stringResource(R.string.summary_copied, it) },
        counts[ItemStatus.FAILED]?.let { stringResource(R.string.summary_failed, it) },
        counts[ItemStatus.INTERRUPTED]?.let { stringResource(R.string.summary_interrupted, it) },
        counts[ItemStatus.COPYING]?.let { stringResource(R.string.summary_copying, it) },
        counts[ItemStatus.PENDING]?.let { stringResource(R.string.summary_pending, it) },
    )
    return parts.joinToString(", ")
}
