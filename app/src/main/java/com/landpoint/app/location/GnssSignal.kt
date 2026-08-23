package com.landpoint.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * What the satellites are doing, straight from the receiver.
 *
 * This is the only honest source for "is the GPS working" — accuracy tells you how
 * good the answer is, and this tells you why. Eight satellites at 40 dB-Hz and a
 * poor accuracy means the receiver is still solving; two satellites means it is
 * not going to. The distinction is what lets a user decide between waiting and
 * moving into the open.
 *
 * Nothing here needs a location fix, so the flow reports a sky full of satellites
 * even while the position is still unknown.
 */
class GnssSignal(private val context: Context) {

    private val locationManager: LocationManager?
        get() = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /**
     * Satellite status, roughly once a second while collected.
     *
     * Ends quietly — an empty stream — when there is no permission, no receiver, or
     * the platform refuses the registration. A telemetry panel with no satellite
     * figures is a small loss; a crash in a coroutine that cannot catch it is not.
     */
    @SuppressLint("MissingPermission")
    fun observe(): Flow<GnssSnapshot> = callbackFlow {
        val manager = locationManager
        if (manager == null || !context.hasLocationPermission()) {
            close()
            return@callbackFlow
        }

        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                trySend(status.snapshot())
            }

            override fun onStopped() {
                // The receiver has been switched off under us — say so rather than
                // leaving the last count on screen as though it were still true.
                trySend(GnssSnapshot(timestamp = System.currentTimeMillis()))
            }
        }

        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.registerGnssStatusCallback(context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                manager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
            }
        }.getOrDefault(false)

        if (!registered) {
            close()
            return@callbackFlow
        }

        awaitClose { runCatching { manager.unregisterGnssStatusCallback(callback) } }
    }

    /**
     * Reduces one status report to the two numbers a person can act on.
     *
     * The mean carrier-to-noise density is taken over the satellites *used in the
     * fix* only. Averaging every satellite the receiver can hear includes the ones
     * sitting on the horizon at 12 dB-Hz that it has already discarded, which drags
     * the figure down whenever the sky is at its most open.
     */
    private fun GnssStatus.snapshot(): GnssSnapshot {
        val cn0OfUsed = ArrayList<Double>(satelliteCount)
        for (i in 0 until satelliteCount) {
            if (usedInFix(i)) cn0OfUsed += getCn0DbHz(i).toDouble()
        }
        return GnssSnapshot.of(
            visible = satelliteCount,
            cn0OfUsed = cn0OfUsed,
            timestamp = System.currentTimeMillis()
        )
    }
}
