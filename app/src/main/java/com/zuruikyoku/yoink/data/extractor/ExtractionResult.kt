package com.zuruikyoku.yoink.data.extractor

enum class ExtractionError {
    INVALID_URL,
    NOT_FOUND_OR_PRIVATE,
    NO_MEDIA_FOUND,
    NETWORK_ERROR,
    UNSUPPORTED,
    UNKNOWN
}

sealed class ExtractionResult {
    data class Success(val media: ExtractedMedia) : ExtractionResult()
    data class Error(val reason: ExtractionError, val cause: Throwable? = null) : ExtractionResult()
}
