package com.landpoint.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Land location entity.
 *
 * Future-proof schema:
 * - geometryType + geometryJson allow polygon boundaries (roadmap item)
 * - parcelNumber for official land parcel IDs (roadmap item)
 * - areaSqm for calculated area when polygon is drawn (roadmap item)
 *
 * MVP stores single point (geometryType="Point", geometryJson=null).
 */
@Entity(
    tableName = "lands",
    indices = [Index(value = ["created_at"])]
)
data class LandEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String = "",

    @ColumnInfo(name = "notes")
    val notes: String = "",

    @ColumnInfo(name = "latitude")
    val latitude: Double,

    @ColumnInfo(name = "longitude")
    val longitude: Double,

    @ColumnInfo(name = "altitude")
    val altitude: Double? = null,

    @ColumnInfo(name = "accuracy")
    val accuracy: Float? = null,

    @ColumnInfo(name = "address")
    val address: String? = null,

    @ColumnInfo(name = "geometry_type")
    val geometryType: String = "Point",

    @ColumnInfo(name = "geometry_json")
    val geometryJson: String? = null,

    @ColumnInfo(name = "parcel_number")
    val parcelNumber: String? = null,

    @ColumnInfo(name = "area_sqm")
    val areaSqm: Double? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
