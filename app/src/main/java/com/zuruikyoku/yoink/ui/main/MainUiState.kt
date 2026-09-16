package com.zuruikyoku.yoink.ui.main

import androidx.annotation.StringRes
import com.zuruikyoku.yoink.data.platform.Platform

data class MainUiState(
    val urlInput: String = "",
    val detectedPlatform: Platform? = null,
    val isDownloading: Boolean = false,
    val progressPercent: Int = 0,
    @StringRes val errorMessageRes: Int? = null
)

sealed class MainEvent {
    data class DownloadSucceeded(val isPartialCarousel: Boolean) : MainEvent()
    data object DownloadFailed : MainEvent()
}
