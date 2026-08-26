package com.landpoint.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mark that hangs on the map before it is a corner.
 *
 * Everything asserted here used to be decided a moment too late — after the corner
 * had been added — so the cases that matter are the ones the user is now shown in
 * advance: that a mark on a side will be slotted into the ring rather than tacked on
 * the end, and that a mark on top of an existing corner will be refused, with the
 * corner named.
 *
 * The nudge cases matter for a different reason. Four arrow buttons are the only way
 * to place a corner to the metre on a phone, and a nudge that quietly does nothing —
 * or that drifts a little each press — is the kind of fault a user only discovers
 * once the area is wrong.
 */
class PendingMarkTest {

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

    // ---- placement ---------------------------------------------------------

    @Test
    fun `the first mark on an empty boundary becomes corner one`() {
        assertEquals(
            Placement.Append(1),
            PendingMark.placement(emptyList(), GeoPoint(lat, lon), edgeToleranceM = 2.0)
        )
    }

    @Test
    fun `a mark out in open ground goes on the end`() {
        // The number is the one the map will label it with, so the confirm bar can
        // say "Corner 5" before the user commits rather than after.
        assertEquals(
            Placement.Append(5),
            PendingMark.placement(square, offset(10.0, 10.0), edgeToleranceM = 2.0)
        )
    }

    @Test
    fun `a mark on a side is slotted into that side`() {
        // Between corner 2 and corner 3, which is what a mark dropped there means:
        // "this corner was missed out". Appending it instead would draw a spike from
        // corner 4 across the plot and back, and the area would come out wrong with
        // nothing on screen to explain why.
        assertEquals(
            Placement.Insert(2),
            PendingMark.placement(square, offset(10.0, 20.0), edgeToleranceM = 2.0)
        )
    }

    @Test
    fun `a mark aimed at nothing in particular is never slotted into a side`() {
        // Zero tolerance is what a mark that did not come from a tap carries — typed
        // coordinates are not aiming at a side, and must not be captured by one.
        assertEquals(
            Placement.Append(5),
            PendingMark.placement(square, offset(0.0, 10.0), edgeToleranceM = 0.0)
        )
    }

    @Test
    fun `a mark on top of a corner is refused, and the corner is named`() {
        // 0.2 m from corner 3. Committing it would give the polygon an edge of no
        // length; "too close" without the number sends the user hunting round four
        // corners to find which one they are standing on.
        assertEquals(
            Placement.TooClose(nearCorner = 3),
            PendingMark.placement(square, offset(20.2, 20.0), edgeToleranceM = 2.0)
        )
    }

    @Test
    fun `a mark just past the separation rule is a corner of its own`() {
        // 0.6 m against the 0.5 m rule in CornerDraft: the corner of a shed really
        // can be this close to the next one, so the guard must not round up.
        assertTrue(
            PendingMark.placement(square, offset(20.6, 20.0), edgeToleranceM = 0.0)
                is Placement.Append
        )
    }

    // ---- nudge -------------------------------------------------------------

    @Test
    fun `a nudge north moves the mark north and nowhere else`() {
        val moved = PendingMark.nudge(GeoPoint(lat, lon), bearingDeg = 0.0, stepM = 1.0)

        assertTrue("north must raise the latitude", moved.latitude > lat)
        assertEquals(lon, moved.longitude, 1e-9)
        assertEquals(
            1.0,
            GeoUtils.distance(lat, lon, moved.latitude, moved.longitude),
            0.001
        )
    }

    @Test
    fun `nudging back and forth returns the mark to where it started`() {
        // A user lining a corner up against a fence post presses these buttons dozens
        // of times. A tenth of a millimetre of drift per press would move the corner
        // off the post while it still looked like it was on it.
        val start = GeoPoint(lat, lon)
        val there = PendingMark.nudge(
            PendingMark.nudge(start, bearingDeg = 0.0, stepM = 0.5),
            bearingDeg = 90.0,
            stepM = 0.5
        )
        val back = PendingMark.nudge(
            PendingMark.nudge(there, bearingDeg = 180.0, stepM = 0.5),
            bearingDeg = 270.0,
            stepM = 0.5
        )

        assertEquals(
            0.0,
            GeoUtils.distance(start.latitude, start.longitude, back.latitude, back.longitude),
            0.001
        )
    }

    @Test
    fun `the finest step the buttons offer still moves the mark`() {
        // 0.1 m is offered in the picker, and at this latitude it is about 9e-7 of a
        // degree — small enough that a sloppier implementation would round it away
        // and leave the arrows doing nothing at all.
        val start = GeoPoint(lat, lon)
        val moved = PendingMark.nudge(start, bearingDeg = 90.0, stepM = 0.1)

        assertEquals(
            0.1,
            GeoUtils.distance(start.latitude, start.longitude, moved.latitude, moved.longitude),
            0.001
        )
    }

    @Test
    fun `a step that is not a distance leaves the mark alone`() {
        // Rather than a NaN latitude, which MapLibre accepts and draws nowhere: the
        // mark would vanish off the map with nothing to say why.
        val start = GeoPoint(lat, lon)

        assertSame(start, PendingMark.nudge(start, bearingDeg = 0.0, stepM = 0.0))
        assertSame(start, PendingMark.nudge(start, bearingDeg = 0.0, stepM = -1.0))
        assertSame(start, PendingMark.nudge(start, bearingDeg = 0.0, stepM = Double.NaN))
        assertSame(
            start,
            PendingMark.nudge(start, bearingDeg = 0.0, stepM = Double.POSITIVE_INFINITY)
        )
        assertSame(start, PendingMark.nudge(start, bearingDeg = Double.NaN, stepM = 1.0))
    }

    @Test
    fun `a nudged mark claims no accuracy of its own`() {
        // It carried a GPS accuracy figure until the user moved it by hand; keeping
        // that figure would print a ±3 m against a position the receiver never
        // reported.
        val fromFix = GeoPoint(lat, lon, accuracyM = 3.4)
        val moved = PendingMark.nudge(fromFix, bearingDeg = 0.0, stepM = 1.0)

        assertEquals(null, moved.accuracyM)
    }
}
