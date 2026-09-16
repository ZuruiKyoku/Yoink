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
    /** Always non-empty. More than one entry means the post is a carousel — let the user pick. */
    data class Success(val media: List<ExtractedMedia>) : ExtractionResult()
    data class Error(val reason: ExtractionError, val cause: Throwable? = null) : ExtractionResult()
}
