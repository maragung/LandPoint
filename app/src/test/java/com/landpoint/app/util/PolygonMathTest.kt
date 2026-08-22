package com.landpoint.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolygonMathTest {

    private val lat = -6.914744
    private val lon = 107.609810

    /** Builds a square of [sideM] metres with its south-west corner at the origin. */
    private fun square(sideM: Double): List<GeoPoint> {
        val dLat = sideM / 111_320.0
        val dLon = sideM / (111_320.0 * Math.cos(Math.toRadians(lat)))
        return listOf(
            GeoPoint(lat, lon),
            GeoPoint(lat + dLat, lon),
            GeoPoint(lat + dLat, lon + dLon),
            GeoPoint(lat, lon + dLon)
        )
    }

    /** A point [northM] metres north and [eastM] metres east of the origin. */
    private fun at(northM: Double, eastM: Double) = GeoPoint(
        latitude = lat + northM / 111_320.0,
        longitude = lon + eastM / (111_320.0 * Math.cos(Math.toRadians(lat)))
    )

    // ---- selfCrossing ------------------------------------------------------
    //
    // The reason this check exists at all is the first test below: a bow tie
    // produces an area figure that looks like a real parcel and is nothing like
    // the ground covered. Nothing else in the app notices — it exports, prints
    // and closes neatly — so the sides that cross have to be named here.

    @Test
    fun `a bow tie reports a plausible area, which is why it must be caught`() {
        // Two corners of a 20 m square in the wrong order. The lobes are equal
        // and opposite, so the signed accumulation nearly cancels.
        val bowTie = listOf(at(0.0, 0.0), at(0.0, 20.0), at(20.0, 0.0), at(20.0, 20.0))
        val area = PolygonMath.areaSqm(bowTie)
        assertTrue(
            "expected the area to be misleading, got $area of a real 400 sqm",
            area < 50.0
        )
        // And that is what this catches: sides 1-3 and 2-4 are the crossing pair.
        assertEquals(2 to 4, PolygonMath.selfCrossing(bowTie))
    }

    @Test
    fun `a simple square does not cross itself`() {
        assertNull(PolygonMath.selfCrossing(square(20.0)))
    }

    @Test
    fun `a concave outline is not mistaken for a crossing`() {
        // An L-shaped plot: entirely ordinary, and the case a naive check flags.
        val ell = listOf(
            at(0.0, 0.0),
            at(0.0, 30.0),
            at(10.0, 30.0),
            at(10.0, 10.0),
            at(30.0, 10.0),
            at(30.0, 0.0)
        )
        assertNull(PolygonMath.selfCrossing(ell))
    }

    @Test
    fun `too few corners to cross gives nothing`() {
        assertNull(PolygonMath.selfCrossing(emptyList()))
        assertNull(PolygonMath.selfCrossing(listOf(at(0.0, 0.0))))
        // A triangle cannot cross itself: every pair of sides shares a corner.
        assertNull(PolygonMath.selfCrossing(listOf(at(0.0, 0.0), at(0.0, 10.0), at(10.0, 0.0))))
    }

    @Test
    fun `a crossing against the closing side is found too`() {
        // Five corners with the last two entered the wrong way round, which is the
        // everyday cause. The crossing is on the side back to corner 1 — the only
        // side nobody places by hand, and so the one it would be easiest to miss.
        val outOfOrder = listOf(
            at(0.0, 0.0),
            at(0.0, 20.0),
            at(20.0, 20.0),
            at(20.0, 0.0),   // these last two belong
            at(30.0, 10.0)   // the other way round
        )
        assertEquals(3 to 5, PolygonMath.selfCrossing(outOfOrder))
    }

    @Test
    fun `sides that only meet end to end are not reported`() {
        // A corner placed back on the line between two others: degenerate, but the
        // outline still encloses what it says it does, so a warning here would sit
        // on a boundary that is fine.
        val flat = listOf(at(0.0, 0.0), at(0.0, 10.0), at(0.0, 20.0), at(20.0, 20.0), at(20.0, 0.0))
        assertNull(PolygonMath.selfCrossing(flat))
    }

    @Test
    fun `fewer than three points has no area`() {
        assertEquals(0.0, PolygonMath.areaSqm(emptyList()), 0.0)
        assertEquals(0.0, PolygonMath.areaSqm(listOf(GeoPoint(lat, lon))), 0.0)
        assertEquals(
            0.0,
            PolygonMath.areaSqm(listOf(GeoPoint(lat, lon), GeoPoint(lat + 0.001, lon))),
            0.0
        )
    }

    @Test
    fun `a hundred metre square is one hectare`() {
        val area = PolygonMath.areaSqm(square(100.0))
        // 100 m x 100 m = 10,000 sqm. Allow 1% for the flat-earth approximation
        // used to *build* the test square, not for the method under test.
        assertEquals(10_000.0, area, 100.0)
    }

    @Test
    fun `winding order does not change the area`() {
        val clockwise = square(50.0)
        val anticlockwise = clockwise.reversed()
        assertEquals(
            PolygonMath.areaSqm(clockwise),
            PolygonMath.areaSqm(anticlockwise),
            0.001
        )
    }

    @Test
    fun `perimeter closes the loop`() {
        val perimeter = PolygonMath.perimeterM(square(100.0))
        assertEquals(400.0, perimeter, 4.0)
    }

    @Test
    fun `path length leaves the loop open`() {
        val path = PolygonMath.pathLengthM(square(100.0))
        // Three sides walked, the fourth not yet closed.
        assertEquals(300.0, path, 3.0)
    }

    @Test
    fun `centroid of a square sits in the middle`() {
        val points = square(100.0)
        val centre = PolygonMath.centroid(points)!!
        val dLat = 100.0 / 111_320.0
        assertEquals(lat + dLat / 2, centre.latitude, 1e-5)
    }

    @Test
    fun `centroid of nothing is null`() {
        assertNull(PolygonMath.centroid(emptyList()))
    }

    @Test
    fun `centroid across the antimeridian stays there`() {
        val centre = PolygonMath.centroid(
            listOf(GeoPoint(1.0, 179.99), GeoPoint(1.0, -179.99))
        )!!
        assertTrue("got ${centre.longitude}", Math.abs(centre.longitude) > 179.9)
    }

    @Test
    fun `jitter smaller than the fix accuracy is not a new corner`() {
        val previous = GeoPoint(lat, lon)
        val jitter = GeoPoint(lat + 3.0 / 111_320.0, lon)
        assertTrue(!PolygonMath.isMeaningfulStep(previous, jitter, accuracyM = 8.0))
    }

    @Test
    fun `a real step is recorded`() {
        val previous = GeoPoint(lat, lon)
        val moved = GeoPoint(lat + 20.0 / 111_320.0, lon)
        assertTrue(PolygonMath.isMeaningfulStep(previous, moved, accuracyM = 8.0))
    }

    @Test
    fun `the first point is always recorded`() {
        assertTrue(PolygonMath.isMeaningfulStep(null, GeoPoint(lat, lon), accuracyM = 50.0))
    }

    @Test
    fun `a very accurate fix still needs a two metre step`() {
        // Guards the floor: with a 0.5 m fix we must not record every twitch.
        val previous = GeoPoint(lat, lon)
        val tiny = GeoPoint(lat + 1.0 / 111_320.0, lon)
        assertTrue(!PolygonMath.isMeaningfulStep(previous, tiny, accuracyM = 0.5))
    }
}
