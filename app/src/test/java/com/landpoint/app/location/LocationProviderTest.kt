package com.landpoint.app.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/**
 * The app's own report was "saya coba jalankan app langsung crash".
 *
 * A fresh install has location switched on and no permission granted yet, and
 * the first thing the list screen does is ask for a fix. Every call below is
 * made in exactly that state: permission denied, providers enabled. Before the
 * fix these threw SecurityException out of a coroutine with no handler, which
 * kills the process rather than the request.
 *
 * Robolectric grants all manifest permissions by default *and* its stock
 * LocationManager shadow quietly returns null instead of throwing when they are
 * denied — verified, not assumed. So these tests both revoke the permission and
 * install [ThrowingLocationManagerShadow], which throws the way a device does.
 * Without that shadow every assertion here would pass against the unfixed code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ThrowingLocationManagerShadow::class])
class LocationProviderTest {

    private lateinit var context: Context
    private lateinit var provider: LocationProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = LocationProvider(context)
        enableProviders()
    }

    @After
    fun tearDown() {
        ThrowingLocationManagerShadow.reset()
    }

    private fun shadowApp(): ShadowApplication = shadowOf(context as android.app.Application)

    private fun denyPermissions() {
        shadowApp().denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        ThrowingLocationManagerShadow.enforcePermission(granted = false)
    }

    private fun grantPermissions() {
        shadowApp().grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        ThrowingLocationManagerShadow.enforcePermission(granted = true)
    }

    /** Location switched on at the OS level — the state the crash needed. */
    private fun enableProviders() {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
    }

    @Test
    fun `permission is reported as missing when it was never granted`() {
        denyPermissions()

        assertFalse(provider.hasPermission())
    }

    /**
     * Guards the guard. If this ever passes without throwing, the shadow has
     * stopped enforcing permissions and every other test in this class is
     * vacuous — better to fail loudly here than to ship on a green suite.
     */
    @Test
    fun `the test shadow throws the way a real device does`() {
        denyPermissions()

        try {
            context.locationManager().getLastKnownLocation(LocationManager.GPS_PROVIDER)
            fail("The shadow must throw SecurityException, or these tests prove nothing")
        } catch (expected: SecurityException) {
            // This is the exception the app used to let escape.
        }
    }

    @Test
    fun `permission is reported once granted`() {
        grantPermissions()

        assertTrue(provider.hasPermission())
    }

    /**
     * The launch crash itself: this is the call `LandListViewModel.init` makes
     * before any permission prompt has been shown.
     */
    @Test
    fun `a one-shot fix returns null instead of throwing without permission`() = runTest {
        denyPermissions()

        assertNull(provider.getCurrentLocation())
    }

    /** Same call on the averaged-capture path used by the editor. */
    @Test
    fun `an averaged capture completes without permission`() = runTest {
        denyPermissions()

        val progress = provider.captureAveraged().toList()

        // It must finish rather than hang, and report no fix.
        assertTrue("The capture has to terminate", progress.isNotEmpty())
        assertTrue("The last emission must be marked done", progress.last().done)
        assertNull("There is no fix to report", progress.last().fix)
    }

    /** The compass stream: ends quietly rather than failing its collector. */
    @Test
    fun `the location stream closes without permission instead of failing`() = runTest {
        denyPermissions()

        val emissions = provider.observeLocation().toList()

        assertTrue("No fix can be emitted without permission", emissions.isEmpty())
    }

    /**
     * With location switched off at the OS level there is nothing to read even
     * when permission is held, and that must also be a null rather than a throw.
     */
    @Test
    fun `a one-shot fix returns null when location is switched off`() = runTest {
        grantPermissions()
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)

        assertFalse(provider.isLocationEnabled())
        assertNull(provider.getCurrentLocation())
    }

    /**
     * The jump this release fixes. GPS and NETWORK are subscribed together, and
     * before the arbiter whichever fix arrived last won the listener — so a
     * tower fix landing after a satellite fix dragged the marker hundreds of
     * metres and back.
     */
    @Test
    fun `a tower fix never displaces a satellite fix on the stream`() = runTest {
        grantPermissions()
        val manager = context.locationManager()
        val emissions = mutableListOf<LocationData>()
        val job = launch {
            provider.observeLocation().toList(emissions)
        }
        advanceUntilIdle()

        shadowOf(manager).simulateLocation("gps", location("gps", -6.914744, 107.609810, 4f))
        shadowOf(manager).simulateLocation("network", location("network", -6.9, 107.6, 500f))
        // The shadow hands each fix to the listener through the main looper, which
        // is paused under Robolectric: the coroutine scheduler alone would leave
        // every delivery sitting on it, and the test would prove nothing.
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()

        job.cancel()
        assertTrue(
            "the tower fix must not be emitted after the satellite fix",
            emissions.none { it.provider == "network" }
        )
        assertTrue(emissions.any { it.provider == "gps" })
    }

    /** The other direction: a satellite fix displaces a tower fix immediately. */
    @Test
    fun `a satellite fix displaces a tower fix at once`() = runTest {
        grantPermissions()
        val manager = context.locationManager()
        val emissions = mutableListOf<LocationData>()
        val job = launch {
            provider.observeLocation().toList(emissions)
        }
        advanceUntilIdle()

        shadowOf(manager).simulateLocation("network", location("network", -6.9, 107.6, 500f))
        shadowOf(manager).simulateLocation("gps", location("gps", -6.914744, 107.609810, 4f))
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()

        job.cancel()
        assertTrue(emissions.any { it.provider == "network" })
        assertTrue(emissions.any { it.provider == "gps" })
    }

    /** Robolectric needs a Location built the way the platform delivers one. */
    private fun location(
        provider: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float
    ): Location =
        Location(provider).apply {
            setLatitude(latitude)
            setLongitude(longitude)
            setAccuracy(accuracy)
            setTime(System.currentTimeMillis())
        }

    /** Reverse geocoding is optional on many devices and must degrade quietly. */
    @Test
    fun `an address lookup returns null rather than throwing`() = runTest {
        denyPermissions()

        assertNull(provider.getAddress(-6.914744, 107.609810))
    }
}
