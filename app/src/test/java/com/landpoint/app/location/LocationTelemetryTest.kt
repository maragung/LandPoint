package com.landpoint.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic — no Android, no Robolectric. Every class under test here decides
 * something a user reads off the screen or feels as battery drain, so the tests are
 * written against the behaviour rather than the constants: what a reading means,
 * not which number happens to encode it.
 */
class LocationTelemetryTest {

    // -- Signal strength ---------------------------------------------------

    @Test
    fun `no satellites used is no signal`() {
        assertEquals(SignalStrength.NONE, SignalStrength.of(satellitesUsed = 0, meanCn0DbHz = 45.0))
    }

    @Test
    fun `too few satellites for a fix is weak however loud they are`() {
        assertEquals(SignalStrength.WEAK, SignalStrength.of(satellitesUsed = 3, meanCn0DbHz = 48.0))
    }

    @Test
    fun `many satellites heard faintly is not a strong signal`() {
        assertEquals(SignalStrength.WEAK, SignalStrength.of(satellitesUsed = 11, meanCn0DbHz = 20.0))
    }

    @Test
    fun `open sky with good geometry is strong`() {
        assertEquals(SignalStrength.STRONG, SignalStrength.of(satellitesUsed = 9, meanCn0DbHz = 40.0))
    }

    @Test
    fun `enough satellites at fair carrier to noise is fair`() {
        assertEquals(SignalStrength.FAIR, SignalStrength.of(satellitesUsed = 7, meanCn0DbHz = 30.0))
    }

    @Test
    fun `a receiver reporting no carrier to noise never claims strong`() {
        assertEquals(SignalStrength.FAIR, SignalStrength.of(satellitesUsed = 12, meanCn0DbHz = null))
        assertEquals(SignalStrength.WEAK, SignalStrength.of(satellitesUsed = 5, meanCn0DbHz = null))
    }

    // -- Satellite snapshot ------------------------------------------------

    @Test
    fun `snapshot counts only the satellites used in the fix`() {
        val snapshot = GnssSnapshot.of(
            visible = 14,
            cn0OfUsed = listOf(38.0, 42.0, 40.0, 36.0),
            timestamp = 1_000L
        )
        assertEquals(14, snapshot.visible)
        assertEquals(4, snapshot.used)
        assertEquals(39.0, snapshot.meanCn0DbHz!!, 0.001)
        // Four loud satellites are a usable fix but not an open sky: the count is
        // the arithmetic minimum, so the verdict stops at fair.
        assertEquals(SignalStrength.FAIR, snapshot.strength)
        assertTrue(snapshot.hasReport)
    }

    @Test
    fun `unreported carrier to noise figures are left out of the mean`() {
        // Some chipsets fill the field with zero rather than omitting it. Averaging
        // those in would halve a perfectly good signal.
        val snapshot = GnssSnapshot.of(
            visible = 8,
            cn0OfUsed = listOf(40.0, 0.0, 44.0, 0.0, 42.0, 42.0, 40.0),
            timestamp = 1_000L
        )
        assertEquals(7, snapshot.used)
        assertEquals(41.6, snapshot.meanCn0DbHz!!, 0.001)
        assertEquals(SignalStrength.STRONG, snapshot.strength)
    }

    @Test
    fun `a snapshot nothing has been heard in yet is not a report`() {
        assertFalse(GnssSnapshot().hasReport)
        assertEquals(SignalStrength.NONE, GnssSnapshot().strength)
        assertNull(GnssSnapshot().meanCn0DbHz)
    }

    // -- Adaptive cadence --------------------------------------------------

    @Test
    fun `with no fix yet the app asks as often as it can`() {
        assertEquals(TrackingCadence.URGENT_MS, TrackingCadence.intervalMs(null, moving = false))
        assertEquals(0f, TrackingCadence.minDistanceM(null, moving = false), 0f)
    }

    @Test
    fun `a poor fix is chased regardless of movement`() {
        assertEquals(TrackingCadence.URGENT_MS, TrackingCadence.intervalMs(60.0, moving = false))
        assertEquals(TrackingCadence.URGENT_MS, TrackingCadence.intervalMs(60.0, moving = true))
    }

