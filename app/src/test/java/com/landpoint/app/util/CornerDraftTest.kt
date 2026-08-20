package com.landpoint.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tap guard decides whether a finger press becomes a corner of someone's
 * land, so the cases that matter are the ones where it must say no: a double tap
 * on a corner already placed, and a tap on a corner that is no longer the most
 * recent one.
 */
class CornerDraftTest {

    private val lat = -6.914744
    private val lon = 107.609810

    /** A point [northM] metres north and [eastM] metres east of the origin. */
    private fun offset(northM: Double, eastM: Double) = GeoPoint(
        latitude = lat + northM / 111_320.0,
        longitude = lon + eastM / (111_320.0 * Math.cos(Math.toRadians(lat)))
    )

    @Test
    fun `the first tap is always a corner`() {
        assertTrue(CornerDraft.acceptTap(emptyList(), GeoPoint(lat, lon)))
    }

    @Test
    fun `tapping the same spot twice does not add a second corner`() {
        val placed = GeoPoint(lat, lon)
        assertFalse(CornerDraft.acceptTap(listOf(placed), placed))
    }

    @Test
    fun `a tap a third of a metre away is the same corner`() {
        val existing = listOf(GeoPoint(lat, lon))
        assertFalse(CornerDraft.acceptTap(existing, offset(northM = 0.3, eastM = 0.0)))
    }

    @Test
    fun `a tap a metre away is a new corner`() {
        val existing = listOf(GeoPoint(lat, lon))
        assertTrue(CornerDraft.acceptTap(existing, offset(northM = 1.0, eastM = 0.0)))
    }

    /**
     * The one that a last-corner-only check would get wrong: closing a ring by
     * tapping back on the *first* corner must not add a duplicate vertex.
     */
    @Test
    fun `a tap on an earlier corner is refused, not just on the last one`() {
        val ring = listOf(
            GeoPoint(lat, lon),
            offset(northM = 20.0, eastM = 0.0),
            offset(northM = 20.0, eastM = 20.0),
            offset(northM = 0.0, eastM = 20.0)
        )
        assertFalse(
            "tapping the first corner again must not add a vertex",
            CornerDraft.acceptTap(ring, GeoPoint(lat, lon))
        )
        assertFalse(
            "nor a corner in the middle of the ring",
            CornerDraft.acceptTap(ring, offset(northM = 20.0, eastM = 0.1))
        )
        assertTrue(
            "an untouched spot inside the ring is still a corner",
            CornerDraft.acceptTap(ring, offset(northM = 10.0, eastM = 10.0))
        )
    }

    @Test
    fun `a caller may set its own separation`() {
        val existing = listOf(GeoPoint(lat, lon))
        val threeMetresAway = offset(northM = 3.0, eastM = 0.0)
        assertTrue(CornerDraft.acceptTap(existing, threeMetresAway))
        assertFalse(CornerDraft.acceptTap(existing, threeMetresAway, minSeparationM = 5.0))
    }

    @Test
    fun `corners far apart are all accepted in turn`() {
        var ring = emptyList<GeoPoint>()
        listOf(
            GeoPoint(lat, lon),
            offset(northM = 15.0, eastM = 0.0),
            offset(northM = 15.0, eastM = 15.0),
            offset(northM = 0.0, eastM = 15.0)
        ).forEach { corner ->
            assertTrue("corner $corner should be accepted", CornerDraft.acceptTap(ring, corner))
            ring = ring + corner
        }
        assertTrue(PolygonMath.areaSqm(ring) > 200.0)
    }
}
