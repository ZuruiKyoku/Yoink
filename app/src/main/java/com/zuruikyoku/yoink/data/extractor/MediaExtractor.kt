package com.zuruikyoku.yoink.data.extractor

import com.zuruikyoku.yoink.data.platform.Platform

/**
 * One implementation per platform. Each extractor is fully self-contained — it must
 * catch its own network/parsing failures and translate them into an [ExtractionResult.Error]
 * rather than throwing, so a break in one platform's scraping never affects the others.
 */
interface MediaExtractor {
    val platform: Platform

    suspend fun extract(url: String): ExtractionResult
}
