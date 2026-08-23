package com.landpoint.app.map.offline

import com.landpoint.app.map.GeoBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a download would cost, and when it is refused.
 *
 * Every limit here is enforced rather than advised, so every limit is tested. The
 * cases that matter are the two ways a user asks for too much without meaning to —
 * dragging the rectangle wider than the province, and leaving the zoom slider at the
 * deepest level — and the one case where a refusal would be wrong: a village saved
 * "to zoom 19" from a vector source, which costs a few dozen tiles rather than
 * thousands because the geometry is scaled on the device.
 *
 * Tile counts at the shallowest zooms are hand-checkable, and they are the ones
 * asserted exactly: the whole world is four tiles at zoom 1 and sixteen at zoom 2,
 * so a transposed axis or an off-by-one in the grid shows up here rather than as a
 * download that quietly fetches half of what it should.
 */
class OfflineBudgetTest {

    /** Whole earth, to the latitudes Web Mercator actually reaches. */
    private val world = GeoBounds(-85.05112878, -180.0, 85.05112878, 180.0)

    /** A village-sized rectangle near Bandung: about 5.5 km on a side. */
    private val village = GeoBounds(-6.95, 107.58, -6.90, 107.63)

    /** Half a degree each way — a city and its surroundings. */
    private val city = GeoBounds(-7.2, 107.3, -6.7, 107.8)

    /** Two degrees each way, which is past the area ceiling on its own. */
    private val province = GeoBounds(-7.5, 107.0, -5.5, 109.0)

    private val plenty = 64L * 1024 * 1024 * 1024

    @Test
    fun `the world is one tile at zoom zero, four at one, sixteen at two`() {
        assertEquals(1L, OfflineBudget.tileCount(world, 0, 0))
        assertEquals(4L, OfflineBudget.tileCount(world, 1, 1))
        assertEquals(16L, OfflineBudget.tileCount(world, 2, 2))
        // A range is the sum of its levels, not the deepest one.
        assertEquals(21L, OfflineBudget.tileCount(world, 0, 2))
    }

    @Test
    fun `any rectangle is one tile at zoom zero`() {
        assertEquals(1L, OfflineBudget.tileCount(village, 0, 0))
        assertEquals(1L, OfflineBudget.tileCount(province, 0, 0))
    }

    @Test
    fun `a level deeper is roughly four times the tiles`() {
        val shallow = OfflineBudget.tileCount(city, 12, 12)
        val deeper = OfflineBudget.tileCount(city, 13, 13)

        // Not exactly four: a rectangle straddles tile edges differently at each
        // level. Bounded either side so a factor-of-two mistake cannot pass.
        assertTrue("$shallow -> $deeper", deeper > shallow * 3)
        assertTrue("$shallow -> $deeper", deeper < shallow * 5)
    }

    @Test
    fun `an inverted zoom range costs nothing rather than throwing`() {
        assertEquals(0L, OfflineBudget.tileCount(village, 14, 10))
    }

    @Test
    fun `the estimate is the overhead plus the tiles, and vector tiles are the heavier ones`() {
        val vector = OfflineBudget.estimateBytes(100, vector = true)
        val raster = OfflineBudget.estimateBytes(100, vector = false)

        assertEquals(OfflineBudget.FIXED_OVERHEAD_BYTES + 100 * OfflineBudget.VECTOR_TILE_BYTES, vector)
        assertEquals(OfflineBudget.FIXED_OVERHEAD_BYTES + 100 * OfflineBudget.RASTER_TILE_BYTES, raster)
        assertTrue(vector > raster)
        // Style, fonts and sprites are fetched whatever the size, so a tiny area is
        // never quoted as free.
        assertEquals(OfflineBudget.FIXED_OVERHEAD_BYTES, OfflineBudget.estimateBytes(0, vector = true))
    }

    @Test
    fun `a vector source is costed against the zoom it publishes, not the zoom asked for`() {
        val plan = OfflineBudget.plan(
            bounds = village,
            minZoom = 0,
            maxZoom = 19,
            tileMaxZoom = 14,
            vector = true
        )

        assertEquals(14, plan.effectiveMaxZoom)
        assertEquals(19, plan.requestedMaxZoom)
        assertTrue(plan.cappedBySource)
        // Thirty tiles for a village, because levels past 14 are drawn by scaling
        // geometry the archive already has. Costed against the requested 19 it would
        // be quoted at hundreds of times this and refused for a limit it never met.
        assertEquals(30L, plan.tiles)
        assertEquals(OfflineBudget.tileCount(village, 0, 14), plan.tiles)
    }

    @Test
    fun `a raster source is costed against every level the user asked for`() {
        val plan = OfflineBudget.plan(
            bounds = village,
            minZoom = 0,
            maxZoom = 19,
            tileMaxZoom = 19,
            vector = false
        )

        assertEquals(19, plan.effectiveMaxZoom)
        assertFalse(plan.cappedBySource)
        assertEquals(7_403L, plan.tiles)
    }

