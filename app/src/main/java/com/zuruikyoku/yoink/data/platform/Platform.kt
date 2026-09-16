package com.zuruikyoku.yoink.data.platform

/** Supported source platforms. Persisted by [name] in Room, so don't rename entries. */
enum class Platform(val displayName: String, val folderName: String) {
    TWITTER("Twitter/X", "Twitter"),
    INSTAGRAM("Instagram", "Instagram"),
    PINTEREST("Pinterest", "Pinterest")
}
