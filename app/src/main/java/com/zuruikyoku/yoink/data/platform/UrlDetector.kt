package com.zuruikyoku.yoink.data.platform

data class DetectedLink(val url: String, val platform: Platform)

/**
 * Finds a supported post URL inside arbitrary text — either a bare URL (clipboard,
 * manual paste) or a share-sheet payload that mixes a caption with the link.
 */
object UrlDetector {

    private val URL_REGEX = Regex("""https?://\S+""")

    private val TWITTER_STATUS_REGEX = Regex(
        """https?://(?:www\.|mobile\.)?(?:twitter|x)\.com/[^/\s]+/status(?:es)?/\d+""",
        RegexOption.IGNORE_CASE
    )

    private val INSTAGRAM_REGEX = Regex(
        """https?://(?:www\.)?instagram\.com/(?:p|reel|reels|tv)/[A-Za-z0-9_-]+""",
        RegexOption.IGNORE_CASE
    )

    private val PINTEREST_PIN_REGEX = Regex(
        """https?://(?:[a-z]{2,3}\.)?pinterest\.[a-z.]+/pin/[A-Za-z0-9_-]+""",
        RegexOption.IGNORE_CASE
    )

    private val PINTEREST_SHORT_REGEX = Regex(
        """https?://pin\.it/[A-Za-z0-9]+""",
        RegexOption.IGNORE_CASE
    )

    fun detect(text: String?): DetectedLink? {
        if (text.isNullOrBlank()) return null

        TWITTER_STATUS_REGEX.find(text)?.let { return DetectedLink(it.value.trimTrailingPunctuation(), Platform.TWITTER) }
        INSTAGRAM_REGEX.find(text)?.let { return DetectedLink(it.value.trimTrailingPunctuation(), Platform.INSTAGRAM) }
        PINTEREST_PIN_REGEX.find(text)?.let { return DetectedLink(it.value.trimTrailingPunctuation(), Platform.PINTEREST) }
        PINTEREST_SHORT_REGEX.find(text)?.let { return DetectedLink(it.value.trimTrailingPunctuation(), Platform.PINTEREST) }

        // Fall back to classifying the first bare URL by host, in case the specific
        // path shape above didn't match (e.g. a platform tweaks its URL format).
        val firstUrl = URL_REGEX.find(text)?.value?.trimTrailingPunctuation() ?: return null
        return classifyByHost(firstUrl)?.let { DetectedLink(firstUrl, it) }
    }

    private fun classifyByHost(url: String): Platform? {
        val host = url.toHttpHostOrNull() ?: return null
        return when {
            host.endsWith("twitter.com") || host.endsWith("x.com") -> Platform.TWITTER
            host.endsWith("instagram.com") -> Platform.INSTAGRAM
            host.endsWith("pinterest.com") || host.startsWith("pinterest.") || host == "pin.it" -> Platform.PINTEREST
            else -> null
        }
    }

    private fun String.toHttpHostOrNull(): String? =
        runCatching { java.net.URI(this).host?.lowercase() }.getOrNull()

    private fun String.trimTrailingPunctuation(): String =
        trimEnd('.', ',', ')', ']', '}', '!', '?', '"', '\'')
}
