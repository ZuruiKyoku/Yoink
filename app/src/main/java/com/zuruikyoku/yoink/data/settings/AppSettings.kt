package com.zuruikyoku.yoink.data.settings

data class AppSettings(
    val imageFolderName: String = DEFAULT_FOLDER,
    val videoFolderName: String = DEFAULT_FOLDER,
    val perPlatformSubfolders: Boolean = false,
    val autoClipboardDetect: Boolean = true
) {
    companion object {
        const val DEFAULT_FOLDER = "Yoink"
    }
}
