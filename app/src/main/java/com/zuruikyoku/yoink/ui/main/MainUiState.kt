package com.zuruikyoku.yoink.ui.main

import androidx.annotation.StringRes
import com.zuruikyoku.yoink.data.extractor.ExtractedMedia
import com.zuruikyoku.yoink.data.platform.Platform

data class MainUiState(
    val urlInput: String = "",
    val detectedPlatform: Platform? = null,
    /** Fetching the post's metadata to see what media it has — brief, precedes any download. */
    val isExtracting: Boolean = false,
    val isDownloading: Boolean = false,
    val progressPercent: Int = 0,
    /** > 0 total means a multi-item batch is being downloaded one at a time. */
    val queueIndex: Int = 0,
    val queueTotal: Int = 0,
    @StringRes val errorMessageRes: Int? = null,
    /** Non-null while the "pick what to yoink" sheet should be shown. */
    val pickerItems: List<ExtractedMedia>? = null
)

sealed class MainEvent {
    data class QueueFinished(val succeeded: Int, val failed: Int) : MainEvent()
}
