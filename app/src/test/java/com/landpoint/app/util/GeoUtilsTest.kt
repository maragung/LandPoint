package com.landpoint.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- destination -------------------------------------------------------
    //
    // This one writes a corner down rather than reading one back, so the way it
    // is checked is by measuring the result with the two functions above: a
    // corner placed on a bearing at a distance must be found at that bearing and
    // that distance. A sign error or a swapped argument passes every test that
    // only looks at one of the two.

    @Test
    fun `a corner placed on a bearing is found back at that bearing`() {
        val bearings = listOf(0.0, 37.5, 90.0, 154.2, 180.0, 231.0, 270.0, 349.9)
        for (azimuth in bearings) {
            for (metres in listOf(5.0, 250.0, 12_000.0)) {
                val point = GeoUtils.destination(bandungLat, bandungLon, azimuth, metres)
                assertEquals(
                    "distance back from $azimuth° at $metres m",
                    metres,
                    GeoUtils.distance(bandungLat, bandungLon, point.latitude, point.longitude),
                    metres * 0.001
                )
                assertEquals(
                    "bearing back from $azimuth° at $metres m",
                    azimuth,
                    GeoUtils.bearing(bandungLat, bandungLon, point.latitude, point.longitude),
                    0.01
                )
            }
        }
    }

    @Test
    fun `due north moves the latitude and leaves the longitude alone`() {
        val point = GeoUtils.destination(bandungLat, bandungLon, 0.0, 1_000.0)
        assertEquals(bandungLat + 1_000.0 / 111_195.0, point.latitude, 0.0001)
        assertEquals(bandungLon, point.longitude, 0.000001)
    }

    @Test
    fun `due east near the equator moves only the longitude`() {
        val point = GeoUtils.destination(0.0, 0.0, 90.0, 1_000.0)
        assertEquals(0.0, point.latitude, 0.000001)
        assertTrue("expected to move east, got ${point.longitude}", point.longitude > 0.0)
    }

    @Test
    fun `a side of no length stays where it started`() {
        val point = GeoUtils.destination(bandungLat, bandungLon, 123.0, 0.0)
        assertEquals(bandungLat, point.latitude, 0.000001)
        assertEquals(bandungLon, point.longitude, 0.000001)
    }

    @Test
    fun `a side crossing the antimeridian gives a longitude a map can place`() {
        // 179.99° E heading east: the raw formula walks past 180 and produces a
        // figure no map projection will place.
        val point = GeoUtils.destination(0.0, 179.99, 90.0, 5_000.0)
        assertTrue(
            "longitude ${point.longitude} out of range",
            point.longitude >= -180.0 && point.longitude <= 180.0
        )
        assertTrue("expected to wrap negative, got ${point.longitude}", point.longitude < 0.0)
    }

    @Test
    fun `going north over the pole is a number, not NaN`() {
        // asin's domain is the edge case here; a corner this far north is not a
        // parcel, but it must not poison the boundary with NaN if it is typed.
        val point = GeoUtils.destination(89.999, 0.0, 0.0, 50_000.0)
        assertFalse("latitude was NaN", point.latitude.isNaN())
        assertFalse("longitude was NaN", point.longitude.isNaN())
    }

    @Test
    fun `googleMapsLink embeds both coordinates`() {
        val link = GeoUtils.googleMapsLink(bandungLat, bandungLon)
        assertTrue(link, link.contains("$bandungLat,$bandungLon"))
    }
}
