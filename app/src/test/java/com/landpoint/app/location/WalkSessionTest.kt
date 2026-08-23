package com.landpoint.app.location

import com.landpoint.app.util.GeoPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walk's shared state, tested without Android.
 *
 * [WalkSession] is a process singleton because the service that records and the
 * screen that draws are separate objects with separate lifetimes, and both need
 * the same track. That makes two things worth pinning down here: readings are
 * only ever accepted while a walk is actually running, and the three ways a walk
 * can end are told apart — finished by the user, ended because the fixes stopped
 * arriving, and never started at all because the service was refused.
 */
class WalkSessionTest {

    private val lat = -6.914744
    private val lon = 107.609810

    /** Metres to degrees of latitude, near enough at this scale. */
    private fun northOf(metres: Double) = lat + metres / 111_320.0

    private fun sample(
        latitude: Double = lat,
        longitude: Double = lon,
        accuracy: Float? = 4f
    ) = GeoSample(
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        source = FixSource.GPS,
        timestamp = 0L
    )

    /**
     * A singleton outlives the test that touched it, so every test leaves it idle.
     * Begin-then-end rather than end alone, because that is the only pair that also
     * clears a track and a failure flag.
     */
    @After
    fun reset() {
        WalkSession.begin()
        WalkSession.end()
    }

    @Test
    fun `readings before a walk starts are refused`() {
        assertFalse(WalkSession.isRunning)
        assertFalse(WalkSession.offer(sample()))
        assertTrue(WalkSession.state.value.track.isEmpty())
    }

    @Test
    fun `beginning a walk clears whatever the last one left`() {
        WalkSession.begin()
        WalkSession.offer(sample())
        WalkSession.end()

        WalkSession.begin()

        assertTrue(WalkSession.isRunning)
        assertTrue(WalkSession.state.value.track.isEmpty())
        assertEquals(0, WalkSession.state.value.progress.points)
        assertFalse(WalkSession.state.value.failed)
    }

    @Test
    fun `a phone standing still adds one vertex, not one per fix`() {
        WalkSession.begin()

        assertTrue(WalkSession.offer(sample()))
        // Well inside the 4 m accuracy of the fix, so this is wander, not walking.
        assertFalse(WalkSession.offer(sample(latitude = northOf(1.0))))
        assertFalse(WalkSession.offer(sample(latitude = northOf(2.0))))

        assertEquals(1, WalkSession.state.value.track.size)
    }

    @Test
    fun `walking records vertices and measures the distance between them`() {
        WalkSession.begin()

        WalkSession.offer(sample())
        WalkSession.offer(sample(latitude = northOf(10.0)))
        WalkSession.offer(sample(latitude = northOf(20.0)))

        val progress = WalkSession.state.value.progress
        assertEquals(3, progress.points)
        assertEquals(20.0, progress.walkedM, 0.5)
        // Three collinear points enclose nothing worth reporting as an area.
        assertEquals(0.0, progress.areaSqm ?: 0.0, 0.5)
    }

    @Test
    fun `accuracy the radio itself calls vague never becomes a corner`() {
        WalkSession.begin()

        assertFalse(WalkSession.offer(sample(accuracy = null)))
        assertFalse(WalkSession.offer(sample(accuracy = (WalkTrack.MAX_ACCURACY_M + 1).toFloat())))

        assertTrue(WalkSession.state.value.track.isEmpty())
    }

    @Test
    fun `finishing hands back the track and stops accepting readings`() {
        WalkSession.begin()
        WalkSession.offer(sample())
        WalkSession.offer(sample(latitude = northOf(10.0)))

        val track: List<GeoPoint> = WalkSession.end()

        assertEquals(2, track.size)
        assertFalse(WalkSession.isRunning)
        assertFalse(WalkSession.offer(sample(latitude = northOf(30.0))))
        // Still on screen after finishing: the editor draws this track while the
        // user decides whether to keep it.
        assertEquals(2, WalkSession.state.value.track.size)
    }

    @Test
    fun `finishing twice hands back the same track rather than losing it`() {
        WalkSession.begin()
        WalkSession.offer(sample())

        assertEquals(1, WalkSession.end().size)
        assertEquals(1, WalkSession.end().size)
    }

    @Test
    fun `a refused service is a failure, not an empty walk`() {
        WalkSession.begin()

        WalkSession.fail()

        assertFalse(WalkSession.isRunning)
        assertTrue(WalkSession.state.value.failed)
        assertTrue(WalkSession.state.value.track.isEmpty())
        assertNull(WalkSession.state.value.progress.areaSqm)
    }

    @Test
    fun `a walk that ends by itself is not reported as a failure`() {
        WalkSession.begin()
        WalkSession.offer(sample())

        WalkSession.end()

        assertFalse(WalkSession.state.value.failed)
    }
}
