package com.zuruikyoku.yoink.data.extractor

import com.zuruikyoku.yoink.data.platform.Platform
import com.zuruikyoku.yoink.util.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Pulls media from public Instagram posts/reels using the Open Graph tags Instagram
 * server-renders into the post page's HTML `<head>` for link-preview purposes. This
 * needs no login and no JS execution, but only ever exposes the *first* item of a
 * carousel — Instagram doesn't put the rest in page metadata. Breaks independently of
 * the Twitter/Pinterest extractors if Instagram changes its markup.
 */
class InstagramExtractor : MediaExtractor {

    override val platform = Platform.INSTAGRAM

    private val postUrlRegex = Regex(
        """instagram\.com/(?:p|reel|reels|tv)/[A-Za-z0-9_-]+""",
        RegexOption.IGNORE_CASE
    )

    private val metaTagRegex = Regex("""<meta\s+[^>]*>""", RegexOption.IGNORE_CASE)
    private val propertyAttrRegex = Regex("""property\s*=\s*"([^"]*)"""")
    private val contentAttrRegex = Regex("""content\s*=\s*"([^"]*)"""")
    private val sidecarRegex = Regex(""""__typename"\s*:\s*"GraphSidecar"|"product_type"\s*:\s*"carousel_container"""")

    override suspend fun extract(url: String): ExtractionResult = withContext(Dispatchers.IO) {
        if (!postUrlRegex.containsMatchIn(url)) {
            return@withContext ExtractionResult.Error(ExtractionError.INVALID_URL)
        }

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        val html = try {
            NetworkClient.client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 ->
                        return@withContext ExtractionResult.Error(ExtractionError.NOT_FOUND_OR_PRIVATE)
                    !response.isSuccessful ->
                        return@withContext ExtractionResult.Error(ExtractionError.NETWORK_ERROR)
                    else -> response.body?.string()
                }
            }
        } catch (e: IOException) {
            return@withContext ExtractionResult.Error(ExtractionError.NETWORK_ERROR, e)
        }

        if (html.isNullOrBlank()) {
            return@withContext ExtractionResult.Error(ExtractionError.NOT_FOUND_OR_PRIVATE)
        }

        parseHtml(html, url)
    }

    private fun parseHtml(html: String, sourceUrl: String): ExtractionResult {
        if (html.contains("Sorry, this page isn't available") ||
            html.contains("This account is private")
        ) {
            return ExtractionResult.Error(ExtractionError.NOT_FOUND_OR_PRIVATE)
        }

        val ogTags = extractOgTags(html)
        val isCarousel = sidecarRegex.containsMatchIn(html)

        val videoUrl = ogTags["og:video"] ?: ogTags["og:video:secure_url"]
        val imageUrl = ogTags["og:image"]

        val media = when {
            !videoUrl.isNullOrBlank() -> ExtractedMedia(
                mediaUrl = unescapeHtml(videoUrl),
                mediaType = MediaType.VIDEO,
                platform = platform,
                sourceUrl = sourceUrl,
                thumbnailUrl = imageUrl?.let { unescapeHtml(it) },
                isPartialCarousel = isCarousel
            )

            !imageUrl.isNullOrBlank() -> ExtractedMedia(
                mediaUrl = unescapeHtml(imageUrl),
                mediaType = MediaType.IMAGE,
                platform = platform,
                sourceUrl = sourceUrl,
                isPartialCarousel = isCarousel
            )

            else -> null
        } ?: return ExtractionResult.Error(ExtractionError.NO_MEDIA_FOUND)

        return ExtractionResult.Success(media)
    }

    private fun extractOgTags(html: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (match in metaTagRegex.findAll(html)) {
            val tag = match.value
            val property = propertyAttrRegex.find(tag)?.groupValues?.get(1) ?: continue
            if (!property.startsWith("og:")) continue
            val content = contentAttrRegex.find(tag)?.groupValues?.get(1) ?: continue
            result[property] = content
        }
        return result
    }

    private fun unescapeHtml(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
