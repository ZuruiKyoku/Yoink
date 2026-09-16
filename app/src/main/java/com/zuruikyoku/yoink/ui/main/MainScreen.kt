package com.zuruikyoku.yoink.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zuruikyoku.yoink.R
import com.zuruikyoku.yoink.data.platform.Platform
import com.zuruikyoku.yoink.ui.components.EmptyNest
import com.zuruikyoku.yoink.ui.components.HistoryItemRow
import com.zuruikyoku.yoink.ui.components.MediaPickerSheet
import com.zuruikyoku.yoink.ui.components.UrlInputSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val history by viewModel.filteredHistory.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.maybePrefillFromClipboard()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is MainEvent.QueueFinished -> {
                    val message = when {
                        event.succeeded == 0 -> context.getString(R.string.toast_failed)
                        event.failed == 0 && event.succeeded == 1 -> context.getString(R.string.toast_nabbed)
                        event.failed == 0 -> context.getString(R.string.toast_nabbed_multiple, event.succeeded)
                        else -> context.getString(R.string.toast_nabbed_partial, event.succeeded, event.failed)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    uiState.pickerItems?.let { items ->
        MediaPickerSheet(
            items = items,
            onConfirm = viewModel::onPickerConfirmed,
            onDismiss = viewModel::onPickerDismissed
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.main_title), style = MaterialTheme.typography.headlineMedium)
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.main_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                UrlInputSection(
                    url = uiState.urlInput,
                    detectedPlatform = uiState.detectedPlatform,
                    isExtracting = uiState.isExtracting,
                    isDownloading = uiState.isDownloading,
                    progressPercent = uiState.progressPercent,
                    queueIndex = uiState.queueIndex,
                    queueTotal = uiState.queueTotal,
                    errorMessageRes = uiState.errorMessageRes,
                    onUrlChanged = viewModel::onUrlChanged,
                    onClear = viewModel::onClear,
                    onDownloadClick = viewModel::onDownloadClicked
                )
                Spacer(Modifier.height(28.dp))
                Text(stringResource(R.string.nest_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(10.dp))
                PlatformFilterRow(selectedFilter, viewModel::onFilterSelected)
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (history.isEmpty()) {
                item { EmptyNest() }
            } else {
                items(history, key = { it.id }) { entity ->
                    HistoryItemRow(
                        entity = entity,
                        onClick = { openMedia(context, entity.mediaUri, entity.mediaType) },
                        onDelete = { viewModel.onDeleteEntry(entity) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlatformFilterRow(selected: Platform?, onSelected: (Platform?) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelected(null) },
            label = { Text(stringResource(R.string.nest_filter_all)) }
        )
        FilterChip(
            selected = selected == Platform.TWITTER,
            onClick = { onSelected(Platform.TWITTER) },
            label = { Text(stringResource(R.string.nest_filter_twitter)) }
        )
        FilterChip(
            selected = selected == Platform.INSTAGRAM,
            onClick = { onSelected(Platform.INSTAGRAM) },
            label = { Text(stringResource(R.string.nest_filter_instagram)) }
        )
        FilterChip(
            selected = selected == Platform.PINTEREST,
            onClick = { onSelected(Platform.PINTEREST) },
            label = { Text(stringResource(R.string.nest_filter_pinterest)) }
        )
    }
}

private fun openMedia(context: Context, mediaUri: String, mediaTypeName: String) {
    val mimePrefix = if (mediaTypeName == "IMAGE") "image/*" else "video/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(mediaUri), mimePrefix)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}
