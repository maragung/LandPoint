package com.landpoint.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.landpoint.app.data.model.LandEntity
import com.landpoint.app.data.model.LandWithPhotos
import com.landpoint.app.data.model.PhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LandDao {

    @Transaction
    @Query("SELECT * FROM lands ORDER BY created_at DESC")
    fun observeAll(): Flow<List<LandWithPhotos>>

    @Transaction
    @Query("SELECT * FROM lands WHERE id = :id")
    fun observeById(id: String): Flow<LandWithPhotos?>

    @Transaction
    @Query("SELECT * FROM lands WHERE id = :id")
    suspend fun getById(id: String): LandWithPhotos?

    @Transaction
    @Query("SELECT * FROM lands")
    suspend fun getAllOnce(): List<LandWithPhotos>

    @Query("SELECT COUNT(*) FROM lands")
    fun observeCount(): Flow<Int>

    /** Used by import to detect duplicates that carry the same stable id. */
    @Query("SELECT id FROM lands WHERE id IN (:ids)")
    suspend fun findExistingIds(ids: List<String>): List<String>

    /**
     * Coordinate-based duplicate detection for imports of files that have no
     * stable id (e.g. hand-made CSV). Compares to ~1e-5 deg (about 1 m).
     */
    @Query(
        """
        SELECT * FROM lands
        WHERE ABS(latitude - :lat) < 0.00001 AND ABS(longitude - :lon) < 0.00001
        LIMIT 1
        """
    )
    suspend fun findByCoordinates(lat: Double, lon: Double): LandEntity?

    @Query("SELECT * FROM lands WHERE name = :name AND ABS(latitude - :lat) < 0.00001 AND ABS(longitude - :lon) < 0.00001 LIMIT 1")
    suspend fun findByNameAndCoordinates(name: String, lat: Double, lon: Double): LandEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(land: LandEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(land: LandEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(lands: List<LandEntity>)

    @Update
    suspend fun update(land: LandEntity)

    @Delete
    suspend fun delete(land: LandEntity)

    @Query("DELETE FROM lands WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM lands WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM lands")
    suspend fun deleteAll()

    // ---- photos ----

    @Query("SELECT * FROM photos WHERE land_id = :landId ORDER BY created_at ASC")
    suspend fun getPhotosFor(landId: String): List<PhotoEntity>

    @Query("SELECT * FROM photos")
    suspend fun getAllPhotos(): List<PhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: PhotoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhotos(photos: List<PhotoEntity>)

    /**
     * Writes a caption onto a photo that is already stored.
     *
     * Only the one column: re-inserting the row would mean carrying its path and
     * timestamp through the UI just to hand them back unchanged.
     */
    @Query("UPDATE photos SET caption = :caption WHERE id = :id")
    suspend fun updatePhotoCaption(id: String, caption: String)

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deletePhotoById(id: String)

    @Query("DELETE FROM photos WHERE land_id = :landId")
    suspend fun deletePhotosFor(landId: String)
}
