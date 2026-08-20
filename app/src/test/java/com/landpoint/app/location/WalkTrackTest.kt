package com.landpoint.app.location

import com.landpoint.app.util.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure maths — no Android, no Robolectric. Coordinates are around Bandung so the
 * numbers stay in the range the app is actually used in.
 *
 * The point of these tests is the claim the feature rests on: a raw GPS track
 * records wander as fence line, and the filters have to remove it. So the cases
 * that matter are a phone standing still, a fix the radio itself calls vague,
 * and a straight fence walked in many small steps.
 */
class WalkTrackTest {

    private val lat = -6.914744
    private val lon = 107.609810

    /** Metres to degrees of latitude, near enough at this scale. */
    private fun northOf(metres: Double) = lat + metres / 111_320.0

    private fun sample(
        latitude: Double = lat,
        longitude: Double = lon,
        accuracy: Float? = 5f
    ) = GeoSample(
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        source = FixSource.GPS,
        timestamp = 0L
    )

    @Test
    fun `first fix is kept`() {
        assertTrue(WalkTrack.accept(null, sample()))
    }

    @Test
    fun `fix with no stated accuracy is refused`() {
        assertFalse(WalkTrack.accept(null, sample(accuracy = null)))
    }

    @Test
    fun `fix with nonsense accuracy is refused`() {
        assertFalse(WalkTrack.accept(null, sample(accuracy = 0f)))
        assertFalse(WalkTrack.accept(null, sample(accuracy = -3f)))
        assertFalse(WalkTrack.accept(null, sample(accuracy = Float.NaN)))
    }

    @Test
    fun `fix vaguer than the gate is refused`() {
        val tooVague = (WalkTrack.MAX_ACCURACY_M + 1).toFloat()
        assertFalse(WalkTrack.accept(null, sample(accuracy = tooVague)))
    }

    @Test
    fun `standing still adds nothing`() {
        val previous = GeoPoint(lat, lon)
        // Half a metre of wander, well inside a 5 m fix.
        assertFalse(WalkTrack.accept(previous, sample(latitude = northOf(0.5))))
    }

    @Test
    fun `a step the accuracy cannot explain is kept`() {
        val previous = GeoPoint(lat, lon)
        assertTrue(WalkTrack.accept(previous, sample(latitude = northOf(12.0))))
    }

    @Test
    fun `a vague fix must move further before it counts`() {
        val previous = GeoPoint(lat, lon)
        // 12 m of movement is real against a 5 m fix but not against a 20 m one.
        assertTrue(WalkTrack.accept(previous, sample(latitude = northOf(12.0), accuracy = 5f)))
        assertFalse(WalkTrack.accept(previous, sample(latitude = northOf(12.0), accuracy = 20f)))
    }

    @Test
    fun `simplify collapses a straight run to its ends`() {
        val straight = (0..20).map { GeoPoint(northOf(it * 5.0), lon) }
        val simplified = WalkTrack.simplify(straight, toleranceM = 2.0)
        assertEquals(2, simplified.size)
        assertEquals(straight.first(), simplified.first())
        assertEquals(straight.last(), simplified.last())
    }

    @Test
    fun `simplify keeps a corner`() {
        val corner = listOf(
            GeoPoint(lat, lon),
            GeoPoint(northOf(25.0), lon),
            GeoPoint(northOf(50.0), lon),
            // Turns hard east.
            GeoPoint(northOf(50.0), lon + 50.0 / 110_000.0)
        )
        val simplified = WalkTrack.simplify(corner, toleranceM = 2.0)
        assertTrue("corner must survive", simplified.contains(GeoPoint(northOf(50.0), lon)))
    }

    @Test
    fun `simplify leaves short or untolerated input alone`() {
        val two = listOf(GeoPoint(lat, lon), GeoPoint(northOf(10.0), lon))
        assertEquals(two, WalkTrack.simplify(two, toleranceM = 2.0))
        val three = two + GeoPoint(northOf(20.0), lon)
        assertEquals(three, WalkTrack.simplify(three, toleranceM = 0.0))
    }

    @Test
    fun `close drops a last vertex that sits on the first`() {
        val metresPerDegreeLon = 110_000.0
        val east = 40.0 / metresPerDegreeLon
        val square = listOf(
            GeoPoint(lat, lon),
            GeoPoint(lat, lon + east),
            GeoPoint(northOf(40.0), lon + east),
            GeoPoint(northOf(40.0), lon),
            // Walker returns to within a stride of the start.
            GeoPoint(northOf(1.0), lon)
        )
        val closed = WalkTrack.close(square)
        assertEquals(4, closed.size)
        assertEquals(GeoPoint(northOf(40.0), lon), closed.last())
    }