    @Test
    fun `a village from a vector source is allowed`() {
        val plan = OfflineBudget.plan(village, 0, 19, tileMaxZoom = 14, vector = true)

        assertEquals(
            BudgetVerdict.Allowed,
            OfflineBudget.verdict(plan, freeBytes = plenty, existingRegions = 0)
        )
        assertTrue(plan.areaSquareKm < 50.0)
    }

    @Test
    fun `a rectangle dragged across a province is refused for its area`() {
        val plan = OfflineBudget.plan(province, 0, 8, tileMaxZoom = 14, vector = true)
        val verdict = OfflineBudget.verdict(plan, freeBytes = plenty, existingRegions = 0)

        assertTrue("got $verdict", verdict is BudgetVerdict.AreaTooLarge)
        verdict as BudgetVerdict.AreaTooLarge
        assertEquals(OfflineBudget.MAX_AREA_SQUARE_KM, verdict.limit, 0.0)
        assertTrue(verdict.requested > verdict.limit)
    }

    @Test
    fun `a modest area at photographic depth is refused for its tile count`() {
        // Inside the area ceiling — this is one city — and still three quarters of a
        // million tiles, because a raster source has a real tile at every level.
        val plan = OfflineBudget.plan(city, 0, 19, tileMaxZoom = 19, vector = false)
        assertTrue(plan.areaSquareKm < OfflineBudget.MAX_AREA_SQUARE_KM)

        val verdict = OfflineBudget.verdict(plan, freeBytes = plenty, existingRegions = 0)

        assertTrue("got $verdict", verdict is BudgetVerdict.TooManyTiles)
        verdict as BudgetVerdict.TooManyTiles
        assertEquals(OfflineBudget.MAX_TILES, verdict.limit)
        assertTrue(verdict.requested > verdict.limit)
    }

    @Test
    fun `a phone already full of saved areas takes no more`() {
        val plan = OfflineBudget.plan(village, 0, 14, tileMaxZoom = 14, vector = true)

        val verdict = OfflineBudget.verdict(
            plan,
            freeBytes = plenty,
            existingRegions = OfflineBudget.MAX_REGIONS
        )

        assertEquals(BudgetVerdict.TooManyRegions(OfflineBudget.MAX_REGIONS), verdict)
        // One below the ceiling still goes ahead, so the limit is a count and not an
        // off-by-one.
        assertEquals(
            BudgetVerdict.Allowed,
            OfflineBudget.verdict(plan, plenty, OfflineBudget.MAX_REGIONS - 1)
        )
    }

    @Test
    fun `a download that would leave no room is refused before it starts`() {
        val plan = OfflineBudget.plan(village, 0, 14, tileMaxZoom = 14, vector = true)
        // Enough for the tiles themselves, but not for the headroom the phone needs
        // for its own database, photos and exports.
        val tight = plan.estimatedBytes + OfflineBudget.DISK_HEADROOM_BYTES / 2

        val verdict = OfflineBudget.verdict(plan, freeBytes = tight, existingRegions = 0)

        assertTrue("got $verdict", verdict is BudgetVerdict.NotEnoughSpace)
        verdict as BudgetVerdict.NotEnoughSpace
        assertEquals(plan.estimatedBytes, verdict.needed)
        assertEquals(tight, verdict.free)

        assertEquals(
            BudgetVerdict.Allowed,
            OfflineBudget.verdict(
                plan,
                freeBytes = plan.estimatedBytes + OfflineBudget.DISK_HEADROOM_BYTES + 1,
                existingRegions = 0
            )
        )
    }

    @Test
    fun `a rectangle with no extent is nothing selected, not an empty download`() {
        val flat = GeoBounds(-6.9, 107.6, -6.9, 107.7)
        val plan = OfflineBudget.plan(flat, 0, 14, tileMaxZoom = 14, vector = true)

        assertTrue(plan.bounds.isDegenerate)
        assertEquals(
            BudgetVerdict.NothingSelected,
            OfflineBudget.verdict(plan, freeBytes = plenty, existingRegions = 0)
        )
    }

    @Test
    fun `an inverted zoom range is nothing selected too`() {
        val plan = OfflineBudget.plan(village, 14, 10, tileMaxZoom = 14, vector = true)

        assertEquals(0L, plan.tiles)
        assertEquals(
            BudgetVerdict.NothingSelected,
            OfflineBudget.verdict(plan, freeBytes = plenty, existingRegions = 0)
        )
    }

    @Test
    fun `the plan reports the area it was asked about`() {
        val plan = OfflineBudget.plan(village, 0, 14, tileMaxZoom = 14, vector = true)

        assertEquals(village.areaSquareKm, plan.areaSquareKm, 1e-9)
        assertEquals(village, plan.bounds)
        assertEquals(0, plan.minZoom)
    }
}
