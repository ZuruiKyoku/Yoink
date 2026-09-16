package com.zuruikyoku.yoink.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUrl: String,
    /** [com.zuruikyoku.yoink.data.platform.Platform.name] */
    val platform: String,
    /** [com.zuruikyoku.yoink.data.extractor.MediaType.name] */
    val mediaType: String,
    /** content:// MediaStore URI of the saved file — used to open it and to render a thumbnail. */
    val mediaUri: String,
    val timestamp: Long
)
