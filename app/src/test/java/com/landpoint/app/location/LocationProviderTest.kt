package com.landpoint.app.location

import android.Manifest
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.toList
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

    /** Reverse geocoding is optional on many devices and must degrade quietly. */
    @Test
    fun `an address lookup returns null rather than throwing`() = runTest {
        denyPermissions()

        assertNull(provider.getAddress(-6.914744, 107.609810))
    }
}