    @Test
    fun `close keeps a last vertex that is genuinely elsewhere`() {
        val east = 40.0 / 110_000.0
        val open = listOf(
            GeoPoint(lat, lon),
            GeoPoint(lat, lon + east),
            GeoPoint(northOf(40.0), lon + east),
            GeoPoint(northOf(40.0), lon - east)
        )
        val closed = WalkTrack.close(open)
        assertEquals(4, closed.size)
    }

    @Test
    fun `close never returns a degenerate ring`() {
        // Two points a stride apart: trimming would leave a single point, which
        // is not a boundary. Better to hand back something the caller rejects.
        val pair = listOf(GeoPoint(lat, lon), GeoPoint(northOf(1.0), lon))
        val closed = WalkTrack.close(pair)
        assertTrue(closed.size < WalkTrack.MIN_POINTS)
    }

    @Test
    fun `progress has no area until the shape closes`() {
        val one = listOf(GeoPoint(lat, lon))
        assertNull(WalkTrack.progressOf(one).areaSqm)
        val two = one + GeoPoint(northOf(40.0), lon)
        assertNull(WalkTrack.progressOf(two).areaSqm)

        val three = two + GeoPoint(northOf(40.0), lon + 40.0 / 110_000.0)
        val progress = WalkTrack.progressOf(three)
        assertNotNull(progress.areaSqm)
        assertEquals(3, progress.points)
    }

    @Test
    fun `walked distance is the path, not the perimeter`() {
        val leg = 40.0
        val points = listOf(
            GeoPoint(lat, lon),
            GeoPoint(northOf(leg), lon),
            GeoPoint(northOf(2 * leg), lon)
        )
        // Two legs walked, open path: no closing edge back to the start.
        assertEquals(2 * leg, WalkTrack.progressOf(points).walkedM, 1.0)
    }

    /**
     * The whole feature in one case: a 40 m square fence walked at roughly one
     * fix a stride, with a metre of jitter on every fix, must come back as a
     * handful of corners and an area near 1600 m² — not as 160 vertices.
     */
    @Test
    fun `a jittery walked square reduces to a usable boundary`() {
        val metresPerDegreeLat = 111_320.0
        val metresPerDegreeLon = 110_000.0
        val side = 40.0
        val stride = 2.0

        // Deterministic pseudo-jitter: no Random, so a failure is reproducible.
        var seed = 7
        fun jitter(): Double {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            return (seed % 2000) / 1000.0 - 1.0
        }

        val track = mutableListOf<GeoPoint>()
        var previous: GeoPoint? = null
        fun offer(northM: Double, eastM: Double) {
            val candidate = GeoSample(
                latitude = lat + (northM + jitter()) / metresPerDegreeLat,
                longitude = lon + (eastM + jitter()) / metresPerDegreeLon,
                accuracy = 4f,
                source = FixSource.GPS,
                timestamp = 0L
            )
            if (WalkTrack.accept(previous, candidate)) {
                val point = GeoPoint(candidate.latitude, candidate.longitude)
                track += point
                previous = point
            }
        }

        var d = 0.0
        while (d <= side) { offer(0.0, d); d += stride }
        d = 0.0
        while (d <= side) { offer(d, side); d += stride }
        d = side
        while (d >= 0.0) { offer(side, d); d -= stride }
        d = side
        while (d >= 0.0) { offer(d, 0.0); d -= stride }

        val closed = WalkTrack.close(track)
        // Not an absolute count: the spacing gate drops fixes whose step the
        // accuracy cannot explain, so how many survive a jittery walk is not
        // fixed. What must hold is that the walk is recorded densely and the
        // boundary that comes out of it is a handful of corners.
        assertTrue("raw track should be dense: ${track.size}", track.size > 25)
        assertTrue("boundary should be sparse: ${closed.size}", closed.size in 4..12)
        assertTrue(
            "simplification should collapse the track: ${track.size} -> ${closed.size}",
            closed.size * 3 < track.size
        )

        val area = WalkTrack.progressOf(closed).areaSqm!!
        assertEquals(side * side, area, 300.0)
    }
}
