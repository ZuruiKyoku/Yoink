package com.zuruikyoku.yoink.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DownloadEntity::class], version = 2, exportSchema = false)
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
                )
                    // No released migration path yet for this single-user local history table —
                    // a schema bump just wipes and recreates it rather than carrying a Migration.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
