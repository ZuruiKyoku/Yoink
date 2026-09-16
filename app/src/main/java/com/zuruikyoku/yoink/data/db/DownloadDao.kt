package com.zuruikyoku.yoink.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Query("SELECT * FROM downloads ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Delete
    suspend fun delete(entity: DownloadEntity)
}
