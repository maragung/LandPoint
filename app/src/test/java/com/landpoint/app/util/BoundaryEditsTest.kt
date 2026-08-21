package com.landpoint.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These edits act on a boundary someone has already recorded, so the cases that
 * matter are the destructive ones: an index that no longer exists (a walk
 * recording can rewrite the list under a button press), and a corner that would
 * silently land on top of another.
 */
class BoundaryEditsTest {

    private val lat = -6.914744
    private val lon = 107.609810

    /** A point [northM] metres north and [eastM] metres east of the origin. */
    private fun offset(northM: Double, eastM: Double) = GeoPoint(
        latitude = lat + northM / 111_320.0,
        longitude = lon + eastM / (111_320.0 * Math.cos(Math.toRadians(lat)))
    )

    private val square = listOf(
        offset(0.0, 0.0),
        offset(0.0, 20.0),
        offset(20.0, 20.0),
        offset(20.0, 0.0)
    )

    // ---- removeAt ----------------------------------------------------------

    @Test
    fun `removing a corner drops exactly that one`() {
        val result = BoundaryEdits.removeAt(square, 1)
        assertEquals(3, result.size)
        assertEquals(listOf(square[0], square[2], square[3]), result)
    }

    @Test
    fun `removing an index the list does not have changes nothing`() {
        assertSame(square, BoundaryEdits.removeAt(square, 4))
        assertSame(square, BoundaryEdits.removeAt(square, -1))
        assertSame(emptyList<GeoPoint>(), BoundaryEdits.removeAt(emptyList(), 0))
    }

    // ---- replaceAt ---------------------------------------------------------

    @Test
    fun `replacing a corner leaves the others and the order alone`() {
        val moved = offset(5.0, 5.0)
        val result = BoundaryEdits.replaceAt(square, 2, moved)
        assertEquals(listOf(square[0], square[1], moved, square[3]), result)
    }

    @Test
    fun `replacing an index the list does not have changes nothing`() {
        assertSame(square, BoundaryEdits.replaceAt(square, 9, offset(1.0, 1.0)))
    }

    // ---- insertAt ----------------------------------------------------------

    @Test
    fun `a corner can be inserted between two others`() {
        val extra = offset(0.0, 10.0)
        val result = BoundaryEdits.insertAt(square, 1, extra)
        assertEquals(5, result.size)
        assertEquals(extra, result[1])
        assertEquals(square[1], result[2])
    }

    @Test
    fun `inserting at the size of the list appends`() {
        val extra = offset(30.0, 0.0)
        val result = BoundaryEdits.insertAt(square, square.size, extra)
        assertEquals(extra, result.last())
    }

    @Test
    fun `inserting past the end changes nothing`() {
        assertSame(square, BoundaryEdits.insertAt(square, square.size + 1, offset(1.0, 1.0)))
        assertSame(square, BoundaryEdits.insertAt(square, -1, offset(1.0, 1.0)))
    }

    // ---- swap --------------------------------------------------------------

    @Test
    fun `swapping two corners reorders the ring`() {
        val result = BoundaryEdits.swap(square, 1, 2)
        assertEquals(listOf(square[0], square[2], square[1], square[3]), result)
    }

    @Test
    fun `swapping a corner with itself or with nothing changes nothing`() {
        assertSame(square, BoundaryEdits.swap(square, 1, 1))
        assertSame(square, BoundaryEdits.swap(square, 0, 4))
        assertSame(square, BoundaryEdits.swap(square, -1, 0))
    }

    /**
     * The point of reordering: the same four corners in a different sequence are
     * a different shape. If this ever stops holding, move-up and move-down have
     * become decoration.
     */
    @Test
    fun `reordering the ring changes the area it encloses`() {
        val crossed = BoundaryEdits.swap(square, 1, 2)
        assertTrue(
            "swapping adjacent corners should change the enclosed area",
            Math.abs(PolygonMath.areaSqm(square) - PolygonMath.areaSqm(crossed)) > 1.0
        )
    }

    // ---- isDistinct --------------------------------------------------------

    @Test
    fun `a corner well away from the others is accepted`() {
        assertTrue(BoundaryEdits.isDistinct(square, offset(10.0, 40.0)))
    }

    @Test
    fun `a corner on top of an existing one is refused`() {
        assertFalse(BoundaryEdits.isDistinct(square, square[2]))
    }

    @Test
    fun `the corner being edited does not count as its own duplicate`() {
        // Saving corner 2 again unchanged, and nudging it by centimetres, are
        // both edits a user makes on purpose.
        assertTrue(BoundaryEdits.isDistinct(square, square[2], ignoreIndex = 2))
        assertTrue(
            BoundaryEdits.isDistinct(square, offset(20.05, 20.0), ignoreIndex = 2)
        )
    }

    @Test
    fun `editing one corner onto another is still refused`() {
        assertFalse(BoundaryEdits.isDistinct(square, square[3], ignoreIndex = 2))
    }

    // ---- parseLatLon -------------------------------------------------------

    @Test
    fun `a typed coordinate pair is read`() {
        val point = BoundaryEdits.parseLatLon("-6.914744", "107.609810")
        assertEquals(-6.914744, point!!.latitude, 1e-9)
        assertEquals(107.609810, point.longitude, 1e-9)
    }

    @Test
    fun `surrounding spaces are tolerated`() {
        assertEquals(
            GeoPoint(1.5, 2.5),
            BoundaryEdits.parseLatLon("  1.5 ", " 2.5  ")
        )
    }

    @Test
    fun `blank or non-numeric input is not a coordinate`() {
        assertNull(BoundaryEdits.parseLatLon("", "107.6"))
        assertNull(BoundaryEdits.parseLatLon("-6.9", ""))
        assertNull(BoundaryEdits.parseLatLon("south", "107.6"))
        assertNull(BoundaryEdits.parseLatLon("-6,914744", "107.6"))
    }

    @Test
    fun `coordinates outside the world are refused`() {
        assertNull(BoundaryEdits.parseLatLon("91.0", "0.0"))
        assertNull(BoundaryEdits.parseLatLon("-91.0", "0.0"))
        assertNull(BoundaryEdits.parseLatLon("0.0", "181.0"))
        assertNull(BoundaryEdits.parseLatLon("0.0", "-181.0"))
    }

    @Test
    fun `the poles and the date line are inside the world`() {
        assertEquals(GeoPoint(90.0, 180.0), BoundaryEdits.parseLatLon("90", "180"))
        assertEquals(GeoPoint(-90.0, -180.0), BoundaryEdits.parseLatLon("-90", "-180"))
    }
}
