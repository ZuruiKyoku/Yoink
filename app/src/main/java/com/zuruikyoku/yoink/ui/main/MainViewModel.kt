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
import com.zuruikyoku.yoink.data.extractor.ExtractionError
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
        if (_uiState.value.isDownloading) return
        val detected = UrlDetector.detect(_uiState.value.urlInput)
        if (detected == null) {
            _uiState.update { it.copy(errorMessageRes = R.string.error_invalid_url) }
            return
        }

        val request = DownloadWorker.buildRequest(detected.url, detected.platform)
        _uiState.update { it.copy(isDownloading = true, progressPercent = 0, errorMessageRes = null) }
        workManager.enqueue(request)
        observeWork(request.id)
    }

    private fun observeWork(id: UUID) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(id).collect { info ->
                if (info == null) return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        val percent = info.progress.getInt(
                            DownloadWorker.KEY_PROGRESS_PERCENT,
                            _uiState.value.progressPercent
                        )
                        _uiState.update { it.copy(isDownloading = true, progressPercent = percent) }
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        val isPartial = info.outputData.getBoolean(DownloadWorker.KEY_IS_PARTIAL_CAROUSEL, false)
                        _uiState.update {
                            it.copy(isDownloading = false, progressPercent = 100, urlInput = "", detectedPlatform = null)
                        }
                        _events.emit(MainEvent.DownloadSucceeded(isPartial))
                    }

                    WorkInfo.State.FAILED -> {
                        val reason = info.outputData.getString(DownloadWorker.KEY_ERROR_REASON)
                            ?.let { runCatching { ExtractionError.valueOf(it) }.getOrNull() }
                            ?: ExtractionError.UNKNOWN
                        _uiState.update { it.copy(isDownloading = false, errorMessageRes = errorMessageFor(reason)) }
                        _events.emit(MainEvent.DownloadFailed)
                    }

                    WorkInfo.State.CANCELLED -> {
                        _uiState.update { it.copy(isDownloading = false) }
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
