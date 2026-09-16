package com.zuruikyoku.yoink.data.extractor

import com.zuruikyoku.yoink.data.platform.Platform

object ExtractorRegistry {

    private val extractors: Map<Platform, MediaExtractor> = mapOf(
        Platform.TWITTER to TwitterExtractor(),
        Platform.INSTAGRAM to InstagramExtractor(),
        Platform.PINTEREST to PinterestExtractor()
    )

    fun forPlatform(platform: Platform): MediaExtractor = extractors.getValue(platform)
}
