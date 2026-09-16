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
 * Pulls the original-resolution image, or the best video variant, from a Pinterest pin
 * page's embedded JSON (the Redux state Pinterest server-renders into a `<script
 * type="application/json">` block). Falls back to Open Graph tags if that blob can't be
 * found or parsed. Breaks independently of the Twitter/Instagram extractors if Pinterest
 * reshapes its page data.
 */
class PinterestExtractor : MediaExtractor {

    override val platform = Platform.PINTEREST

    private val pinUrlRegex = Regex(
        """pinterest\.[a-z.]+/pin/[A-Za-z0-9_-]+|pin\.it/[A-Za-z0-9]+""",
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
        if (!pinUrlRegex.containsMatchIn(url)) {
            return@withContext ExtractionResult.Error(ExtractionError.INVALID_URL)
        }

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        var resolvedUrl = url
        val html = try {
            NetworkClient.client.newCall(request).execute().use { response ->
                resolvedUrl = response.request.url.toString()
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

        parseHtml(html, resolvedUrl)
    }

    private fun parseHtml(html: String, sourceUrl: String): ExtractionResult {
        if (html.contains("Sorry! We couldn't find that page")) {
            return ExtractionResult.Error(ExtractionError.NOT_FOUND_OR_PRIVATE)
        }

        val (videoUrl, imageUrl) = extractFromEmbeddedJson(html)

        val media = when {
            !videoUrl.isNullOrBlank() -> ExtractedMedia(
                mediaUrl = videoUrl,
                mediaType = MediaType.VIDEO,
                platform = platform,
                sourceUrl = sourceUrl,
                thumbnailUrl = imageUrl
            )

            !imageUrl.isNullOrBlank() -> ExtractedMedia(
                mediaUrl = imageUrl,
                mediaType = MediaType.IMAGE,
                platform = platform,
                sourceUrl = sourceUrl
            )

            else -> extractFromOgTags(html)?.let { (ogUrl, isVideo) ->
                ExtractedMedia(
                    mediaUrl = ogUrl,
                    mediaType = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
                    platform = platform,
                    sourceUrl = sourceUrl
                )
            }
        } ?: return ExtractionResult.Error(ExtractionError.NO_MEDIA_FOUND)

        return ExtractionResult.Success(listOf(media))
    }

    /** Returns (bestVideoUrl, origImageUrl) found anywhere in the page's JSON state blobs. */
    private fun extractFromEmbeddedJson(html: String): Pair<String?, String?> {
        var bestVideoUrl: String? = null
        var bestVideoWidth = -1
        var origImageUrl: String? = null

        for (match in jsonScriptRegex.findAll(html)) {
            val root = try {
                JSONObject(match.groupValues[1])
            } catch (e: Exception) {
                continue
            }

            if (origImageUrl == null) {
                val imagesNode = deepFind(root) { key, value ->
                    key == "images" && value is JSONObject && value.has("orig")
                } as? JSONObject
                origImageUrl = imagesNode?.optJSONObject("orig")?.optString("url")?.takeIf { it.isNotBlank() }
            }

            val videoListNode = deepFind(root) { key, value -> key == "video_list" && value is JSONObject } as? JSONObject
            videoListNode?.keys()?.forEach { variantKey ->
                val variant = videoListNode.optJSONObject(variantKey) ?: return@forEach
                val width = variant.optInt("width", -1)
                val variantUrl = variant.optString("url").takeIf { it.isNotBlank() } ?: return@forEach
                if (width > bestVideoWidth) {
                    bestVideoWidth = width
                    bestVideoUrl = variantUrl
                }
            }

            if (origImageUrl != null && bestVideoUrl != null) break
        }

        return bestVideoUrl to origImageUrl
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

    /** Returns (url, isVideo) from Open Graph tags, as a last resort. */
    private fun extractFromOgTags(html: String): Pair<String, Boolean>? {
        val tags = mutableMapOf<String, String>()
        for (match in metaTagRegex.findAll(html)) {
            val tag = match.value
            val property = propertyAttrRegex.find(tag)?.groupValues?.get(1) ?: continue
            if (!property.startsWith("og:")) continue
            val content = contentAttrRegex.find(tag)?.groupValues?.get(1) ?: continue
            tags[property] = content
        }
        val video = tags["og:video"] ?: tags["og:video:url"]
        if (!video.isNullOrBlank()) return unescapeHtml(video) to true
        val image = tags["og:image"] ?: return null
        return unescapeHtml(image) to false
    }

    private fun unescapeHtml(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
