package com.landpoint.app.data.model

import com.landpoint.app.util.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

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
    val accuracy: List<Double?>? = null,
    /**
     * Stable id per corner, in the same order as [coordinates].
     *
     * Foreign member like [accuracy]: old readers ignore it, new readers use
     * it to bind corner photos across reorder/insert/delete. Omitted when
     * empty so old payloads stay byte-identical.
     */
    val ids: List<String>? = null
)

/**
 * One boundary corner with a stable identity.
 *
 * [GeoPoint] is the measured position; [id] is what survives reorder, insert
 * and delete, so a corner photo stays attached to its corner instead of its
 * position in the ring. New corners get [newCornerId]; corners decoded from
 * geometry written before ids existed get a generated id on load.
 */
@Serializable
data class CornerPoint(
    val id: String = "",
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double? = null
) {
    fun toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude, accuracyM)
}

fun newCornerId(): String = UUID.randomUUID().toString()

fun GeoPoint.toCorner(id: String = newCornerId()): CornerPoint =
    CornerPoint(id = id, latitude = latitude, longitude = longitude, accuracyM = accuracyM)

fun List<GeoPoint>.toCorners(ids: List<String>? = null): List<CornerPoint> =
    mapIndexed { index, point ->
        val id = ids?.getOrNull(index)?.takeIf { it.isNotBlank() } ?: newCornerId()
        point.toCorner(id)
    }

fun List<CornerPoint>.toGeoPoints(): List<GeoPoint> = map { it.toGeoPoint() }

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

    /**
     * Encodes corners with stable ids. Returns null for fewer than 3 corners.
     * Old readers ignore [StoredGeometry.ids]; new readers recover them via
     * [decodeCorners]. Coordinates/accuracy encoding is identical to [encode].
     */
    fun encodeCorners(corners: List<CornerPoint>): String? {
        if (corners.size < 3) return null
        return json.encodeToString(
            StoredGeometry(
                type = GeometryType.POLYGON,
                coordinates = corners.map { listOf(it.longitude, it.latitude) },
                accuracy = corners.map { it.accuracyM }
                    .takeIf { figures -> figures.any { it != null } },
                ids = corners.map { it.id.ifBlank { newCornerId() } }
            )
        )
    }

    /**
     * Decodes corners with ids. Old payloads without ids get generated ones so
     * every corner always has an identity. Never throws.
     */
    fun decodeCorners(geometryJson: String?): List<CornerPoint> {
        if (geometryJson.isNullOrBlank()) return emptyList()
        return runCatching {
            val stored = json.decodeFromString<StoredGeometry>(geometryJson)
            val accuracy = stored.accuracy?.takeIf { it.size == stored.coordinates.size }
            val ids = stored.ids?.takeIf { it.size == stored.coordinates.size }
            stored.coordinates.mapIndexedNotNull { index, pair ->
                if (pair.size < 2) null
                else CornerPoint(
                    id = ids?.get(index)?.takeIf { it.isNotBlank() } ?: newCornerId(),
                    latitude = pair[1],
                    longitude = pair[0],
                    accuracyM = accuracy?.get(index)?.takeIf { it.isFinite() && it > 0.0 }
                )
            }
        }.getOrElse { emptyList() }
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
