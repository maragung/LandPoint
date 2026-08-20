package com.landpoint.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoUtilsTest {

    // Bandung ↔ Jakarta, roughly 120 km apart.
    private val bandungLat = -6.914744
    private val bandungLon = 107.609810
    private val jakartaLat = -6.208763
    private val jakartaLon = 106.845599

    @Test
    fun `distance between the same point is zero`() {
        assertEquals(0.0, GeoUtils.distance(bandungLat, bandungLon, bandungLat, bandungLon), 0.001)
    }

    @Test
    fun `distance matches a known pair within one percent`() {
        val meters = GeoUtils.distance(bandungLat, bandungLon, jakartaLat, jakartaLon)
        assertTrue("expected ~119 km, got $meters m", meters in 115_000.0..123_000.0)
    }

    @Test
    fun `distance is symmetric`() {
        val there = GeoUtils.distance(bandungLat, bandungLon, jakartaLat, jakartaLon)
        val back = GeoUtils.distance(jakartaLat, jakartaLon, bandungLat, bandungLon)
        assertEquals(there, back, 0.001)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        assertEquals(111_195.0, GeoUtils.distance(0.0, 0.0, 1.0, 0.0), 500.0)
    }

    @Test
    fun `bearing due north is zero`() {
        assertEquals(0.0, GeoUtils.bearing(0.0, 0.0, 10.0, 0.0), 0.01)
    }

    @Test
    fun `bearing due east is ninety`() {
        assertEquals(90.0, GeoUtils.bearing(0.0, 0.0, 0.0, 10.0), 0.01)
    }

    @Test
    fun `bearing due west normalises to 270 not negative`() {
        assertEquals(270.0, GeoUtils.bearing(0.0, 0.0, 0.0, -10.0), 0.01)
    }

    @Test
    fun `bearing is always in range`() {
        val samples = listOf(
            listOf(0.0, 0.0, 45.0, 45.0),
            listOf(-33.9, 151.2, 51.5, -0.1),
            listOf(89.0, 179.0, -89.0, -179.0)
        )
        samples.forEach { (a, b, c, d) ->
            val bearing = GeoUtils.bearing(a, b, c, d)
            assertTrue("bearing $bearing out of range", bearing >= 0.0 && bearing < 360.0)
        }
    }

    @Test
    fun `cardinal directions cover the compass`() {
        assertEquals("N", GeoUtils.bearingToCardinal(0.0))
        assertEquals("NE", GeoUtils.bearingToCardinal(45.0))
        assertEquals("E", GeoUtils.bearingToCardinal(90.0))
        assertEquals("SE", GeoUtils.bearingToCardinal(135.0))
        assertEquals("S", GeoUtils.bearingToCardinal(180.0))
        assertEquals("SW", GeoUtils.bearingToCardinal(225.0))
        assertEquals("W", GeoUtils.bearingToCardinal(270.0))
        assertEquals("NW", GeoUtils.bearingToCardinal(315.0))
        assertEquals("N", GeoUtils.bearingToCardinal(350.0))
    }

    @Test
    fun `formatDistance switches unit at one kilometre`() {
        assertEquals("999 m", GeoUtils.formatDistance(999.0))
        assertEquals("1.0 km", GeoUtils.formatDistance(1000.0))
        assertEquals("2.5 km", GeoUtils.formatDistance(2500.0))
    }

    @Test
    fun `formatDistance supports imperial`() {
        assertEquals("328 ft", GeoUtils.formatDistance(100.0, imperial = true))
        assertTrue(GeoUtils.formatDistance(5000.0, imperial = true).endsWith("mi"))
    }

    @Test
    fun `formatDecimal keeps six places`() {
        assertEquals("-6.914744, 107.609810", GeoUtils.formatDecimal(bandungLat, bandungLon))
    }

    @Test
    fun `formatDMS marks the right hemispheres`() {
        val south = GeoUtils.formatDMS(-6.5, 107.5)
        assertTrue(south, south.contains("S"))
        assertTrue(south, south.contains("E"))

        val north = GeoUtils.formatDMS(6.5, -107.5)
        assertTrue(north, north.contains("N"))
        assertTrue(north, north.contains("W"))
    }

    @Test
    fun `formatDMS converts a known value`() {
        // 6.5 degrees == 6 deg 30 min 0 sec
        assertTrue(GeoUtils.formatDMS(6.5, 0.0).startsWith("6°30'0.0\""))
    }

    /**
     * Seconds are printed to one decimal, so a value just under the next minute
     * used to round up to a reading that does not exist: 6°30'60.0".
     */
    @Test
    fun `formatDMS never prints sixty seconds`() {
        // Walk a range dense enough to cross many rounding boundaries.
        var degrees = 0.0
        while (degrees < 1.0) {
            val text = GeoUtils.formatDMS(degrees, degrees)
            assertTrue("60 seconds is not a reading: $text", !text.contains("60.0\""))
            assertTrue("60 minutes is not a reading: $text", !text.contains("°60'"))
            degrees += 0.000137   // ~0.5 arc-seconds, deliberately not a round step
        }
    }

    /** The exact boundary the old code tripped on: 59.97" rounds into a carry. */
    @Test
    fun `formatDMS carries into the next minute instead of overflowing`() {
        // 0°0'59.97" — one decimal place rounds this to 60.0 without a carry.
        val decimal = 59.97 / 3600.0
        val text = GeoUtils.formatDMS(decimal, 0.0)

        assertTrue("expected a carry to 1 minute, got $text", text.startsWith("0°1'0.0\""))
    }

    @Test
    fun `googleMapsLink embeds both coordinates`() {
        val link = GeoUtils.googleMapsLink(bandungLat, bandungLon)
        assertTrue(link, link.contains("$bandungLat,$bandungLon"))
    }
}
