package com.landpoint.app.map.offline

import com.landpoint.app.map.GeoBounds
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * What a download would cost, and whether it is allowed to go ahead.
 *
 * Pure arithmetic on purpose. Two different things depend on getting this right — a
 * number shown to the user before they commit, and a hard refusal of requests that
 * would fill their phone — and neither should need a device to test.
 *
 * Every limit here is enforced, not advised. The instruction this was built to is
 * explicit that the app must not download tiles without bound or fill storage
 * uncontrollably, and a warning the user can tap past is not a bound.
 */
object OfflineBudget {

    /**
     * The largest area that may be saved in one region, in square kilometres.
     *
     * Roughly a 120 km square — a whole regency with room to spare, and far more
     * than the "the land and the road to it" this feature exists for. The ceiling is
     * here because the source is donated infrastructure: a user who wants a province
     * should fetch a published archive and import it, which costs that server one
     * request instead of a hundred thousand.
     */
    const val MAX_AREA_SQUARE_KM = 15_000.0

    /** Tiles in one region. A second ceiling, because area alone misses deep zooms. */
    const val MAX_TILES = 120_000L

    /** Regions kept at once, so a phone cannot be filled one small area at a time. */
    const val MAX_REGIONS = 12

    /**
     * Free space that must remain after a download finishes.
     *
     * Android starts behaving badly well before a volume is truly full — and this
     * app's own database, photos and exports all need somewhere to go.
     */
    const val DISK_HEADROOM_BYTES = 250L * 1024 * 1024

    /**
     * Average bytes per tile, used only for the estimate shown before a download.
     *
     * Both numbers are rough by nature: a vector tile over open sea is under a
     * kilobyte and one over a city centre is several hundred, and the true figure for
     * a given area cannot be known without asking for it. MapLibre reports the real
     * required-resource count once a download starts, and that is what the progress
     * display uses — this is for the sentence before the user commits, which is why
     * the estimates lean high rather than flattering.
     */
    const val VECTOR_TILE_BYTES = 45_000L

    /** @see VECTOR_TILE_BYTES */
    const val RASTER_TILE_BYTES = 28_000L

    /**
     * Style, fonts and sprites fetched once per region regardless of its size.
     *
     * Small, but not nothing, and leaving it out makes the estimate for a tiny area
     * look implausibly cheap.
     */
    const val FIXED_OVERHEAD_BYTES = 2L * 1024 * 1024

    /**
     * How many tiles cover [bounds] between [minZoom] and [maxZoom] inclusive.
     *
     * Counted per zoom from the Web Mercator tile grid, the same grid MapLibre asks
     * for, so this is a count of real tiles rather than a scaling guess.
     */
    fun tileCount(bounds: GeoBounds, minZoom: Int, maxZoom: Int): Long {
        if (minZoom > maxZoom) return 0
        var total = 0L
        for (zoom in minZoom..maxZoom) {
            val scale = 1 shl zoom
            val xMin = tileX(bounds.west, scale)
            val xMax = tileX(bounds.east, scale)
            // Tile y counts downwards from the north pole, so the northern edge of
            // the box gives the smaller index.
            val yMin = tileY(bounds.north, scale)
            val yMax = tileY(bounds.south, scale)
            total += (xMax - xMin + 1).toLong() * (yMax - yMin + 1).toLong()
        }
        return total
    }

    /** Bytes a region of [tiles] tiles is expected to take. */
    fun estimateBytes(tiles: Long, vector: Boolean): Long =
        FIXED_OVERHEAD_BYTES + tiles * (if (vector) VECTOR_TILE_BYTES else RASTER_TILE_BYTES)

