package com.landpoint.app.data.export

import com.landpoint.app.data.model.Land
import java.util.UUID

/** Builds domain objects for tests without repeating every optional field. */
object LandTestFactory {

    fun land(
        id: String = UUID.randomUUID().toString(),
        name: String = "Test plot",
        description: String = "",
        notes: String = "",
        latitude: Double = -6.9,
        longitude: Double = 107.6,
        altitude: Double? = null,
        accuracy: Float? = null,
        address: String? = null,
        parcelNumber: String? = null,
        areaSqm: Double? = null,
        createdAt: Long = 1_700_000_000_000,
        updatedAt: Long = 1_700_000_000_000
    ) = Land(
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
        createdAt = createdAt,
        updatedAt = updatedAt,
        photos = emptyList()
    )
}
