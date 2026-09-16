package com.zuruikyoku.yoink.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "yoink_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val IMAGE_FOLDER = stringPreferencesKey("image_folder")
        val VIDEO_FOLDER = stringPreferencesKey("video_folder")
        val PER_PLATFORM_SUBFOLDERS = booleanPreferencesKey("per_platform_subfolders")
        val AUTO_CLIPBOARD = booleanPreferencesKey("auto_clipboard_detect")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            imageFolderName = prefs[Keys.IMAGE_FOLDER] ?: AppSettings.DEFAULT_FOLDER,
            videoFolderName = prefs[Keys.VIDEO_FOLDER] ?: AppSettings.DEFAULT_FOLDER,
            perPlatformSubfolders = prefs[Keys.PER_PLATFORM_SUBFOLDERS] ?: false,
            autoClipboardDetect = prefs[Keys.AUTO_CLIPBOARD] ?: true
        )
    }

    /** One-shot read, for callers that don't want to keep collecting (e.g. the download worker). */
    suspend fun currentSettings(): AppSettings = settingsFlow.first()

    suspend fun setImageFolder(name: String) {
        val safeName = name.ifBlank { AppSettings.DEFAULT_FOLDER }
        context.dataStore.edit { it[Keys.IMAGE_FOLDER] = safeName }
    }

    suspend fun setVideoFolder(name: String) {
        val safeName = name.ifBlank { AppSettings.DEFAULT_FOLDER }
        context.dataStore.edit { it[Keys.VIDEO_FOLDER] = safeName }
    }

    suspend fun setPerPlatformSubfolders(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PER_PLATFORM_SUBFOLDERS] = enabled }
    }

    suspend fun setAutoClipboardDetect(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_CLIPBOARD] = enabled }
    }
}
