package com.landpoint.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.landpoint.app.data.model.LandEntity
import com.landpoint.app.data.model.PhotoEntity

@Database(
    entities = [LandEntity::class, PhotoEntity::class],
    version = 1,
    exportSchema = true
)
abstract class LandDatabase : RoomDatabase() {

    abstract fun landDao(): LandDao

    companion object {
        private const val NAME = "landpoint.db"

        @Volatile
        private var instance: LandDatabase? = null

        fun get(context: Context): LandDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): LandDatabase =
            Room.databaseBuilder(context.applicationContext, LandDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
