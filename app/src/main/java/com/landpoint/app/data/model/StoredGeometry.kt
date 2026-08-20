package com.landpoint.app.data.model

import com.landpoint.app.util.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * How a boundary is stored in `geometry_json`.
 *
 * Coordinates are `[longitude, latitude]` pairs in GeoJSON order — not the
 * lat/lon order used everywhere else in the app. That inversion is deliberate:
 * it keeps the stored text valid GeoJSON, so the same string can be handed to
 * GPX/KML export and to any external tool without translation. [toGeoPoints]
 * and [fromGeoPoints] are the only places the order flips.
 */
@Serializable
data class StoredGeometry(
    val type: String = GeometryType.POLYGON,
    val coordinates: List<List<Double>> = emptyList()
)

object GeometryType {
    const val POINT = "Point"
    const val POLYGON = "Polygon"
}

object GeometryCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(points: List<GeoPoint>): String? {
        if (points.size < 3) return null
        return json.encodeToString(
            StoredGeometry(
                type = GeometryType.POLYGON,
                coordinates = points.map { listOf(it.longitude, it.latitude) }
            )
        )
    }

    /** Returns an empty list for null, blank or malformed input — never throws. */
    fun decode(geometryJson: String?): List<GeoPoint> {
        if (geometryJson.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<StoredGeometry>(geometryJson)
                .coordinates
                .mapNotNull { pair ->
                    if (pair.size < 2) null
                    else GeoPoint(latitude = pair[1], longitude = pair[0])
                }
        }.getOrElse { emptyList() }
    }
}
