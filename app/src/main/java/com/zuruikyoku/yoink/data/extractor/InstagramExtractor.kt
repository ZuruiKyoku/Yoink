package com.zuruikyoku.yoink.data.extractor

import com.zuruikyoku.yoink.data.platform.Platform
import com.zuruikyoku.yoink.util.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Pulls media from public Instagram posts/reels/carousels. Verified live (Sept 2026): a plain
 * browser User-Agent now gets an empty React shell with no OG tags and no embedded post JSON
 * at all, regardless of headers — the `?__a=1&__d=dis` pseudo-API is fully dead (500/404).
 * The one thing that still works anonymously is that Instagram must keep serving real Open
 * Graph tags to known link-preview crawlers (WhatsApp, iMessage, etc.) since its own ecosystem
 * depends on those unfurling correctly — spoofing that UA for the HTML fetch is what actually
 * unlocks real data. That edge also occasionally 302s with an empty body for no discernible
 * reason (observed even on identical back-to-back requests) and succeeds on a bare retry, so
 * [fetchHtml] retries once.
 *
 * This only recovers a single cover image per post (real Open Graph `og:video` tags are gone
 * too) — a carousel or Reel yields just its first-slide/cover photo, not the full set or the
 * actual video. There's no known anonymous-HTTP path to the real carousel list or video URL
 * anymore; that would need a logged-in session or JS execution, both out of scope here.
 *
 * Tries a few independent approaches in order and takes whichever one turns up usable data
 * first:
 *
 * 1. The `?__a=1&__d=dis` pseudo-API response, with the `X-IG-App-ID` header Instagram's own
 *    web client sends — a long-standing, widely-documented public web client id, not a secret.
 *    Kept in case Instagram re-enables it; currently always falls through.
 * 2. The post page's embedded JSON state (which, for a carousel, lists every slide under
 *    `edge_sidecar_to_children` in the older shape, or `carousel_media` in the newer one).
 *    Kept for the same reason; currently always falls through too.
 * 3. The Open Graph tags Instagram server-renders for link previews — only ever the first item,
 *    and image-only. This is the one that actually returns data today.
 *
 * Needs no login and no JS execution. Breaks independently of the Twitter/Pinterest
 * extractors if Instagram changes its markup or blocks anonymous requests harder.
 */
class InstagramExtractor : MediaExtractor {

    override val platform = Platform.INSTAGRAM

    // Instagram's own website sends this as its web client id on internal API calls; it's a
    // long-lived public constant referenced across many independent tools, not a real secret.
    private val webAppIdHeader = "X-IG-App-ID" to "936619743392459"

    // Confirmed live: a normal desktop/mobile browser UA gets stonewalled with an empty app
    // shell, but Instagram still renders real Open Graph tags for this one.
    private val linkPreviewUserAgent = "WhatsApp/2.23.20.0"

    private companion object {
        const val MAX_HTML_FETCH_ATTEMPTS = 4
    }

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

        // Best-effort first try; any failure here just falls through to the HTML fetch below,
        // which is the one that gets to report a real network error / not-found.
        fetchViaPseudoApi(url)?.let { return@withContext it }

        val html = when (val fetch = fetchHtml(url)) {
            is HtmlFetch.Failed -> return@withContext ExtractionResult.Error(fetch.reason)
            is HtmlFetch.Success -> fetch.body
        }

