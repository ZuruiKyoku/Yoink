package com.zuruikyoku.yoink.data.extractor

import com.zuruikyoku.yoink.data.platform.Platform

enum class MediaType { IMAGE, VIDEO, GIF }

/** Twitter serves "GIFs" as muted looping mp4 files, so GIF and VIDEO share storage/mime handling. */
val MediaType.isVideoLike: Boolean get() = this == MediaType.VIDEO || this == MediaType.GIF

data class ExtractedMedia(
    val mediaUrl: String,
    val mediaType: MediaType,
    val platform: Platform,
    val sourceUrl: String,
    val thumbnailUrl: String? = null
)
