package com.landpoint.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = LandEntity::class,
            parentColumns = ["id"],
            childColumns = ["land_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["land_id"])]
)
data class PhotoEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "land_id")
    val landId: String,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "caption")
    val caption: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
