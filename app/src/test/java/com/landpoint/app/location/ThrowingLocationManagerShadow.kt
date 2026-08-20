package com.landpoint.app.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLocationManager

/**
 * A LocationManager that behaves like a real one when permission is missing.
 *
 * Robolectric's stock shadow returns null from `getLastKnownLocation` and
 * accepts `requestLocationUpdates` regardless of permission state. That makes
 * an un-permissioned test pass against code that would throw on a device — the
 * exact crash being guarded here — so the guard has to be tested against a
 * shadow that throws the way the platform does.
 *
 * Enforcement is opt-in per test via [enforcePermission] so the shadow only
 * bites where a test means it to.
 */
@Implements(LocationManager::class)
class ThrowingLocationManagerShadow : ShadowLocationManager() {

    @Implementation
    override fun getLastKnownLocation(provider: String): Location? {
        assertPermitted()
        return super.getLastKnownLocation(provider)
    }

    @Implementation
    override fun requestLocationUpdates(
        provider: String,
        minTimeMs: Long,
        minDistanceM: Float,
        listener: LocationListener,
        looper: Looper?
    ) {
        assertPermitted()
        super.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, looper)
    }

    private fun assertPermitted() {
        if (!permitted) {
            throw SecurityException(
                "\"$provider\" location provider requires ACCESS_FINE_LOCATION or " +
                    "ACCESS_COARSE_LOCATION permission."
            )
        }
    }

    companion object {
        private const val provider = "gps"

        @Volatile
        private var permitted: Boolean = true

        /** Make every location call throw, as the platform does when denied. */
        fun enforcePermission(granted: Boolean) {
            permitted = granted
        }

        /** Back to permissive, so unrelated tests are unaffected. */
        fun reset() {
            permitted = true
        }
    }
}

/** Convenience for reading the shadow's state in assertions. */
fun Context.locationManager(): LocationManager =
    getSystemService(Context.LOCATION_SERVICE) as LocationManager
