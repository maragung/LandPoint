package com.landpoint.app.data

import com.landpoint.app.data.db.LandDao
import com.landpoint.app.data.model.Land
import com.landpoint.app.data.model.LandEntity
import com.landpoint.app.data.model.Photo
import com.landpoint.app.data.model.PhotoEntity
import com.landpoint.app.data.model.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LandRepository(
    private val dao: LandDao,
    private val photoStore: PhotoStore
) {

    fun observeLands(): Flow<List<Land>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeLand(id: String): Flow<Land?> =
        dao.observeById(id).map { it?.toDomain() }

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun getLand(id: String): Land? = dao.getById(id)?.toDomain()

    suspend fun getAllLands(): List<Land> = dao.getAllOnce().map { it.toDomain() }

    suspend fun save(land: LandEntity) = dao.upsert(land)

    suspend fun update(land: LandEntity) = dao.update(land.copy(updatedAt = System.currentTimeMillis()))

    /** Deletes the land row plus every photo file it owned. */
    suspend fun delete(id: String) {
        dao.getPhotosFor(id).forEach { photoStore.delete(it.filePath) }
        dao.deleteById(id)
    }

    suspend fun deleteMany(ids: List<String>) {
        ids.forEach { id -> dao.getPhotosFor(id).forEach { photoStore.delete(it.filePath) } }
        dao.deleteByIds(ids)
    }

    suspend fun deleteAll() {
        dao.getAllPhotos().forEach { photoStore.delete(it.filePath) }
        dao.deleteAll()
    }

    suspend fun addPhoto(landId: String, filePath: String, caption: String = "") {
        dao.insertPhoto(PhotoEntity(landId = landId, filePath = filePath, caption = caption))
    }

    suspend fun setPhotoCaption(photoId: String, caption: String) {
        dao.updatePhotoCaption(photoId, caption)
    }

    suspend fun removePhoto(photo: Photo) {
        photoStore.delete(photo.filePath)
        dao.deletePhotoById(photo.id)
    }

    suspend fun removePhotoById(photoId: String, filePath: String) {
        photoStore.delete(filePath)
        dao.deletePhotoById(photoId)
    }

    suspend fun findExistingIds(ids: List<String>): Set<String> =
        if (ids.isEmpty()) emptySet() else dao.findExistingIds(ids).toSet()

    suspend fun findDuplicateByCoordinates(name: String, lat: Double, lon: Double): LandEntity? =
        dao.findByNameAndCoordinates(name, lat, lon)

    suspend fun upsertAll(lands: List<LandEntity>) = dao.upsertAll(lands)

    /** Every photo path currently referenced by a land, used by restore cleanup. */
    suspend fun allPhotoPaths(): Set<String> = dao.getAllPhotos().map { it.filePath }.toSet()
}
