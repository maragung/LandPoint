package com.landpoint.app.map.offline

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The fixed 127-byte header at the front of a PMTiles v3 archive.
 *
 * Read at import rather than trusted, because everything the app needs to know
 * about an archive is in here and every one of those facts is one a file name
 * cannot tell you: whether the tiles are vector geometry or photographs (they need
 * different styles), which zooms exist (asking for one that does not draws nothing),
 * and where on earth it covers (so the map can be sent somewhere the archive
 * actually has data).
 *
 * It is also the only honest way to catch a half-downloaded file. The header states
 * where the tile data ends; if that runs past the end of the file, the archive is
 * truncated — and saying so at import is the difference between a clear refusal and
 * a user in a field with a map that silently draws nothing.
 *
 * Layout is from the PMTiles v3 specification, little-endian throughout.
 */
data class PmtilesHeader(
    val tileType: TileType,
    val minZoom: Int,
    val maxZoom: Int,
    val minLatitude: Double,
    val minLongitude: Double,
    val maxLatitude: Double,
    val maxLongitude: Double,
    /** How many tile addresses the archive claims, for showing what it holds. */
    val addressedTiles: Long
) {
    /** Vector tiles are drawn by the device; everything else is a picture. */
    val isVector: Boolean get() = tileType == TileType.MVT

    /** What kind of tile the archive stores. */
    enum class TileType(val code: Int) {
        MVT(1),
        PNG(2),
        JPEG(3),
        WEBP(4),
        AVIF(5),
        UNKNOWN(0);

        companion object {
            fun of(code: Int): TileType = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    companion object {

        /** Length of the v3 header. Anything shorter is not a PMTiles archive. */
        const val HEADER_BYTES = 127

        private val MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)

        /** The only version this app can read. v2 has an incompatible layout. */
        private const val SUPPORTED_VERSION = 3

        /** Beyond this a zoom byte is a misread, not a deep archive. */
        private const val MAX_SANE_ZOOM = 30

        /**
         * Reads and checks [file], or says what is wrong with it.
         *
         * Only the first 127 bytes are read, plus the file's length — so this is
         * cheap enough to run on every import and on a file of any size, and it
         * still catches the failure that matters most.
         */
        fun read(file: File): PmtilesResult {
            if (!file.isFile) return PmtilesResult.Unreadable
            val length = file.length()
            if (length < HEADER_BYTES) return PmtilesResult.NotPmtiles

            val raw = ByteArray(HEADER_BYTES)
            try {
                RandomAccessFile(file, "r").use { it.readFully(raw) }
            } catch (e: IOException) {
                return PmtilesResult.Unreadable
            }

            for (i in MAGIC.indices) {
                if (raw[i] != MAGIC[i]) return PmtilesResult.NotPmtiles
            }

            val version = raw[7].toInt() and 0xFF
            if (version != SUPPORTED_VERSION) return PmtilesResult.WrongVersion(version)

            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val rootOffset = buffer.getLong(8)
            val rootLength = buffer.getLong(16)
            val tileDataOffset = buffer.getLong(56)
            val tileDataLength = buffer.getLong(64)
            val addressedTiles = buffer.getLong(72)

            // A negative value here means an offset past 2^63, which no real archive
            // has and which would make the bounds checks below meaningless.
            if (rootOffset < 0 || rootLength < 0 || tileDataOffset < 0 || tileDataLength < 0) {
                return PmtilesResult.NotPmtiles
            }
            if (tileDataLength == 0L || addressedTiles <= 0L) return PmtilesResult.Empty

            // The truncation check. A download cut off partway leaves a file whose
            // header still describes the whole archive, so the header is what proves
            // the rest is missing.
            val declaredEnd = maxOf(tileDataOffset + tileDataLength, rootOffset + rootLength)
            if (declaredEnd > length) {
                return PmtilesResult.Truncated(expectedBytes = declaredEnd, actualBytes = length)
            }

            val minZoom = raw[100].toInt() and 0xFF
            val maxZoom = raw[101].toInt() and 0xFF
            if (minZoom > maxZoom || maxZoom > MAX_SANE_ZOOM) return PmtilesResult.NotPmtiles

            val minLongitude = e7(buffer.getInt(102))
            val minLatitude = e7(buffer.getInt(106))
            val maxLongitude = e7(buffer.getInt(110))
            val maxLatitude = e7(buffer.getInt(114))
            if (minLatitude > maxLatitude || minLongitude > maxLongitude ||
                minLatitude < -90.0 || maxLatitude > 90.0 ||
                minLongitude < -180.0 || maxLongitude > 180.0
            ) {
                return PmtilesResult.NotPmtiles
            }

            val tileType = TileType.of(raw[99].toInt() and 0xFF)
            if (tileType == TileType.UNKNOWN) return PmtilesResult.UnknownTileType

            return PmtilesResult.Ok(
                PmtilesHeader(
                    tileType = tileType,
                    minZoom = minZoom,
                    maxZoom = maxZoom,
                    minLatitude = minLatitude,
                    minLongitude = minLongitude,
                    maxLatitude = maxLatitude,
                    maxLongitude = maxLongitude,
                    addressedTiles = addressedTiles
                )
            )
        }

        /** Coordinates are stored as signed degrees times ten million. */
        private fun e7(value: Int): Double = value / 1e7
    }
}

/**
 * The outcome of inspecting a candidate archive.
 *
 * Distinct cases rather than a null, because each one is a different sentence to the
 * user and one of them — [Truncated] — is the one they can actually act on by
 * downloading the file again.
 */
sealed interface PmtilesResult {

    data class Ok(val header: PmtilesHeader) : PmtilesResult

    /** Not a PMTiles archive at all, or damaged past recognition. */
    data object NotPmtiles : PmtilesResult

    /** Could not be opened or read. */
    data object Unreadable : PmtilesResult

    /** A PMTiles archive of a version this app cannot read. */
    data class WrongVersion(val version: Int) : PmtilesResult

    /** Well-formed but holds no tiles. */
    data object Empty : PmtilesResult

    /** Holds tiles in an image format this app has no style for. */
    data object UnknownTileType : PmtilesResult

    /** Cut short — almost always an interrupted download. */
    data class Truncated(val expectedBytes: Long, val actualBytes: Long) : PmtilesResult
}
