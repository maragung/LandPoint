package com.landpoint.app.data.model

import com.landpoint.app.util.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerPointCodecTest {

    private val lat = -6.914744
    private val lon = 107.609810

    @Test
    fun `corners survive a round trip with ids and accuracy`() {
        val corners = listOf(
            CornerPoint(id = "c1", latitude = lat, longitude = lon, accuracyM = 4.0),
            CornerPoint(id = "c2", latitude = lat + 0.001, longitude = lon),
            CornerPoint(id = "c3", latitude = lat + 0.001, longitude = lon + 0.001, accuracyM = 3.0),
            CornerPoint(id = "c4", latitude = lat, longitude = lon + 0.001)
        )
        val decoded = GeometryCodec.decodeCorners(GeometryCodec.encodeCorners(corners))
        assertEquals(corners, decoded)
    }

    @Test
    fun `geometry written before ids existed still reads with generated ids`() {
        val old = """{"type":"Polygon","coordinates":[[107.6,-6.9],[107.7,-6.9],[107.7,-6.8]]}"""
        val decoded = GeometryCodec.decodeCorners(old)
        assertEquals(3, decoded.size)
        assertTrue(decoded.all { it.id.isNotBlank() })
        assertEquals(3, decoded.map { it.id }.toSet().size)
        // Plain decode keeps working for old payloads.
        assertEquals(3, GeometryCodec.decode(old).size)
    }

    @Test
    fun `typed corners convert to corners with distinct ids`() {
        val corners = listOf(
            GeoPoint(lat, lon),
            GeoPoint(lat + 0.001, lon),
            GeoPoint(lat, lon + 0.001)
        ).toCorners()
        assertEquals(3, corners.map { it.id }.toSet().size)
        assertEquals(corners.map { it.toGeoPoint() }, GeometryCodec.decode(GeometryCodec.encodeCorners(corners)))
    }

    @Test
    fun `fewer than three corners is not a boundary to store`() {
        assertNull(GeometryCodec.encodeCorners(emptyList()))
        assertNull(GeometryCodec.encodeCorners(listOf(CornerPoint("a", lat, lon))))
    }

    @Test
    fun `unreadable input comes back empty rather than throwing`() {
        assertTrue(GeometryCodec.decodeCorners(null).isEmpty())
        assertTrue(GeometryCodec.decodeCorners("not json").isEmpty())
    }
}
