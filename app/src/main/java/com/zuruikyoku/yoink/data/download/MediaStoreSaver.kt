package com.zuruikyoku.yoink.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.zuruikyoku.yoink.data.extractor.MediaType
import com.zuruikyoku.yoink.data.extractor.isVideoLike
import com.zuruikyoku.yoink.data.platform.Platform
import com.zuruikyoku.yoink.data.settings.AppSettings
import java.io.File

/**
 * Creates and finalizes MediaStore entries under Pictures/ or Movies/, so downloaded
 * files show up in the gallery immediately. Handles both scoped storage (API 29+, via
 * RELATIVE_PATH + IS_PENDING) and the legacy direct-file-path approach required on
 * API 26-28.
 */
object MediaStoreSaver {

    data class PreparedTarget(
        val uri: Uri,
        val isScopedStorage: Boolean,
        val legacyFilePath: String?
    )

    fun openOutputTarget(
        context: Context,
        displayName: String,
        mimeType: String,
        mediaType: MediaType,
        platform: Platform,
        settings: AppSettings
    ): PreparedTarget? {
        val resolver = context.contentResolver
        val isVideoLike = mediaType.isVideoLike
        val collection = if (isVideoLike) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val baseDir = if (isVideoLike) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val folder = if (isVideoLike) settings.videoFolderName else settings.imageFolderName
        val subDir = if (settings.perPlatformSubfolders) "${platform.folderName}/" else ""

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        }

        val isScoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val legacyFilePath: String?

        if (isScoped) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/$folder/$subDir")
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            legacyFilePath = null
        } else {
            @Suppress("DEPRECATION")
            val publicDir = Environment.getExternalStoragePublicDirectory(baseDir)
            val targetDir = File(publicDir, "$folder/$subDir")
            if (!targetDir.exists() && !targetDir.mkdirs()) return null
            val file = File(targetDir, displayName)
            values.put(MediaStore.MediaColumns.DATA, file.absolutePath)
            legacyFilePath = file.absolutePath
        }

        val uri = resolver.insert(collection, values) ?: return null
        return PreparedTarget(uri, isScoped, legacyFilePath)
    }

    fun finalize(context: Context, target: PreparedTarget, mimeType: String) {
        if (target.isScopedStorage) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(target.uri, values, null, null)
        } else if (target.legacyFilePath != null) {
            MediaScannerConnection.scanFile(context, arrayOf(target.legacyFilePath), arrayOf(mimeType), null)
        }
    }

    fun abandon(context: Context, target: PreparedTarget) {
        runCatching { context.contentResolver.delete(target.uri, null, null) }
        target.legacyFilePath?.let { runCatching { File(it).delete() } }
    }
}
