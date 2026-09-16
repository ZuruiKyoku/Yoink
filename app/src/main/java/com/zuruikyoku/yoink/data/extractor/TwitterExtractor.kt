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
 * Pulls media from Twitter/X posts via the public syndication endpoint used to render
 * tweet embeds (cdn.syndication.twimg.com). No login required, but the endpoint gates
 * requests behind a lightly-obfuscated `token` derived from the tweet id — see
 * [syndicationToken]. If Twitter changes that scheme this extractor breaks in isolation;
 * Instagram/Pinterest are unaffected.
 */
class TwitterExtractor : MediaExtractor {

    override val platform = Platform.TWITTER

    private val statusIdRegex = Regex("""status(?:es)?/(\d+)""")

    override suspend fun extract(url: String): ExtractionResult = withContext(Dispatchers.IO) {
        val tweetId = statusIdRegex.find(url)?.groupValues?.get(1)
            ?: return@withContext ExtractionResult.Error(ExtractionError.INVALID_URL)

        val apiUrl = "https://cdn.syndication.twimg.com/tweet-result" +
            "?id=$tweetId&token=${syndicationToken(tweetId)}&lang=en"

        val request = Request.Builder().url(apiUrl).build()

        val body = try {
            NetworkClient.client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 || response.code == 403 ->
                        return@withContext ExtractionResult.Error(ExtractionError.NOT_FOUND_OR_PRIVATE)
                    !response.isSuccessful ->
                        return@withContext ExtractionResult.Error(ExtractionError.NETWORK_ERROR)
                    else -> response.body?.string()
                }
            }
        } catch (e: IOException) {
            return@withContext ExtractionResult.Error(ExtractionError.NETWORK_ERROR, e)
        }

        if (body.isNullOrBlank()) {
            return@withContext ExtractionResult.Error(ExtractionError.NOT_FOUND_OR_PRIVATE)
        }

        parseTweetResult(body, url)
    }

    private fun parseTweetResult(body: String, sourceUrl: String): ExtractionResult {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return ExtractionResult.Error(ExtractionError.UNKNOWN, e)
        }

        // A tombstone/error payload instead of tweet data means deleted/protected/age-gated.
        if (json.has("__typename") && json.optString("__typename") == "TweetTombstone") {
            return ExtractionResult.Error(ExtractionError.NOT_FOUND_OR_PRIVATE)
        }

        val mediaDetails: JSONArray? = json.optJSONArray("mediaDetails")
        if (mediaDetails == null || mediaDetails.length() == 0) {
            return ExtractionResult.Error(ExtractionError.NO_MEDIA_FOUND)
        }

        // A multi-photo tweet lists every item here — build one ExtractedMedia per item and
        // let the caller decide whether to prompt (a bad item is skipped, not fatal).
        val items = (0 until mediaDetails.length()).mapNotNull { i ->
            itemToMedia(mediaDetails.getJSONObject(i), sourceUrl)
        }

        if (items.isEmpty()) return ExtractionResult.Error(ExtractionError.NO_MEDIA_FOUND)
        return ExtractionResult.Success(items)
    }

    private fun itemToMedia(item: JSONObject, sourceUrl: String): ExtractedMedia? = when (item.optString("type")) {
        "photo" -> {
            val rawUrl = item.optString("media_url_https").takeIf { it.isNotBlank() }
            rawUrl?.let {
                ExtractedMedia(
                    mediaUrl = highestQualityPhotoUrl(it),
                    mediaType = MediaType.IMAGE,
                    platform = platform,
                    sourceUrl = sourceUrl
                )
            }
        }

        "video", "animated_gif" -> {
            bestMp4Variant(item.optJSONObject("video_info"))?.let { variant ->
                ExtractedMedia(
                    mediaUrl = variant,
                    mediaType = if (item.optString("type") == "animated_gif") MediaType.GIF else MediaType.VIDEO,
                    platform = platform,
                    sourceUrl = sourceUrl,
                    thumbnailUrl = item.optString("media_url_https").takeIf { it.isNotBlank() }
                )
            }
        }

        else -> null
    }

    private fun highestQualityPhotoUrl(rawUrl: String): String {
        val base = rawUrl.substringBefore('?')
        return "$base?format=jpg&name=orig"
    }

    private fun bestMp4Variant(videoInfo: JSONObject?): String? {
        val variants = videoInfo?.optJSONArray("variants") ?: return null
        var bestUrl: String? = null
        var bestBitrate = -1
        for (i in 0 until variants.length()) {
            val variant = variants.getJSONObject(i)
            if (variant.optString("content_type") != "video/mp4") continue
            val bitrate = variant.optInt("bitrate", -1)
            val variantUrl = variant.optString("url").takeIf { it.isNotBlank() } ?: continue
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                bestUrl = variantUrl
            }
        }
        return bestUrl
    }

    /**
     * Reverse-engineered token scheme the syndication widget's JS uses to gate
     * `tweet-result` requests: base36(tweetId / 1e15 * PI) with zeros and the
     * decimal point stripped. It isn't cryptographically verified server-side —
     * it just has to look plausible — so an exact bit-for-bit match with the real
     * client isn't required, only the same shape.
     */
    private fun syndicationToken(tweetId: String): String {
        val value = (tweetId.toDouble() / 1e15) * Math.PI
        return doubleToBase36(value).replace(Regex("[0.]"), "")
    }

    private fun doubleToBase36(value: Double): String {
        val intPart = value.toLong()
        var frac = value - intPart
        val intStr = intPart.toString(36)
        val fracBuilder = StringBuilder()
        repeat(16) {
            frac *= 36
            val digit = frac.toInt().coerceIn(0, 35)
            fracBuilder.append(digit.toString(36))
            frac -= digit
        }
        val fracStr = fracBuilder.toString().trimEnd('0')
        return if (fracStr.isEmpty()) intStr else "$intStr.$fracStr"
    }
}
