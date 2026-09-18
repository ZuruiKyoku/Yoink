package com.zuruikyoku.yoink.util

import com.zuruikyoku.yoink.data.platform.Platform
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Shared OkHttp client used by every extractor and the downloader. */
object NetworkClient {

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(DefaultHeadersInterceptor())
            .build()
    }

    /** CDNs for these platforms hotlink-protect on Referer — used for both the real download and picker previews. */
    fun refererFor(platform: Platform): String = when (platform) {
        Platform.TWITTER -> "https://twitter.com/"
        Platform.INSTAGRAM -> "https://www.instagram.com/"
        Platform.PINTEREST -> "https://www.pinterest.com/"
    }

    private class DefaultHeadersInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val builder = original.newBuilder()
                .header("Accept-Language", "en-US,en;q=0.9")
            // A request can set its own User-Agent (e.g. InstagramExtractor's link-preview
            // UA) to get different server-side treatment; only fall back to the desktop one
            // when the caller didn't ask for something specific.
            if (original.header("User-Agent") == null) {
                builder.header("User-Agent", DESKTOP_USER_AGENT)
            }
            return chain.proceed(builder.build())
        }
    }
}
