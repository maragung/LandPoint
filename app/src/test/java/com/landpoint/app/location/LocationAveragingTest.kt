package com.landpoint.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure maths — no Android, no Robolectric. Coordinates are around Bandung so the
 * numbers stay in the same range the app is actually used in.
 */
class LocationAveragingTest {

    private val lat = -6.914744
    private val lon = 107.609810

    /** Metres to degrees of latitude, near enough at this scale. */
    private fun northOf(metres: Double) = lat + metres / 111_320.0

    private fun sample(
        latitude: Double = lat,
        longitude: Double = lon,
        accuracy: Float? = 5f,
        source: FixSource = FixSource.GPS
    ) = GeoSample(
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        source = source,
        timestamp = 0L
    )

    @Test
    fun `no samples gives no fix`() {
        assertNull(LocationAveraging.average(emptyList()))
    }

    @Test
    fun `single sample is returned unchanged`() {
        val fix = LocationAveraging.average(listOf(sample()))
        assertNotNull(fix)
        assertEquals(lat, fix!!.latitude, 1e-9)
        assertEquals(lon, fix.longitude, 1e-9)
        assertEquals(1, fix.used)
        // One reading can never be better than what the radio claimed.
        assertEquals(5.0, fix.accuracy, 1e-6)
    }

    @Test
    fun `identical samples do not invent precision`() {
        val fix = LocationAveraging.average(List(10) { sample() })!!
        // The textbook formula would say 5/sqrt(10) = 1.6 m. GPS errors are
        // correlated, so we refuse to promise better than half a single reading.
        assertTrue("claimed ${fix.accuracy} m", fix.accuracy >= 2.5)
    }

    @Test
    fun `a precise gps fix outweighs a vague tower fix`() {
        val fix = LocationAveraging.average(
            listOf(
                sample(accuracy = 4f, source = FixSource.GPS),
                sample(
                    latitude = northOf(800.0),
                    accuracy = 1200f,
                    source = FixSource.NETWORK
                )
            )
        )!!
        // Inverse-variance weighting should leave the answer essentially on the
        // GPS point — well under a metre of pull from an 800 m away tower fix.
        val drift = com.landpoint.app.util.GeoUtils.distance(lat, lon, fix.latitude, fix.longitude)
        assertTrue("drifted $drift m", drift < 1.0)
    }

    @Test
    fun `a wild reading is rejected`() {
        val samples = List(6) { sample() } + sample(latitude = northOf(400.0))
        val fix = LocationAveraging.average(samples)!!
        assertEquals(1, fix.rejected)
        assertEquals(6, fix.used)
        val drift = com.landpoint.app.util.GeoUtils.distance(lat, lon, fix.latitude, fix.longitude)
        assertTrue("drifted $drift m", drift < 1.0)
    }

    @Test
    fun `scattered readings report worse accuracy than they claim`() {
        // Six readings all claiming 3 m but spread over ~50 m: the spread is the
        // truth and the reported accuracy has to reflect it.
        val samples = (0..5).map { sample(latitude = northOf(it * 10.0), accuracy = 3f) }
        val fix = LocationAveraging.average(samples)!!
        assertTrue("claimed ${fix.accuracy} m", fix.accuracy > 10.0)
    }

    @Test
    fun `network only fix is flagged by its source set`() {
        val fix = LocationAveraging.average(
            List(4) { sample(accuracy = 900f, source = FixSource.NETWORK) }
        )!!
        assertEquals(setOf(FixSource.NETWORK), fix.sources)
        assertEquals(FixQuality.POOR, fix.quality)
    }

    @Test
    fun `missing accuracy does not drag a good fix`() {
        val fix = LocationAveraging.average(
            listOf(
                sample(accuracy = 4f),
                sample(latitude = northOf(300.0), accuracy = null, source = FixSource.NETWORK)
            )
        )!!
        val drift = com.landpoint.app.util.GeoUtils.distance(lat, lon, fix.latitude, fix.longitude)
        assertTrue("drifted $drift m", drift < 1.0)
    }

    @Test
    fun `network accuracy is floored so it cannot outrank gps`() {
        // A tower fix claiming 1 m is not believable; the floor keeps it honest.
        val optimistic = sample(accuracy = 1f, source = FixSource.NETWORK)
        assertTrue(LocationAveraging.sigmaOf(optimistic) >= 30.0)
    }

    @Test
    fun `averaging works across the antimeridian`() {
        val samples = listOf(
            GeoSample(1.0, 179.9999, accuracy = 5f, source = FixSource.GPS),
            GeoSample(1.0, -179.9999, accuracy = 5f, source = FixSource.GPS)
        )
        val fix = LocationAveraging.average(samples)!!
        // The midpoint is the date line itself, not longitude 0 in the Atlantic.
        assertTrue("got ${fix.longitude}", kotlin.math.abs(fix.longitude) > 179.99)
    }

    @Test
    fun `quality thresholds match the labels shown to the user`() {
        assertEquals(FixQuality.EXCELLENT, FixQuality.of(4.0))
        assertEquals(FixQuality.GOOD, FixQuality.of(9.0))
        assertEquals(FixQuality.FAIR, FixQuality.of(25.0))
        assertEquals(FixQuality.POOR, FixQuality.of(80.0))
    }

    @Test
    fun `capture stops once the fix is good enough`() {
        val good = List(LocationAveraging.MIN_SAMPLES) { sample(accuracy = 4f) }
        assertTrue(LocationAveraging.shouldStop(good, elapsedMs = 3_000))
    }

    @Test
    fun `capture keeps going while readings are poor`() {
        val poor = List(LocationAveraging.MIN_SAMPLES) {
            sample(accuracy = 400f, source = FixSource.NETWORK)
        }
        assertTrue(!LocationAveraging.shouldStop(poor, elapsedMs = 3_000))
    }

    @Test
    fun `capture gives up when the window closes`() {
        val poor = List(2) { sample(accuracy = 400f, source = FixSource.NETWORK) }
        assertTrue(LocationAveraging.shouldStop(poor, elapsedMs = LocationAveraging.WINDOW_MS))
    }

    @Test
    fun `median handles even and odd counts`() {
        assertEquals(2.0, LocationAveraging.median(listOf(1.0, 2.0, 3.0)), 1e-9)
        assertEquals(2.5, LocationAveraging.median(listOf(1.0, 2.0, 3.0, 4.0)), 1e-9)
    }
}
