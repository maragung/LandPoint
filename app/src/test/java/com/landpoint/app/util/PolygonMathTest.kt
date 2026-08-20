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
