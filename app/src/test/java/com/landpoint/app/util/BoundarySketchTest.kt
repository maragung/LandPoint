package com.landpoint.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * The arithmetic behind the sketch in the PDF.
 *
 * Worth testing on its own because a wrong scale or a flipped axis produces a
 * drawing that still looks like a plausible parcel — just not the one that was
 * measured — and nobody checking a printed report would catch it.
 */
class BoundarySketchTest {

    private val boxW = 220f
    private val boxH = 150f
    private val padding = 6f
    private val usableW = boxW - padding * 2   // 208
    private val usableH = boxH - padding * 2   // 138

    private val metresPerDegree = 6371000.0 * Math.PI / 180.0

    /** A rectangle [eastM] by [northM], corners listed anticlockwise from the SW. */
    private fun rect(
        eastM: Double,
        northM: Double,
        lat: Double = -6.9,
        lon: Double = 107.6
    ): List<GeoPoint> {
        val dLat = northM / metresPerDegree
        val dLon = eastM / (metresPerDegree * cos(Math.toRadians(lat)))
        return listOf(
            GeoPoint(lat, lon),
            GeoPoint(lat, lon + dLon),
            GeoPoint(lat + dLat, lon + dLon),
            GeoPoint(lat + dLat, lon)
        )
    }

    private fun xs(sketch: Sketch) = sketch.outline.map { it.x.toDouble() }
    private fun ys(sketch: Sketch) = sketch.outline.map { it.y.toDouble() }

    @Test
    fun `a square parcel comes out square`() {
        val sketch = BoundarySketch.of(rect(20.0, 20.0), boxW, boxH)!!
        val spanX = xs(sketch).max() - xs(sketch).min()
        val spanY = ys(sketch).max() - ys(sketch).min()
        assertEquals("a square must not be stretched to fill its box", spanX, spanY, 0.5)
    }

    @Test
    fun `the drawing fills whichever direction runs out of room first`() {
        // A square in a landscape box: height is the limit, so it fills the height
        // and sits centred across the width.
        val sketch = BoundarySketch.of(rect(20.0, 20.0), boxW, boxH)!!
        assertEquals(padding.toDouble(), ys(sketch).min(), 0.5)
        assertEquals((padding + usableH).toDouble(), ys(sketch).max(), 0.5)
        assertEquals(
            (padding + (usableW - usableH) / 2).toDouble(),
            xs(sketch).min(),
            0.5
        )
    }

    @Test
    fun `a wide strip is limited by the width instead`() {
        val sketch = BoundarySketch.of(rect(100.0, 10.0), boxW, boxH)!!
        val spanX = xs(sketch).max() - xs(sketch).min()
        val spanY = ys(sketch).max() - ys(sketch).min()
        assertEquals(usableW.toDouble(), spanX, 0.5)
        // A tenth as tall as it is wide, and still a tenth on paper.
        assertEquals(usableW / 10.0, spanY, 0.5)
        assertEquals(padding.toDouble(), xs(sketch).min(), 0.5)
    }

    @Test
    fun `north is up and east is to the right`() {
        val corners = rect(30.0, 40.0)
        val sketch = BoundarySketch.of(corners, boxW, boxH)!!
        val northernmost = corners.indices.maxBy { corners[it].latitude }
        val easternmost = corners.indices.maxBy { corners[it].longitude }
        assertEquals(
            "the northernmost corner must be the highest on the page",
            ys(sketch).min(),
            sketch.outline[northernmost].y.toDouble(),
            0.5
        )
        assertEquals(
            "the easternmost corner must be the furthest right",
            xs(sketch).max(),
            sketch.outline[easternmost].x.toDouble(),
            0.5
        )
    }

    @Test
    fun `nothing is drawn outside the box`() {
        listOf(rect(20.0, 20.0), rect(400.0, 3.0), rect(2.0, 900.0)).forEach { corners ->
            val sketch = BoundarySketch.of(corners, boxW, boxH)!!
            sketch.outline.forEach { point ->
                assertTrue("x ${point.x} escaped the box", point.x >= 0f && point.x <= boxW)
                assertTrue("y ${point.y} escaped the box", point.y >= 0f && point.y <= boxH)
            }
        }
    }

    @Test
    fun `the scale reported is the one the drawing was fitted at`() {
        val sketch = BoundarySketch.of(rect(20.0, 20.0), boxW, boxH)!!
        val spanY = ys(sketch).max() - ys(sketch).min()
        // Points back to the 20 m it started from, which is the whole point of
        // carrying the figure: it is what the scale bar is drawn from.
        assertEquals(20.0, sketch.metresPerPoint * spanY, 0.2)
    }

    @Test
    fun `fewer than three corners is not a boundary to sketch`() {
        assertNull(BoundarySketch.of(emptyList(), boxW, boxH))
        assertNull(BoundarySketch.of(rect(20.0, 20.0).take(2), boxW, boxH))
    }

    @Test
    fun `corners all on one spot leave nothing to draw`() {
        val one = GeoPoint(-6.9, 107.6)
        assertNull(BoundarySketch.of(listOf(one, one, one, one), boxW, boxH))
    }

    @Test
    fun `a line of corners collapses down the middle rather than failing`() {
        // Degenerate but real: three corners typed off a certificate can be
        // collinear. It has a length, so it has a drawing — a line.
        val lat = -6.9
        val step = 30.0 / metresPerDegree
        val line = listOf(
            GeoPoint(lat, 107.6),
            GeoPoint(lat + step, 107.6),
            GeoPoint(lat + step * 2, 107.6)
        )
        val sketch = BoundarySketch.of(line, boxW, boxH)!!
        assertEquals("no east-west span, so every corner sits on one vertical",
            0.0, xs(sketch).max() - xs(sketch).min(), 0.01)
        assertEquals((padding + usableW / 2).toDouble(), xs(sketch).min(), 0.5)
        assertEquals(usableH.toDouble(), ys(sketch).max() - ys(sketch).min(), 0.5)
        assertTrue(sketch.metresPerPoint.isFinite() && sketch.metresPerPoint > 0.0)
    }

    @Test
    fun `a box too small to hold any padding is refused`() {
        assertNull(BoundarySketch.of(rect(20.0, 20.0), 8f, 8f))
    }

    @Test
    fun `the scale bar is a round number that fits`() {
        assertEquals(2.0, BoundarySketch.niceBarMetres(3.0), 1e-9)
        assertEquals(5.0, BoundarySketch.niceBarMetres(8.9), 1e-9)
        assertEquals(10.0, BoundarySketch.niceBarMetres(12.0), 1e-9)
        assertEquals(20.0, BoundarySketch.niceBarMetres(47.0), 1e-9)
        assertEquals(50.0, BoundarySketch.niceBarMetres(99.0), 1e-9)
        assertEquals(100.0, BoundarySketch.niceBarMetres(100.0), 1e-9)
        assertEquals(0.5, BoundarySketch.niceBarMetres(0.7), 1e-9)
    }

    @Test
    fun `a nonsense length gets no bar at all`() {
        assertEquals(0.0, BoundarySketch.niceBarMetres(0.0), 1e-9)
        assertEquals(0.0, BoundarySketch.niceBarMetres(-5.0), 1e-9)
        assertEquals(0.0, BoundarySketch.niceBarMetres(Double.NaN), 1e-9)
        assertEquals(0.0, BoundarySketch.niceBarMetres(Double.POSITIVE_INFINITY), 1e-9)
    }
}
