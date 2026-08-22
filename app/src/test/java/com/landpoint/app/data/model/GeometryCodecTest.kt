package com.landpoint.app.data.model

import com.landpoint.app.util.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of a boundary.
 *
 * Most of what is checked here is the per-corner accuracy figure, and most of
 * that is about refusing to trust it: a figure attached to the wrong corner is
 * worse than no figure, because it reads as a measurement of a corner it never
 * measured. Everything else in the app treats what comes out of here as fact.
 */
class GeometryCodecTest {

    private val lat = -6.914744
    private val lon = 107.609810

    private fun typed(count: Int) = List(count) { GeoPoint(lat + it * 0.001, lon + it * 0.001) }

    @Test
    fun `a boundary of typed corners writes no accuracy member at all`() {
        // Nothing measured it, so there is nothing to say — and saying nothing
        // keeps what is written identical to what was written before this member
        // existed, which is what makes the change invisible to older readers.
        val encoded = GeometryCodec.encode(typed(4))!!
        assertFalse(encoded, encoded.contains("accuracy"))
    }

    @Test
    fun `a measured boundary keeps every corner's own figure through a round trip`() {
        val points = listOf(
            GeoPoint(lat, lon, 4.0),
            GeoPoint(lat + 0.001, lon, 12.5),
            // Typed in afterwards to fix a corner the GPS could not reach.
            GeoPoint(lat + 0.001, lon + 0.001),
            GeoPoint(lat, lon + 0.001, 3.0)
        )
        val decoded = GeometryCodec.decode(GeometryCodec.encode(points))
        assertEquals(points.map { it.accuracyM }, decoded.map { it.accuracyM })
        assertEquals(points, decoded)
    }

    @Test
    fun `one measured corner among typed ones is enough to write the member`() {
        val points = typed(4).toMutableList().also { it[2] = it[2].copy(accuracyM = 7.0) }
        val encoded = GeometryCodec.encode(points)!!
        assertTrue(encoded, encoded.contains("accuracy"))
        assertEquals(listOf(null, null, 7.0, null), GeometryCodec.decode(encoded).map { it.accuracyM })
    }

    @Test
    fun `geometry written before accuracy existed still reads`() {
        val old = """{"type":"Polygon","coordinates":[[107.6,-6.9],[107.7,-6.9],[107.7,-6.8]]}"""
        val decoded = GeometryCodec.decode(old)
        assertEquals(3, decoded.size)
        assertTrue(decoded.all { it.accuracyM == null })
        assertEquals(-6.9, decoded[0].latitude, 1e-9)
        assertEquals(107.6, decoded[0].longitude, 1e-9)
    }

    @Test
    fun `a list of the wrong length is ignored rather than lined up by guesswork`() {
        // Two figures for three corners: something edited this and did not know
        // what the second list was for. Which corner lost its figure is exactly
        // what cannot be worked out, so none of them keep one.
        val mismatched = """
            {"type":"Polygon","coordinates":[[107.6,-6.9],[107.7,-6.9],[107.7,-6.8]],
             "accuracy":[4.0,5.0]}
        """.trimIndent()
        val decoded = GeometryCodec.decode(mismatched)
        assertEquals(3, decoded.size)
        assertTrue(decoded.all { it.accuracyM == null })
    }

    @Test
    fun `a figure no receiver could have reported is dropped`() {
        val hostile = """
            {"type":"Polygon","coordinates":[[107.6,-6.9],[107.7,-6.9],[107.7,-6.8]],
             "accuracy":[0.0,-5.0,6.0]}
        """.trimIndent()
        assertEquals(listOf(null, null, 6.0), GeometryCodec.decode(hostile).map { it.accuracyM })
    }

    @Test
    fun `a malformed position does not shift the figures onto the wrong corners`() {
        // The middle position is missing its latitude and is dropped. The figures
        // are read by position in the original list, so the corner that survives
        // after it keeps its own 9.0 rather than inheriting the 5.0 in front.
        val ragged = """
            {"type":"Polygon","coordinates":[[107.6,-6.9],[107.7],[107.7,-6.8]],
             "accuracy":[4.0,5.0,9.0]}
        """.trimIndent()
        val decoded = GeometryCodec.decode(ragged)
        assertEquals(2, decoded.size)
        assertEquals(listOf(4.0, 9.0), decoded.map { it.accuracyM })
    }

    @Test
    fun `fewer than three corners is not a boundary to store`() {
        assertNull(GeometryCodec.encode(emptyList()))
        assertNull(GeometryCodec.encode(typed(2)))
    }

    @Test
    fun `nothing readable comes back as no boundary rather than an exception`() {
        assertTrue(GeometryCodec.decode(null).isEmpty())
        assertTrue(GeometryCodec.decode("").isEmpty())
        assertTrue(GeometryCodec.decode("  ").isEmpty())
        assertTrue(GeometryCodec.decode("not json").isEmpty())
        assertTrue(GeometryCodec.decode("""{"type":"Polygon"}""").isEmpty())
    }
}
