package com.zuruikyoku.yoink.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.getWorkInfoByIdFlow
import com.zuruikyoku.yoink.R
import com.zuruikyoku.yoink.data.clipboard.ClipboardHelper
import com.zuruikyoku.yoink.data.db.DownloadEntity
import com.zuruikyoku.yoink.data.db.YoinkDatabase
import com.zuruikyoku.yoink.data.download.DownloadWorker
import com.zuruikyoku.yoink.data.extractor.ExtractedMedia
import com.zuruikyoku.yoink.data.extractor.ExtractionError
import com.zuruikyoku.yoink.data.extractor.ExtractionResult
import com.zuruikyoku.yoink.data.extractor.ExtractorRegistry
import com.zuruikyoku.yoink.data.platform.Platform
import com.zuruikyoku.yoink.data.platform.UrlDetector
import com.zuruikyoku.yoink.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val clipboardHelper = ClipboardHelper(application)
    private val downloadDao = YoinkDatabase.getInstance(application).downloadDao()
    private val workManager = WorkManager.getInstance(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MainEvent>()
    val events: SharedFlow<MainEvent> = _events.asSharedFlow()

    private val _selectedFilter = MutableStateFlow<Platform?>(null)
    val selectedFilter: StateFlow<Platform?> = _selectedFilter.asStateFlow()

    private val history: StateFlow<List<DownloadEntity>> = downloadDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredHistory: StateFlow<List<DownloadEntity>> =
        combine(history, _selectedFilter) { list, filter ->
            if (filter == null) list else list.filter { it.platform == filter.name }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var hasCheckedClipboard = false

    // In-memory download queue — one item downloads at a time so a single progress bar
    // and notification can represent the whole batch ("2 of 4").
    private val downloadQueue = ArrayDeque<ExtractedMedia>()
    private var queueSucceeded = 0
    private var queueFailed = 0
    private var queueSize = 0

    /** Called once when the main screen first appears (not on every recomposition/rotation). */
    fun maybePrefillFromClipboard() {
        if (hasCheckedClipboard) return
        hasCheckedClipboard = true
        viewModelScope.launch {
            val settings = settingsRepository.currentSettings()
            if (!settings.autoClipboardDetect) return@launch
            if (_uiState.value.urlInput.isNotBlank()) return@launch
            val text = clipboardHelper.readText() ?: return@launch
            applyDetectedText(text)
        }
    }

    /** Called from a share-sheet or deep-link intent; always overrides current input. */
    fun onSharedText(text: String?) {
        applyDetectedText(text)
    }

    private fun applyDetectedText(text: String?) {
        val detected = UrlDetector.detect(text) ?: return
        _uiState.update {
            it.copy(urlInput = detected.url, detectedPlatform = detected.platform, errorMessageRes = null)
        }
    }

    fun onUrlChanged(newValue: String) {
        val detected = UrlDetector.detect(newValue)
        _uiState.update {
            it.copy(urlInput = newValue, detectedPlatform = detected?.platform, errorMessageRes = null)
        }
    }

    fun onClear() {
        _uiState.update { it.copy(urlInput = "", detectedPlatform = null, errorMessageRes = null) }
    }

    fun onFilterSelected(platform: Platform?) {
        _selectedFilter.value = platform
    }

    fun onDeleteEntry(entity: DownloadEntity) {
        viewModelScope.launch { downloadDao.delete(entity) }
    }

    fun onDownloadClicked() {
        val state = _uiState.value
        if (state.isDownloading || state.isExtracting) return
        val detected = UrlDetector.detect(state.urlInput)
        if (detected == null) {
            _uiState.update { it.copy(errorMessageRes = R.string.error_invalid_url) }
            return
        }

        _uiState.update { it.copy(isExtracting = true, errorMessageRes = null) }
        viewModelScope.launch {
            when (val result = ExtractorRegistry.forPlatform(detected.platform).extract(detected.url)) {
                is ExtractionResult.Error -> {
                    _uiState.update { it.copy(isExtracting = false, errorMessageRes = errorMessageFor(result.reason)) }
                }

                is ExtractionResult.Success -> {
                    if (result.media.size == 1) {
                        _uiState.update { it.copy(isExtracting = false) }
                        startDownloadQueue(result.media)
                    } else {
                        _uiState.update { it.copy(isExtracting = false, pickerItems = result.media) }
                    }
                }
            }
        }
    }

    /** User confirmed a selection from the "pick what to yoink" sheet. */
    fun onPickerConfirmed(selected: List<ExtractedMedia>) {
        _uiState.update { it.copy(pickerItems = null) }
        if (selected.isNotEmpty()) startDownloadQueue(selected)
    }

    fun onPickerDismissed() {
        _uiState.update { it.copy(pickerItems = null) }
    }

    private fun startDownloadQueue(items: List<ExtractedMedia>) {
        downloadQueue.clear()
        downloadQueue.addAll(items)
        queueSize = items.size
        queueSucceeded = 0
        queueFailed = 0
        advanceQueue()
    }

    private fun advanceQueue() {
        val next = downloadQueue.removeFirstOrNull()
        if (next == null) {
            val succeeded = queueSucceeded
            val failed = queueFailed
            _uiState.update {
                it.copy(isDownloading = false, progressPercent = 0, queueIndex = 0, queueTotal = 0)
            }
            viewModelScope.launch { _events.emit(MainEvent.QueueFinished(succeeded, failed)) }
            return
        }

        val doneSoFar = queueSize - downloadQueue.size // includes the item we just popped
        _uiState.update {
            it.copy(
                isDownloading = true,
                progressPercent = 0,
                queueIndex = doneSoFar,
                queueTotal = queueSize,
                urlInput = "",
                detectedPlatform = null,
                errorMessageRes = null
            )
        }

        val request = DownloadWorker.buildRequest(next, queueIndex = doneSoFar, queueTotal = queueSize)
        workManager.enqueue(request)
        observeWork(request.id)
    }

    private fun observeWork(id: UUID) {
        viewModelScope.launch {
            // Room's observed Flow can re-emit the same terminal state if unrelated work
            // rows change; this guard keeps a redundant emission from double-counting.
            var handled = false
            workManager.getWorkInfoByIdFlow(id).collect { info ->
                if (info == null || handled) return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        val percent = info.progress.getInt(
                            DownloadWorker.KEY_PROGRESS_PERCENT,
                            _uiState.value.progressPercent
                        )
                        _uiState.update { it.copy(progressPercent = percent) }
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        handled = true
                        queueSucceeded++
                        advanceQueue()
                    }

                    WorkInfo.State.FAILED -> {
                        handled = true
                        queueFailed++
                        if (queueSize == 1) {
                            val reason = info.outputData.getString(DownloadWorker.KEY_ERROR_REASON)
                                ?.let { runCatching { ExtractionError.valueOf(it) }.getOrNull() }
                                ?: ExtractionError.UNKNOWN
                            _uiState.update { it.copy(errorMessageRes = errorMessageFor(reason)) }
                        }
                        advanceQueue()
                    }

                    WorkInfo.State.CANCELLED -> {
                        handled = true
                        queueFailed++
                        advanceQueue()
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun errorMessageFor(reason: ExtractionError): Int = when (reason) {
        ExtractionError.INVALID_URL -> R.string.error_invalid_url
        ExtractionError.NOT_FOUND_OR_PRIVATE -> R.string.error_private_or_deleted
        ExtractionError.NO_MEDIA_FOUND -> R.string.error_no_media
        ExtractionError.NETWORK_ERROR -> R.string.error_network
        ExtractionError.UNSUPPORTED, ExtractionError.UNKNOWN -> R.string.error_unknown
    }
}
