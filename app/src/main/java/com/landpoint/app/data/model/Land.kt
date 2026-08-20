package com.landpoint.app.data.model

/** Domain model exposed to the UI layer. */
data class Land(
    val id: String,
    val name: String,
    val description: String,
    val notes: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val address: String?,
    val parcelNumber: String?,
    val areaSqm: Double?,
    val geometryType: String = GeometryType.POINT,
    val geometryJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val photos: List<Photo>,
    /** Filled in by the list layer when a current position is known. */
    val distanceMeters: Double? = null
) {
    /** Corners of the boundary, or empty when this land is a single point. */
    val boundary: List<com.landpoint.app.util.GeoPoint>
        get() = GeometryCodec.decode(geometryJson)

    val isPolygon: Boolean get() = geometryType == GeometryType.POLYGON
}

data class Photo(
    val id: String,
    val landId: String,
    val filePath: String,
    val caption: String,
    val createdAt: Long
)

fun LandWithPhotos.toDomain(): Land = Land(
    id = land.id,
    name = land.name,
    description = land.description,
    notes = land.notes,
    latitude = land.latitude,
    longitude = land.longitude,
    altitude = land.altitude,
    accuracy = land.accuracy,
    address = land.address,
    parcelNumber = land.parcelNumber,
    areaSqm = land.areaSqm,
    geometryType = land.geometryType,
    geometryJson = land.geometryJson,
    createdAt = land.createdAt,
    updatedAt = land.updatedAt,
    photos = photos.map { it.toDomain() }
)

fun PhotoEntity.toDomain(): Photo = Photo(
    id = id,
    landId = landId,
    filePath = filePath,
    caption = caption,
    createdAt = createdAt
)

fun Land.toEntity(): LandEntity = LandEntity(
    id = id,
    name = name,
    description = description,
    notes = notes,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    accuracy = accuracy,
    address = address,
    parcelNumber = parcelNumber,
    areaSqm = areaSqm,
    geometryType = geometryType,
    geometryJson = geometryJson,
    createdAt = createdAt,
    updatedAt = updatedAt
)
