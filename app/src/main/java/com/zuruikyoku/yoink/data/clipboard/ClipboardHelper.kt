package com.zuruikyoku.yoink.data.clipboard

import android.content.ClipboardManager
import android.content.Context

class ClipboardHelper(context: Context) {

    private val appContext = context.applicationContext
    private val clipboardManager =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * Reads the current clipboard text, if any. Must be called while the app is in the
     * foreground — Android 10+ blocks clipboard reads from background apps.
     */
    fun readText(): String? {
        val clip = clipboardManager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(appContext)?.toString()
    }
}
