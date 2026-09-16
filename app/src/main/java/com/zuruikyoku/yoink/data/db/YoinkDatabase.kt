package com.zuruikyoku.yoink.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DownloadEntity::class], version = 1, exportSchema = false)
abstract class YoinkDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var instance: YoinkDatabase? = null

        fun getInstance(context: Context): YoinkDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    YoinkDatabase::class.java,
                    "yoink.db"
                ).build().also { instance = it }
            }
    }
}