    @Test
    fun `walking is sampled densely enough not to cut a corner`() {
        assertEquals(TrackingCadence.MOVING_MS, TrackingCadence.intervalMs(8.0, moving = true))
        assertEquals(1f, TrackingCadence.minDistanceM(8.0, moving = true), 0f)
    }

    @Test
    fun `a good fix on a phone at rest is asked for rarely`() {
        assertEquals(TrackingCadence.RESTING_MS, TrackingCadence.intervalMs(6.0, moving = false))
    }

    @Test
    fun `a fix still settling is neither chased nor abandoned`() {
        val interval = TrackingCadence.intervalMs(18.0, moving = false)
        assertEquals(TrackingCadence.SETTLING_MS, interval)
        assertTrue(interval > TrackingCadence.MOVING_MS)
        assertTrue(interval < TrackingCadence.RESTING_MS)
    }

    @Test
    fun `the distance filter never sits below the fix's own uncertainty`() {
        // Reporting movement smaller than the accuracy would be reporting noise.
        assertEquals(4f, TrackingCadence.minDistanceM(8.0, moving = false), 0.001f)
    }

    // -- Motion gate -------------------------------------------------------

    @Test
    fun `a phone lying on a table is not moving`() {
        val gate = MotionGate()
        repeat(40) { gate.onAcceleration(0.02, 0.01, 9.81) }
        assertFalse(gate.moving)
    }

    @Test
    fun `a phone being walked with is moving`() {
        val gate = MotionGate()
        // Footfalls: the magnitude swings by more than a metre per second squared.
        repeat(40) { step -> gate.onAcceleration(0.0, 0.0, if (step % 2 == 0) 8.0 else 12.0) }
        assertTrue(gate.moving)
    }

    @Test
    fun `a knock against a table does not leave the phone marked as moving`() {
        val gate = MotionGate()
        repeat(20) { gate.onAcceleration(0.0, 0.0, 9.81) }
        // Knocked, and settling back. A single jolt does register — it has to, or a
        // first footstep would not either — but it has to fade, and quickly.
        gate.onAcceleration(0.0, 0.0, 14.0)
        gate.onAcceleration(0.0, 0.0, 9.81)
        repeat(20) { gate.onAcceleration(0.0, 0.0, 9.81) }
        assertFalse(gate.moving)
    }

    @Test
    fun `a reported speed above walking pace settles it`() {
        val gate = MotionGate()
        assertFalse(gate.moving)
        gate.onSpeed(1.4)
        assertTrue(gate.moving)
    }

    @Test
    fun `a speed below walking pace is not taken as movement`() {
        val gate = MotionGate()
        gate.onSpeed(0.3)
        assertFalse(gate.moving)
    }

    @Test
    fun `a fix with no speed leaves the accelerometer's verdict alone`() {
        val gate = MotionGate()
        repeat(40) { step -> gate.onAcceleration(0.0, 0.0, if (step % 2 == 0) 8.0 else 12.0) }
        gate.onSpeed(null)
        assertTrue(gate.moving)
    }

    // -- Position smoothing ------------------------------------------------

    private val lat = -6.914744
    private val lon = 107.609810

    /** Metres to degrees of latitude, near enough at this scale. */
    private fun northOf(metres: Double) = lat + metres / 111_320.0

    @Test
    fun `the first fix is reported exactly as it arrived`() {
        val smoother = PositionSmoother()
        val (latOut, lonOut) = smoother.feed(lat, lon, 5.0, moving = false)
        assertEquals(lat, latOut, 0.0)
        assertEquals(lon, lonOut, 0.0)
    }

    @Test
    fun `a stationary phone's wander is damped`() {
        val smoother = PositionSmoother()
        smoother.feed(lat, lon, 5.0, moving = false)
        val (latOut, _) = smoother.feed(northOf(4.0), lon, 5.0, moving = false)
        // Somewhere between the two, and much nearer the first.
        assertTrue(latOut > lat)
        assertTrue(latOut < northOf(2.0))
    }

    @Test
    fun `a walking phone is followed closely`() {
        val resting = PositionSmoother()
        val walking = PositionSmoother()
        resting.feed(lat, lon, 5.0, moving = false)
        walking.feed(lat, lon, 5.0, moving = true)
        val (restingOut, _) = resting.feed(northOf(10.0), lon, 5.0, moving = false)
        val (walkingOut, _) = walking.feed(northOf(10.0), lon, 5.0, moving = true)
        assertTrue(walkingOut > restingOut)
    }

