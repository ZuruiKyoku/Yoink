package com.zuruikyoku.yoink.data.extractor

import com.zuruikyoku.yoink.data.platform.Platform
import com.zuruikyoku.yoink.util.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

/**
 * Pulls the original-resolution image, or the best video variant, from a Pinterest pin
 * page's embedded JSON. Pinterest's exact wrapper markup for that JSON (which `<script>`
 * tag, what it's keyed under) has changed shape more than once, so the primary strategy
 * is a raw scan of the whole HTML for `"url":"...mp4..."` occurrences anchored near the
 * pin's own numeric id — that survives a wrapper-shape change that would break a
 * structured `<script type="application/json">` parse. The structured parse and Open
 * Graph tags are kept as fallbacks. Breaks independently of the Twitter/Instagram
 * extractors if Pinterest reshapes its page data again.
 */
class PinterestExtractor : MediaExtractor {

    override val platform = Platform.PINTEREST

    private val pinUrlRegex = Regex(
        """pinterest\.[a-z.]+/pin/[A-Za-z0-9_-]+|pin\.it/[A-Za-z0-9]+""",
        RegexOption.IGNORE_CASE
    )

    // Deliberately lenient (leading digits only, whatever follows) rather than requiring the
    // whole path segment to be numeric — this is only used to anchor the raw-text video scan
    // below, so a wrong guess about Pinterest's URL shape just degrades that anchoring, it
    // doesn't reject a link pinUrlRegex above would otherwise accept.
    private val pinIdRegex = Regex("""pin/(\d+)""")

    private val jsonScriptRegex = Regex(
        """<script[^>]+type="application/json"[^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val rawMp4UrlRegex = Regex(""""url"\s*:\s*"([^"]+?\.mp4[^"]*?)"""")
    private val resolutionHintRegex = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)

    private val metaTagRegex = Regex("""<meta\s+[^>]*>""", RegexOption.IGNORE_CASE)
    private val propertyAttrRegex = Regex("""property\s*=\s*"([^"]*)"""")
    private val contentAttrRegex = Regex("""content\s*=\s*"([^"]*)"""")

    override suspend fun extract(url: String): ExtractionResult = withContext(Dispatchers.IO) {
        if (!pinUrlRegex.containsMatchIn(url)) {
            return@withContext ExtractionResult.Error(ExtractionError.INVALID_URL)
        }
        val pinId = pinIdRegex.find(url)?.groupValues?.get(1)

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

        // The pin id in the URL, re-extracted from wherever we actually landed (a pin.it
        // short link resolves to a full pinterest.com/pin/<id>/ URL by the time we get here).
        val resolvedPinId = pinId ?: pinIdRegex.find(resolvedUrl)?.groupValues?.get(1)

        parseHtml(html, resolvedUrl, resolvedPinId)
    }

    private fun parseHtml(html: String, sourceUrl: String, pinId: String?): ExtractionResult {
        if (html.contains("Sorry! We couldn't find that page")) {
            return ExtractionResult.Error(ExtractionError.NOT_FOUND_OR_PRIVATE)
        }

        val videoUrl = bestMp4UrlNearPin(html, pinId)
        val imageUrl = extractOrigImageFromEmbeddedJson(html)

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

    /**
     * Scans the raw HTML text for `"url":"...mp4..."` occurrences instead of parsing a
     * specific `<script>` tag's JSON — a pin page embeds dozens of *other* pins' data too
     * (related/recommended pins), so candidates are anchored to whichever one sits nearest
     * an occurrence of this pin's own numeric id, and ranked by an inferred resolution
     * (Pinterest's CDN paths usually embed it, e.g. ".../1080p/...") when several are
     * similarly close.
     */
    private fun bestMp4UrlNearPin(html: String, pinId: String?): String? {
        val candidates = rawMp4UrlRegex.findAll(html)
            .map { it.range.first to unescapeJson(it.groupValues[1]) }
            .distinctBy { it.second }
            .toList()
        if (candidates.isEmpty()) return null

        val anchors = if (pinId != null) {
            Regex(""""id"\s*:\s*"?$pinId"?""").findAll(html).map { it.range.first }.toList()
        } else {
            emptyList()
        }

        fun resolutionOf(url: String) = resolutionHintRegex.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        if (anchors.isEmpty()) {
            // No id to anchor to (or it wasn't found in the markup) — best-effort: highest inferred resolution.
            return candidates.maxByOrNull { resolutionOf(it.second) }?.second
        }

        val maxReasonableDistance = 20_000
        val nearby = candidates
            .map { (pos, url) -> Triple(pos, url, anchors.minOf { abs(it - pos) }) }
            .filter { it.third <= maxReasonableDistance }

        return if (nearby.isNotEmpty()) {
            nearby.minWithOrNull(compareBy({ it.third }, { -resolutionOf(it.second) }))?.second
        } else {
            candidates.maxByOrNull { resolutionOf(it.second) }?.second
        }
    }

    private fun extractOrigImageFromEmbeddedJson(html: String): String? {
        for (match in jsonScriptRegex.findAll(html)) {
            val root = try {
                JSONObject(match.groupValues[1])
            } catch (e: Exception) {
                continue
            }
            val imagesNode = deepFind(root) { key, value ->
                key == "images" && value is JSONObject && value.has("orig")
            } as? JSONObject
            val origUrl = imagesNode?.optJSONObject("orig")?.optString("url")?.takeIf { it.isNotBlank() }
            if (origUrl != null) return origUrl
        }
        return null
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
        val video = tags["og:video"] ?: tags["og:video:url"] ?: tags["og:video:secure_url"]
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

    private fun unescapeJson(value: String): String = value
        .replace("\\/", "/")
        .replace("\\u0026", "&")
}
