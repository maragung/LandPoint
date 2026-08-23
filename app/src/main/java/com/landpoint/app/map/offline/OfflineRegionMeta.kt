package com.landpoint.app.map.offline

import com.landpoint.app.map.GeoBounds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the app remembers about a saved area, beyond what MapLibre stores itself.
 *
 * MapLibre keeps the geometry and the tiles; it keeps nothing that would let a user
 * recognise one region from another. The name, when it was saved, and which style it
 * was saved from all live here, in the opaque `byte[]` MapLibre carries alongside
 * each region.
 *
 * Kept in that blob rather than in the app's own database for one reason worth the
 * awkwardness: the two can never disagree. A region deleted by MapLibre takes its
 * name with it, a database restored from an old backup cannot resurrect a region
 * whose tiles are gone, and a download interrupted by the process being killed comes
 * back with its name intact — because the name was written when the region was
 * created, in the same transaction.
 *
 * @param schema the version of this record. Read on the way in so a region written by
 *   an older build is understood rather than discarded; MapLibre will happily return
 *   a blob written months ago by a version of the app that no longer exists.
 */
@Serializable
data class OfflineRegionMeta(
    val name: String,
    @SerialName("created_at") val createdAt: Long,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    @SerialName("min_zoom") val minZoom: Int,
    @SerialName("max_zoom") val maxZoom: Int,
    /** [com.landpoint.app.data.BasemapMode.key] of the style this was saved from. */
    @SerialName("style") val styleKey: String,
    val schema: Int = CURRENT_SCHEMA
) {
    val bounds: GeoBounds get() = GeoBounds(south, west, north, east)

    fun encode(): ByteArray = json.encodeToString(serializer(), this).toByteArray()

    companion object {

        /** Bumped when a field stops meaning what it used to. */
        const val CURRENT_SCHEMA = 1

        private val json = Json {
            // Both directions matter here. A blob from a newer build may carry fields
            // this one has never heard of, and refusing it would make every saved
            // region vanish after a downgrade.
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun of(
            name: String,
            createdAt: Long,
            bounds: GeoBounds,
            minZoom: Int,
            maxZoom: Int,
            styleKey: String
        ) = OfflineRegionMeta(
            name = name,
            createdAt = createdAt,
            south = bounds.south,
            west = bounds.west,
            north = bounds.north,
            east = bounds.east,
            minZoom = minZoom,
            maxZoom = maxZoom,
            styleKey = styleKey
        )

        /**
         * Reads a region's metadata, or null if it holds nothing this app wrote.
         *
         * Null is a real possibility rather than defensive padding: MapLibre lets any
         * bytes at all be stored here, and a region created by an earlier design — or
         * by a merged database from another install — will not parse. The caller shows
         * such a region under a placeholder name so it can still be deleted, which is
         * the one thing a user needs to be able to do with a region they cannot
         * identify.
         */
        fun decode(bytes: ByteArray?): OfflineRegionMeta? {
            if (bytes == null || bytes.isEmpty()) return null
            return runCatching {
                json.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
            }.getOrNull()
        }
    }
}
