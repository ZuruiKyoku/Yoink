package com.zuruikyoku.yoink.data.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zuruikyoku.yoink.data.db.DownloadEntity
import com.zuruikyoku.yoink.data.db.YoinkDatabase
import com.zuruikyoku.yoink.data.extractor.ExtractedMedia
import com.zuruikyoku.yoink.data.extractor.ExtractionError
import com.zuruikyoku.yoink.data.extractor.ExtractionResult
import com.zuruikyoku.yoink.data.extractor.ExtractorRegistry
import com.zuruikyoku.yoink.data.extractor.MediaType
import com.zuruikyoku.yoink.data.extractor.isVideoLike
import com.zuruikyoku.yoink.data.platform.Platform
import com.zuruikyoku.yoink.data.settings.SettingsRepository
import com.zuruikyoku.yoink.util.NetworkClient
import okhttp3.Request
import java.io.IOException

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sourceUrl = inputData.getString(KEY_SOURCE_URL)
        val platform = inputData.getString(KEY_PLATFORM)?.let { runCatching { Platform.valueOf(it) }.getOrNull() }
        if (sourceUrl.isNullOrBlank() || platform == null) {
            return Result.failure(errorData(ExtractionError.INVALID_URL))
        }

        setForeground(createForegroundInfo(0, indeterminate = true))

        val media = when (val extraction = ExtractorRegistry.forPlatform(platform).extract(sourceUrl)) {
            is ExtractionResult.Error -> {
                DownloadNotifier.showResultNotification(applicationContext, resultNotificationId(), success = false)
                return Result.failure(errorData(extraction.reason))
            }
            is ExtractionResult.Success -> extraction.media
        }

        val settings = SettingsRepository(applicationContext).currentSettings()
        val mimeType = mimeTypeFor(media)
        val extension = extensionFor(mimeType)
        val displayName = "yoink_${media.platform.folderName.lowercase()}_${System.currentTimeMillis()}.$extension"

        val target = MediaStoreSaver.openOutputTarget(
            context = applicationContext,
            displayName = displayName,
            mimeType = mimeType,
            mediaType = media.mediaType,
            platform = media.platform,
            settings = settings
        ) ?: run {
            DownloadNotifier.showResultNotification(applicationContext, resultNotificationId(), success = false)
            return Result.failure(errorData(ExtractionError.UNKNOWN))
        }

        return try {
            downloadTo(target.uri, media)
            MediaStoreSaver.finalize(applicationContext, target, mimeType)

            YoinkDatabase.getInstance(applicationContext).downloadDao().insert(
                DownloadEntity(
                    sourceUrl = media.sourceUrl,
                    platform = media.platform.name,
                    mediaType = media.mediaType.name,
                    mediaUri = target.uri.toString(),
                    timestamp = System.currentTimeMillis(),
                    isPartialCarousel = media.isPartialCarousel
                )
            )

            DownloadNotifier.showResultNotification(applicationContext, resultNotificationId(), success = true)
            Result.success(workDataOf(KEY_MEDIA_URI to target.uri.toString(), KEY_IS_PARTIAL_CAROUSEL to media.isPartialCarousel))
        } catch (e: IOException) {
            MediaStoreSaver.abandon(applicationContext, target)
            DownloadNotifier.showResultNotification(applicationContext, resultNotificationId(), success = false)
            Result.failure(errorData(ExtractionError.NETWORK_ERROR))
        } catch (e: Exception) {
            MediaStoreSaver.abandon(applicationContext, target)
            DownloadNotifier.showResultNotification(applicationContext, resultNotificationId(), success = false)
            Result.failure(errorData(ExtractionError.UNKNOWN))
        }
    }

    private suspend fun downloadTo(uri: Uri, media: ExtractedMedia) {
        val request = Request.Builder()
            .url(media.mediaUrl)
            .header("Referer", refererFor(media.platform))
            .build()

        NetworkClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response body")
            val total = body.contentLength()

            val output = applicationContext.contentResolver.openOutputStream(uri)
                ?: throw IOException("Could not open output stream")

            output.use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesCopied = 0L
                    var lastReportedPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        bytesCopied += read
                        if (total > 0) {
                            val percent = ((bytesCopied * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                setProgress(workDataOf(KEY_PROGRESS_PERCENT to percent))
                                setForeground(createForegroundInfo(percent, indeterminate = false))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createForegroundInfo(percent: Int, indeterminate: Boolean): ForegroundInfo {
        val notification: Notification = DownloadNotifier.progressNotification(applicationContext, percent, indeterminate)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                DownloadNotifier.PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(DownloadNotifier.PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun resultNotificationId(): Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

    private fun errorData(reason: ExtractionError): Data = workDataOf(KEY_ERROR_REASON to reason.name)

    private fun refererFor(platform: Platform): String = when (platform) {
        Platform.TWITTER -> "https://twitter.com/"
        Platform.INSTAGRAM -> "https://www.instagram.com/"
        Platform.PINTEREST -> "https://www.pinterest.com/"
    }

    private fun mimeTypeFor(media: ExtractedMedia): String {
        if (media.mediaType.isVideoLike) return "video/mp4"
        val path = media.mediaUrl.substringBefore('?').lowercase()
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "video/mp4" -> "mp4"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    companion object {
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_PLATFORM = "platform"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_ERROR_REASON = "error_reason"
        const val KEY_MEDIA_URI = "media_uri"
        const val KEY_IS_PARTIAL_CAROUSEL = "is_partial_carousel"
        private const val BUFFER_SIZE = 8 * 1024

        fun buildRequest(sourceUrl: String, platform: Platform): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(DownloadWorker::class.java)
                .setInputData(
                    workDataOf(
                        KEY_SOURCE_URL to sourceUrl,
                        KEY_PLATFORM to platform.name
                    )
                )
                .build()
    }
}
