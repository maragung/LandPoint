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
    val coordinates: List<List<Double>> = emptyList(),
    /**
     * GPS accuracy in metres per corner, in the same order as [coordinates], null
     * at any corner that was typed in rather than measured.
     *
     * A foreign member: RFC 7946 §6.1 allows extra keys on a geometry and obliges
     * a reader that does not know them to leave them alone, so nothing that could
     * read this geometry before is stopped by their being here. Left out entirely
     * when no corner has a figure, which keeps a typed boundary written today
     * byte-identical to one written before this member existed.
     *
     * Not a third number inside each position: that slot means elevation in
     * GeoJSON, and a fourth is explicitly discouraged.
     */
    val accuracy: List<Double?>? = null
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
                coordinates = points.map { listOf(it.longitude, it.latitude) },
                accuracy = points.map { it.accuracyM }
                    .takeIf { figures -> figures.any { it != null } }
            )
        )
    }

    /** Returns an empty list for null, blank or malformed input — never throws. */
    fun decode(geometryJson: String?): List<GeoPoint> {
        if (geometryJson.isNullOrBlank()) return emptyList()
        return runCatching {
            val stored = json.decodeFromString<StoredGeometry>(geometryJson)
            // Trusted only when there is exactly one figure per corner. A list of
            // any other length was written or edited by something that did not
            // know what it was for, and guessing which corners it still lines up
            // with would hang one corner's accuracy on another.
            val accuracy = stored.accuracy?.takeIf { it.size == stored.coordinates.size }
            stored.coordinates.mapIndexedNotNull { index, pair ->
                if (pair.size < 2) null
                else GeoPoint(
                    latitude = pair[1],
                    longitude = pair[0],
                    // Indexed against coordinates, so a dropped malformed pair
                    // cannot shift the remaining figures onto the wrong corners.
                    accuracyM = accuracy?.get(index)?.takeIf { it.isFinite() && it > 0.0 }
                )
            }
        }.getOrElse { emptyList() }
    }
}