        parseHtml(html, url)?.let { return@withContext it }
        ExtractionResult.Error(ExtractionError.NO_MEDIA_FOUND)
    }

    private sealed class HtmlFetch {
        data class Success(val body: String) : HtmlFetch()
        data class Failed(val reason: ExtractionError) : HtmlFetch()
    }

    /** Attempt 1: the `?__a=1&__d=dis` JSON response. Returns null (not a hard error) on any failure so the caller falls through to the HTML approach. */
    private fun fetchViaPseudoApi(url: String): ExtractionResult? {
        val base = url.substringBefore('?').trimEnd('/')
        val apiUrl = "$base/?__a=1&__d=dis"

        val request = Request.Builder()
            .url(apiUrl)
            .header(webAppIdHeader.first, webAppIdHeader.second)
            .header("User-Agent", linkPreviewUserAgent)
            .header("Accept", "*/*")
            .build()

        val body = try {
            NetworkClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (e: IOException) {
            return null
        }

        if (body.isNullOrBlank()) return null

        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }

        val items = mediaFromRoot(root, url)
        return if (items.isNullOrEmpty()) null else ExtractionResult.Success(items)
    }

    private suspend fun fetchHtml(url: String): HtmlFetch {
        // Observed live: this edge fails with an empty-body, non-2xx response for no
        // discernible reason on a large, measured fraction of requests (~40-50% in testing,
        // regardless of which post or which link-preview UA) - a single retry only halves
        // that, leaving a user-visible failure rate a real person will still hit. Retries a
        // few times with backoff before giving up for good.
        var lastFailure: HtmlFetch.Failed? = null
        for (attempt in 0 until MAX_HTML_FETCH_ATTEMPTS) {
            if (attempt > 0) delay(attempt * 500L)
            val result = fetchHtmlOnce(url)
            if (result is HtmlFetch.Success) return result
            result as HtmlFetch.Failed
            if (result.reason != ExtractionError.NETWORK_ERROR) return result
            lastFailure = result
        }
        return lastFailure ?: HtmlFetch.Failed(ExtractionError.NETWORK_ERROR)
    }

    private fun fetchHtmlOnce(url: String): HtmlFetch {
        val request = Request.Builder()
            .url(url)
            .header(webAppIdHeader.first, webAppIdHeader.second)
            .header("User-Agent", linkPreviewUserAgent)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        return try {
            NetworkClient.client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> HtmlFetch.Failed(ExtractionError.NOT_FOUND_OR_PRIVATE)
                    !response.isSuccessful -> HtmlFetch.Failed(ExtractionError.NETWORK_ERROR)
                    else -> {
                        val body = response.body?.string()
                        if (body.isNullOrBlank()) {
                            HtmlFetch.Failed(ExtractionError.NOT_FOUND_OR_PRIVATE)
                        } else {
                            HtmlFetch.Success(body)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            HtmlFetch.Failed(ExtractionError.NETWORK_ERROR)
        }
    }

    private fun parseHtml(html: String, sourceUrl: String): ExtractionResult? {
        if (html.contains("Sorry, this page isn't available") ||
            html.contains("This account is private") ||
            html.contains("Log in to see photos")
        ) {
            return ExtractionResult.Error(ExtractionError.NOT_FOUND_OR_PRIVATE)
        }

        for (match in jsonScriptRegex.findAll(html)) {
            val root = try {
                JSONObject(match.groupValues[1])
            } catch (e: Exception) {
                continue
            }
            mediaFromRoot(root, sourceUrl)?.let { return ExtractionResult.Success(it) }
        }

        val single = extractFromOgTags(html, sourceUrl) ?: return null
        return ExtractionResult.Success(listOf(single))
    }

    /**
     * Tries every media shape we know Instagram has used, anywhere in [root]: the older
     * GraphQL `edge_sidecar_to_children` carousel, the newer mobile-API `carousel_media`
     * array, and a handful of single-item field layouts. Returns null if none matched.
     */
    private fun mediaFromRoot(root: JSONObject, sourceUrl: String): List<ExtractedMedia>? {
        (deepFind(root) { key, value ->
            key == "edge_sidecar_to_children" && value is JSONObject && value.has("edges")
        } as? JSONObject)?.optJSONArray("edges")?.let { edges ->
            val items = (0 until edges.length()).mapNotNull { i ->
                edges.optJSONObject(i)?.optJSONObject("node")?.let { sidecarNodeToMedia(it, sourceUrl) }
            }
            if (items.isNotEmpty()) return items
        }

        (deepFind(root) { key, value -> key == "carousel_media" && value is JSONArray } as? JSONArray)?.let { carousel ->
            val items = (0 until carousel.length()).mapNotNull { i ->
                carousel.optJSONObject(i)?.let { apiItemToMedia(it, sourceUrl) }
            }
            if (items.isNotEmpty()) return items
        }

        val singleCandidates = listOfNotNull(
            root.optJSONArray("items")?.optJSONObject(0),
            deepFind(root) { key, _ -> key == "shortcode_media" } as? JSONObject,
            root
        )
        for (candidate in singleCandidates) {
            apiItemToMedia(candidate, sourceUrl)?.let { return listOf(it) }
            sidecarNodeToMedia(candidate, sourceUrl)?.let { return listOf(it) }
        }

        return null
    }

    /** Older GraphQL shape: display_url / video_url / is_video directly on the node. */
    private fun sidecarNodeToMedia(node: JSONObject, sourceUrl: String): ExtractedMedia? {
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

    /** Newer mobile-API shape: image_versions2.candidates[0].url / video_versions[0].url. */
    private fun apiItemToMedia(item: JSONObject, sourceUrl: String): ExtractedMedia? {
        val imageUrl = item.optJSONObject("image_versions2")
            ?.optJSONArray("candidates")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
        val videoUrl = item.optJSONArray("video_versions")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }

        return when {
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
            else -> null
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