    @Test
    fun `a reflected fix from a stationary phone is held back`() {
        val smoother = PositionSmoother()
        smoother.feed(lat, lon, 4.0, moving = false)
        val (latOut, lonOut) = smoother.feed(northOf(400.0), lon, 4.0, moving = false)
        assertEquals(lat, latOut, 0.0)
        assertEquals(lon, lonOut, 0.0)
    }

    @Test
    fun `a receiver that keeps insisting is eventually believed`() {
        val smoother = PositionSmoother()
        smoother.feed(lat, lon, 4.0, moving = false)
        repeat(5) { smoother.feed(northOf(400.0), lon, 4.0, moving = false) }
        assertNotEquals(lat, smoother.current!!.first)
    }

    @Test
    fun `a large jump is accepted at once while moving`() {
        val smoother = PositionSmoother()
        smoother.feed(lat, lon, 4.0, moving = true)
        val (latOut, _) = smoother.feed(northOf(100.0), lon, 4.0, moving = true)
        assertTrue(latOut > northOf(50.0))
    }

    @Test
    fun `a wide fix is given a wider tolerance before being doubted`() {
        val smoother = PositionSmoother()
        smoother.feed(lat, lon, 80.0, moving = false)
        // Well outside a good fix's tolerance, inside this one's.
        val (latOut, _) = smoother.feed(northOf(150.0), lon, 80.0, moving = false)
        assertNotEquals(lat, latOut)
    }

    @Test
    fun `a vague fix cannot widen the gate and jump through it`() {
        // The bug: a fix that arrived claiming a huge accuracy used to set the
        // outlier tolerance from its own claim, so a jump of three times that
        // was "within tolerance" and the marker leapt. The tolerance now comes
        // from the better of the two accuracies, so this fix is judged by the
        // four-metre one it is landing on.
        val smoother = PositionSmoother()
        smoother.feed(lat, lon, 4.0, moving = false)
        val (latOut, _) = smoother.feed(northOf(500.0), lon, 1000.0, moving = false)
        assertEquals("a 500 m jump is not within a 4 m fix's tolerance", lat, latOut, 0.0)
    }

    @Test
    fun `a vague fix folds in at a weight the screen cannot follow`() {
        // The other half of the jump: a tower fix used to pull with the same
        // strength as the satellite fix before it. The jump here stays inside the
        // 15 m outlier floor so the weight is what is under test, not rejection:
        // weighted by accuracy, ten metres of claim should move the marker by
        // a tenth of a metre, not the 1.5 m an equal-weight fix would.
        val smoother = PositionSmoother()
        smoother.feed(lat, lon, 4.0, moving = false)
        val (latOut, _) = smoother.feed(northOf(10.0), lon, 1000.0, moving = false)
        val shiftM = (latOut - lat) * 111_320.0
        assertTrue("marker moved $shiftM m for a vague fix", shiftM < 0.5)
        assertTrue("but it is never ignored entirely", shiftM > 0.0)
    }

    @Test
    fun `a fix with no accuracy at all is treated as vague, not as perfect`() {
        val smoother = PositionSmoother()
        smoother.feed(lat, lon, 4.0, moving = false)
        val (latOut, _) = smoother.feed(northOf(500.0), lon, null, moving = false)
        assertEquals(lat, latOut, 0.0)
    }

    @Test
    fun `the marker's accuracy only ever improves`() {
        // Once a good fix has been shown, a vague one folded in at a tiny weight
        // must not loosen the gate the next fix is judged against. The vague fix
        // in the middle does move the marker a fraction of a millimetre — that is
        // its tiny weight doing its job — so what is asserted is that the 50 m
        // jump is still refused, not that the marker never moved at all.
        val smoother = PositionSmoother()
        smoother.feed(lat, lon, 4.0, moving = false)
        smoother.feed(northOf(0.5), lon, 1000.0, moving = false)
        val (latOut, _) = smoother.feed(northOf(50.0), lon, 1000.0, moving = false)
        val shiftM = (latOut - lat) * 111_320.0
        assertTrue("the 50 m jump must be refused: marker moved $shiftM m", shiftM < 0.01)
    }

