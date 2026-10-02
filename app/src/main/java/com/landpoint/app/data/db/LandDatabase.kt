package com.landpoint.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.landpoint.app.data.model.LandEntity
import com.landpoint.app.data.model.PhotoEntity

/**
 * 1 → 2: photos gain a nullable `corner_id` binding a photo to a stable
 * [CornerPoint.id]. Old photos keep NULL (= general land photo).
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE photos ADD COLUMN corner_id TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_corner_id ON photos(corner_id)")
    }
}

@Database(
    entities = [LandEntity::class, PhotoEntity::class],
    version = 2,
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

        private fun build(context: Context): LandDatabase {
            val app = context.applicationContext
            return Room.databaseBuilder(app, LandDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .apply {
                    // Null on a phone that cannot manage encryption at all, which
                    // leaves Room on its own opener rather than leaving the user
                    // unable to reach their own records. Settings reports which of
                    // the two happened.
                    DatabaseCipher.factoryFor(app, NAME)?.let(::openHelperFactory)
                }
                .build()
        }
    }
}
