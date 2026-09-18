package com.zuruikyoku.yoink.data.extractor

import com.zuruikyoku.yoink.data.platform.Platform

object ExtractorRegistry {

    // Instagram extraction is disabled for now (see InstagramExtractor) - MainViewModel
    // intercepts Instagram links before they'd ever reach here, so this entry is commented
    // out rather than routed to a dead extractor.
    private val extractors: Map<Platform, MediaExtractor> = mapOf(
        Platform.TWITTER to TwitterExtractor(),
        // Platform.INSTAGRAM to InstagramExtractor(),
        Platform.PINTEREST to PinterestExtractor()
    )

    fun forPlatform(platform: Platform): MediaExtractor = extractors.getValue(platform)
}
