package com.zuruikyoku.yoink.data.extractor

import com.zuruikyoku.yoink.data.platform.Platform
import com.zuruikyoku.yoink.util.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Pulls media from public Instagram posts/reels/carousels. Tries the post page's embedded
 * JSON state first (which, for a multi-item carousel, lists every slide under
 * `edge_sidecar_to_children`); if that shape isn't found or fails to parse, falls back to
 * the Open Graph tags Instagram server-renders for link previews, which only ever expose
 * the first item. Needs no login and no JS execution. Breaks independently of the
 * Twitter/Pinterest extractors if Instagram changes its markup.
 */
class InstagramExtractor : MediaExtractor {

    override val platform = Platform.INSTAGRAM

    private val postUrlRegex = Regex(
        """instagram\.com/(?:p|reel|reels|tv)/[A-Za-z0-9_-]+""",
        RegexOption.IGNORE_CASE
    )

    private val jsonScriptRegex = Regex(
        """<script[^>]+type="application/json"[^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val metaTagRegex = Regex("""<meta\s+[^>]*>""", RegexOption.IGNORE_CASE)
    private val propertyAttrRegex = Regex("""property\s*=\s*"([^"]*)"""")
    private val contentAttrRegex = Regex("""content\s*=\s*"([^"]*)"""")

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

        val carouselItems = extractCarouselFromEmbeddedJson(html, sourceUrl)
        if (!carouselItems.isNullOrEmpty()) {
            return ExtractionResult.Success(carouselItems)
        }

        val single = extractFromOgTags(html, sourceUrl)
            ?: return ExtractionResult.Error(ExtractionError.NO_MEDIA_FOUND)
        return ExtractionResult.Success(listOf(single))
    }

    /** Best-effort: looks for a sidecar (carousel) node in any embedded JSON blob. Null/empty means "not found". */
    private fun extractCarouselFromEmbeddedJson(html: String, sourceUrl: String): List<ExtractedMedia>? {
        for (match in jsonScriptRegex.findAll(html)) {
            val root = try {
                JSONObject(match.groupValues[1])
            } catch (e: Exception) {
                continue
            }

            val sidecar = deepFind(root) { key, value ->
                key == "edge_sidecar_to_children" && value is JSONObject && value.has("edges")
            } as? JSONObject ?: continue

            val edges = sidecar.optJSONArray("edges") ?: continue
            val items = (0 until edges.length()).mapNotNull { i ->
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: return@mapNotNull null
                nodeToMedia(node, sourceUrl)
            }
            if (items.isNotEmpty()) return items
        }
        return null
    }

    private fun nodeToMedia(node: JSONObject, sourceUrl: String): ExtractedMedia? {
        val displayUrl = node.optString("display_url").takeIf { it.isNotBlank() }
        return if (node.optBoolean("is_video")) {
            val videoUrl = node.optString("video_url").takeIf { it.isNotBlank() } ?: return null
            ExtractedMedia(
                mediaUrl = videoUrl,
                mediaType = MediaType.VIDEO,
                platform = platform,
                sourceUrl = sourceUrl,
                thumbnailUrl = displayUrl
            )
        } else {
            displayUrl?.let {
                ExtractedMedia(mediaUrl = it, mediaType = MediaType.IMAGE, platform = platform, sourceUrl = sourceUrl)
            }
        }
    }

    private fun deepFind(node: Any?, predicate: (String, Any?) -> Boolean): Any? {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (predicate(key, value)) return value
                    val found = deepFind(value, predicate)
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = deepFind(node.opt(i), predicate)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun extractFromOgTags(html: String, sourceUrl: String): ExtractedMedia? {
        val ogTags = extractOgTags(html)
        val videoUrl = ogTags["og:video"] ?: ogTags["og:video:secure_url"]
        val imageUrl = ogTags["og:image"]

        return when {
            !videoUrl.isNullOrBlank() -> ExtractedMedia(
                mediaUrl = unescapeHtml(videoUrl),
                mediaType = MediaType.VIDEO,
                platform = platform,
                sourceUrl = sourceUrl,
                thumbnailUrl = imageUrl?.let { unescapeHtml(it) }
            )

            !imageUrl.isNullOrBlank() -> ExtractedMedia(
                mediaUrl = unescapeHtml(imageUrl),
                mediaType = MediaType.IMAGE,
                platform = platform,
                sourceUrl = sourceUrl
            )

            else -> null
        }
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