    /**
     * The whole picture for a proposed download.
     *
     * [tileMaxZoom] is the deepest zoom the *source* publishes, which for the vector
     * street tiles is far shallower than the zoom the user can see: MapLibre scales
     * level 14 geometry up to level 19 on the device. Clamping to it here is what
     * makes the estimate honest — without it a request to save a village "to zoom 19"
     * would be quoted at a thousand times its real cost, and refused for being over a
     * limit it was never going to reach.
     */
    fun plan(
        bounds: GeoBounds,
        minZoom: Int,
        maxZoom: Int,
        tileMaxZoom: Int,
        vector: Boolean
    ): OfflinePlan {
        val effectiveMax = minOf(maxZoom, tileMaxZoom)
        val tiles = tileCount(bounds, minZoom, effectiveMax)
        return OfflinePlan(
            bounds = bounds,
            minZoom = minZoom,
            requestedMaxZoom = maxZoom,
            effectiveMaxZoom = effectiveMax,
            tiles = tiles,
            estimatedBytes = estimateBytes(tiles, vector),
            areaSquareKm = bounds.areaSquareKm
        )
    }

    /**
     * Whether [plan] may proceed, given what else is already stored.
     *
     * @param freeBytes space left on the volume the offline database lives on.
     * @param existingRegions regions already saved.
     */
    fun verdict(
        plan: OfflinePlan,
        freeBytes: Long,
        existingRegions: Int
    ): BudgetVerdict = when {
        plan.bounds.isDegenerate -> BudgetVerdict.NothingSelected
        plan.tiles <= 0L -> BudgetVerdict.NothingSelected
        plan.areaSquareKm > MAX_AREA_SQUARE_KM ->
            BudgetVerdict.AreaTooLarge(plan.areaSquareKm, MAX_AREA_SQUARE_KM)
        plan.tiles > MAX_TILES -> BudgetVerdict.TooManyTiles(plan.tiles, MAX_TILES)
        existingRegions >= MAX_REGIONS -> BudgetVerdict.TooManyRegions(MAX_REGIONS)
        plan.estimatedBytes + DISK_HEADROOM_BYTES > freeBytes ->
            BudgetVerdict.NotEnoughSpace(plan.estimatedBytes, freeBytes)
        else -> BudgetVerdict.Allowed
    }

    private fun tileX(longitude: Double, scale: Int): Int =
        clamp(floor((longitude + 180.0) / 360.0 * scale).toInt(), scale)

    private fun tileY(latitude: Double, scale: Int): Int {
        // Web Mercator, clamped to the poles the projection actually reaches. Past
        // about 85.05° the formula runs away to infinity.
        val clamped = latitude.coerceIn(-85.05112878, 85.05112878)
        val radians = Math.toRadians(clamped)
        val y = (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / Math.PI) / 2.0
        return clamp(floor(y * scale).toInt(), scale)
    }

    /** The east and north edges land exactly on the next tile at some zooms. */
    private fun clamp(index: Int, scale: Int): Int = index.coerceIn(0, scale - 1)
}

/** A proposed download, costed. */
data class OfflinePlan(
    val bounds: GeoBounds,
    val minZoom: Int,
    /** What the user asked for, which is what the zoom slider shows. */
    val requestedMaxZoom: Int,
    /** What will actually be fetched, once the source's own limit is applied. */
    val effectiveMaxZoom: Int,
    val tiles: Long,
    val estimatedBytes: Long,
    val areaSquareKm: Double
) {
    /**
     * True when the source stops shallower than the user asked for.
     *
     * Worth saying on screen rather than silently adjusting the slider: the map will
     * still be sharp at the deeper zoom, because vector geometry is scaled on the
     * device, and a user who is not told that will assume the download was cut short.
     */
    val cappedBySource: Boolean get() = effectiveMaxZoom < requestedMaxZoom
}

/** Why a download may or may not start. */
sealed interface BudgetVerdict {

    data object Allowed : BudgetVerdict

    /** No rectangle drawn, or one with no extent. */
    data object NothingSelected : BudgetVerdict

    data class AreaTooLarge(val requested: Double, val limit: Double) : BudgetVerdict

    data class TooManyTiles(val requested: Long, val limit: Long) : BudgetVerdict

    data class TooManyRegions(val limit: Int) : BudgetVerdict

    data class NotEnoughSpace(val needed: Long, val free: Long) : BudgetVerdict
}
