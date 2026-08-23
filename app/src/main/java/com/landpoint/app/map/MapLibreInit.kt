package com.landpoint.app.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import org.maplibre.android.MapLibre

private const val TAG = "MapLibreInit"

/**
 * Starts the map engine, and keeps it told whether there is a network.
 *
 * MapLibre has to be initialised once per process before any `MapView` is
 * constructed, and it loads its native library while doing so — a few tens of
 * milliseconds on a cold start, which is why it happens in `Application.onCreate`
 * rather than on the first frame of a map screen.
 *
 * No API key is passed and none is needed. `getInstance(Context)` exists precisely
 * for a self-hosted or key-free style, which is the whole reason this app can draw a
 * real map without a Google or Mapbox account.
 */
object MapLibreInit {

    private var started = false

    /**
     * Idempotent, because the engine is initialised from `Application.onCreate` and
     * would also be reached from an instrumentation test that builds its own
     * container. A second `getInstance` is harmless but the callback registration
     * below is not.
     */
    fun start(context: Context) {
        if (started) return
        started = true

        MapLibre.getInstance(context)
        watchConnectivity(context)
    }

    /**
     * Tells the engine when the radio comes and goes.
     *
     * This is what makes offline maps work without the user switching anything. Left
     * to itself MapLibre keeps trying to reach a tile server and waits out the
     * timeout on every request, so a phone with no signal shows a map that fills in
     * slowly and incompletely even where the tiles are already on disk. Told it is
     * offline, it serves what it has immediately and stops asking.
     *
     * It is deliberately *not* a hard gate: `setConnected(true)` does not force a
     * fetch, it only lifts the block. So a wrong answer here costs a little latency,
     * never a blank map.
     *
     * The callback is never unregistered. It outlives every map screen by design —
     * the engine's connectivity state is process-wide, and a callback dropped when
     * the last map closes would leave the next one starting from a stale answer.
     */
    private fun watchConnectivity(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: run {
            // No ConnectivityManager at all is not a real device state, but a
            // headless test environment can present one. Leaving MapLibre on its own
            // default is the right fallback: it will simply try the network.
            Log.w(TAG, "no ConnectivityManager; leaving MapLibre to decide")
            return
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // A network that is up but has not yet proved it reaches anywhere is
            // still worth trying, so validation is not required here. Requiring it
            // would leave the map offline for the second or two every reconnection
            // takes to be confirmed.
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = setConnected(true)
            override fun onLost(network: Network) = setConnected(false)
            override fun onUnavailable() = setConnected(false)
        }

        try {
            manager.registerNetworkCallback(request, callback)
        } catch (e: RuntimeException) {
            // Documented to throw when too many callbacks are registered process-wide.
            // Nothing to recover, and nothing worth crashing over.
            Log.w(TAG, "could not watch connectivity", e)
            return
        }

        // Seed it, because a callback only reports changes from now on and the app
        // may well have started with no signal at all.
        setConnected(manager.activeNetwork != null)
    }

    private fun setConnected(connected: Boolean) {
        runCatching { MapLibre.setConnected(connected) }
            .onFailure { Log.w(TAG, "setConnected($connected) failed", it) }
    }
}
