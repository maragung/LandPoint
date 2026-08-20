package com.landpoint.app.data.model

import androidx.room.Embedded
import androidx.room.Relation

data class LandWithPhotos(
    @Embedded val land: LandEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "land_id"
    )
    val photos: List<PhotoEntity> = emptyList()
)
