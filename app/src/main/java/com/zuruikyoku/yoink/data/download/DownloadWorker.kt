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
import com.zuruikyoku.yoink.data.extractor.MediaType
import com.zuruikyoku.yoink.data.extractor.isVideoLike
import com.zuruikyoku.yoink.data.platform.Platform
import com.zuruikyoku.yoink.data.settings.SettingsRepository
import com.zuruikyoku.yoink.util.NetworkClient
import okhttp3.Request
import java.io.IOException

/**
 * Downloads and saves ONE already-resolved media item. Extraction (and, for a carousel,
 * letting the user pick which item) happens beforehand in MainViewModel — this worker
 * never talks to a platform's extractor, only to the CDN URL it's handed.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val mediaUrl = inputData.getString(KEY_MEDIA_URL)
        val mediaType = inputData.getString(KEY_MEDIA_TYPE)?.let { runCatching { MediaType.valueOf(it) }.getOrNull() }
        val platform = inputData.getString(KEY_PLATFORM)?.let { runCatching { Platform.valueOf(it) }.getOrNull() }
        val sourceUrl = inputData.getString(KEY_SOURCE_URL)
        if (mediaUrl.isNullOrBlank() || mediaType == null || platform == null || sourceUrl.isNullOrBlank()) {
            return Result.failure(errorData(ExtractionError.UNKNOWN))
        }
        val queueIndex = inputData.getInt(KEY_QUEUE_INDEX, 1)
        val queueTotal = inputData.getInt(KEY_QUEUE_TOTAL, 1)

        val media = ExtractedMedia(
            mediaUrl = mediaUrl,
            mediaType = mediaType,
            platform = platform,
            sourceUrl = sourceUrl,
            thumbnailUrl = inputData.getString(KEY_THUMBNAIL_URL)
        )

        setForeground(createForegroundInfo(0, indeterminate = true, queueIndex, queueTotal))

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
            downloadTo(target.uri, media, queueIndex, queueTotal)
            MediaStoreSaver.finalize(applicationContext, target, mimeType)

            YoinkDatabase.getInstance(applicationContext).downloadDao().insert(
                DownloadEntity(
                    sourceUrl = media.sourceUrl,
                    platform = media.platform.name,
                    mediaType = media.mediaType.name,
                    mediaUri = target.uri.toString(),
                    timestamp = System.currentTimeMillis()
                )
            )

            DownloadNotifier.showResultNotification(applicationContext, resultNotificationId(), success = true)
            Result.success(workDataOf(KEY_MEDIA_URI to target.uri.toString()))
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

    private suspend fun downloadTo(uri: Uri, media: ExtractedMedia, queueIndex: Int, queueTotal: Int) {
        val request = Request.Builder()
            .url(media.mediaUrl)
            .header("Referer", NetworkClient.refererFor(media.platform))
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
                                setForeground(createForegroundInfo(percent, indeterminate = false, queueIndex, queueTotal))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createForegroundInfo(percent: Int, indeterminate: Boolean, queueIndex: Int, queueTotal: Int): ForegroundInfo {
        val notification: Notification =
            DownloadNotifier.progressNotification(applicationContext, percent, indeterminate, queueIndex, queueTotal)
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
        const val KEY_MEDIA_URL = "media_url"
        const val KEY_MEDIA_TYPE = "media_type"
        const val KEY_PLATFORM = "platform"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_THUMBNAIL_URL = "thumbnail_url"
        const val KEY_QUEUE_INDEX = "queue_index"
        const val KEY_QUEUE_TOTAL = "queue_total"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_ERROR_REASON = "error_reason"
        const val KEY_MEDIA_URI = "media_uri"
        private const val BUFFER_SIZE = 8 * 1024

        fun buildRequest(media: ExtractedMedia, queueIndex: Int = 1, queueTotal: Int = 1): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(DownloadWorker::class.java)
                .setInputData(
                    workDataOf(
                        KEY_MEDIA_URL to media.mediaUrl,
                        KEY_MEDIA_TYPE to media.mediaType.name,
                        KEY_PLATFORM to media.platform.name,
                        KEY_SOURCE_URL to media.sourceUrl,
                        KEY_THUMBNAIL_URL to media.thumbnailUrl,
                        KEY_QUEUE_INDEX to queueIndex,
                        KEY_QUEUE_TOTAL to queueTotal
                    )
                )
                .build()
    }
}