    @Test
    fun `smoothing across the antimeridian stays on the near side`() {
        val smoother = PositionSmoother()
        smoother.feed(0.0, 179.999_9, 5.0, moving = true)
        val (_, lonOut) = smoother.feed(0.0, -179.999_9, 5.0, moving = true)
        // Averaging the raw numbers would land near the prime meridian instead.
        assertTrue("longitude was $lonOut", lonOut > 179.0 || lonOut < -179.0)
    }

    @Test
    fun `reset forgets the previous position`() {
        val smoother = PositionSmoother()
        smoother.feed(lat, lon, 5.0, moving = false)
        smoother.reset()
        assertNull(smoother.current)
        val (latOut, _) = smoother.feed(northOf(400.0), lon, 5.0, moving = false)
        assertEquals(northOf(400.0), latOut, 0.0)
    }

    // -- Which heading to show ---------------------------------------------

    private fun tracking(
        bearingDeg: Double? = null,
        compassDeg: Double? = null,
        moving: Boolean = false,
        accuracyM: Double? = 5.0,
        hasFix: Boolean = true
    ) = TrackingState(
        fix = if (hasFix) {
            LocationTelemetry(
                latitude = lat,
                longitude = lon,
                accuracyM = accuracyM,
                altitudeM = null,
                speedMps = null,
                bearingDeg = bearingDeg,
                timestamp = 0L,
                source = FixSource.GPS
            )
        } else {
            null
        },
        moving = moving,
        compassDeg = compassDeg
    )

    @Test
    fun `a moving phone shows its direction of travel`() {
        val reading = tracking(bearingDeg = 90.0, compassDeg = 270.0, moving = true)
        assertEquals(90.0, reading.heading!!, 0.0)
        assertFalse(reading.headingIsCompass)
    }

    @Test
    fun `a stationary phone shows the compass instead`() {
        val reading = tracking(bearingDeg = 90.0, compassDeg = 270.0, moving = false)
        assertEquals(270.0, reading.heading!!, 0.0)
        assertTrue(reading.headingIsCompass)
    }

    @Test
    fun `a phone with no compass falls back to the reported bearing`() {
        val reading = tracking(bearingDeg = 90.0, compassDeg = null, moving = false)
        assertEquals(90.0, reading.heading!!, 0.0)
        assertFalse(reading.headingIsCompass)
    }

    @Test
    fun `no heading is claimed when neither source has one`() {
        assertNull(tracking().heading)
    }

    @Test
    fun `the compass is shown before there is any fix at all`() {
        // The whole reason the sky, the motion verdict and the compass live outside
        // the fix: they arrive in the first second, and the fix may take thirty.
        val reading = tracking(compassDeg = 200.0, hasFix = false)
        assertFalse(reading.hasFix)
        assertEquals(200.0, reading.heading!!, 0.0)
        assertTrue(reading.headingIsCompass)
    }

    @Test
    fun `a fix with no accuracy is given no quality verdict`() {
        assertNull(tracking(accuracyM = null).fix!!.quality)
        assertEquals(FixQuality.of(5.0), tracking(accuracyM = 5.0).fix!!.quality)
    }

    // -- Compass smoothing -------------------------------------------------

    @Test
    fun `the first heading is taken as it comes`() {
        assertEquals(42.0, HeadingSmoother().feed(42.0), 0.001)
    }

    @Test
    fun `heading is smoothed towards a new direction`() {
        val smoother = HeadingSmoother()
        smoother.feed(0.0)
        val next = smoother.feed(40.0)
        assertTrue(next > 0.0)
        assertTrue(next < 40.0)
    }

    @Test
    fun `crossing north goes the short way round`() {
        val smoother = HeadingSmoother()
        smoother.feed(350.0)
        val next = smoother.feed(10.0)
        // The long way would drag the needle back through west.
        assertTrue("heading was $next", next > 350.0 || next < 10.0)
    }

    @Test
    fun `a smoothed heading stays inside one turn`() {
        val smoother = HeadingSmoother()
        var last = smoother.feed(350.0)
        repeat(80) { last = smoother.feed(10.0) }
        assertTrue("heading was $last", last >= 0.0 && last < 360.0)
    }

    @Test
    fun `a settled heading converges on what the sensor reports`() {
        val smoother = HeadingSmoother()
        smoother.feed(0.0)
        repeat(200) { smoother.feed(120.0) }
        assertEquals(120.0, smoother.current!!, 0.5)
    }
}
