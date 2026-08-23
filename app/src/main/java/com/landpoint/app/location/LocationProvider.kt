package com.landpoint.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Live state of an averaged capture, emitted as samples arrive. */
data class CaptureProgress(
    val fix: AveragedFix?,
    val samples: Int,
    val done: Boolean
)

/**
 * True when the user has granted either location permission.
 *
 * Lives beside the provider rather than in the UI layer because the provider is
 * the one that must not be called without it — a screen forgetting to check is
 * a cosmetic bug, whereas the provider forgetting is a crash.
 */
fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

/**
 * LocationManager-based provider — no Google Play Services dependency.
 */
class LocationProvider(private val context: Context) {

    private val locationManager: LocationManager
        get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val geocoder: Geocoder? by lazy {
        if (Geocoder.isPresent()) Geocoder(context) else null
    }

    fun hasPermission(): Boolean = context.hasLocationPermission()

    fun isLocationEnabled(): Boolean = runCatching {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)

    /**
     * Providers that are both switched on and safe to query.
     *
     * Empty when permission is missing, so every caller below inherits the
     * permission gate from one place instead of repeating it.
     *
     * From API 31 the platform's own fused provider is asked for first. That is
     * the "fused location provider" done without Google Play Services: it is part
     * of `LocationManager`, and on most devices it is where the vendor's own
     * GNSS/Wi-Fi/sensor blend comes out. GPS and NETWORK are still requested
     * beside it, because a fused implementation may simply forward one of them and
     * on that device the raw providers are all there is. Below API 31 there is no
     * fused provider to ask, so PASSIVE is added instead — it costs no radio time
     * and yields whatever fixes other apps have already paid for.
     *
     * Providers are filtered against [LocationManager.getAllProviders] as well as
     * being tested for being enabled: asking for a name the device does not have
     * throws, and which names exist varies by manufacturer.
     */
    private fun usableProviders(): List<String> {
        if (!hasPermission()) return emptyList()
        return runCatching {
            val manager = locationManager
            val present = manager.allProviders.toSet()
            preferredProviders()
                .filter { it in present && manager.isProviderEnabled(it) }
        }.getOrDefault(emptyList())
    }

    /** The provider names worth asking this Android version for, best first. */
    private fun preferredProviders(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            )
        } else {
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
        }

    /**
     * Last fix the system kept for [provider], or null.
     *
     * This throws [SecurityException] the instant permission is revoked, and a
     * revoke can land between the check above and the call here, so the result
     * is caught rather than trusted.
     */
    @SuppressLint("MissingPermission")
    private fun lastKnown(provider: String): Location? =
        runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()

    /**
     * One-shot location request using best available provider.
     * Returns null if permission denied or location unavailable.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): LocationData? = suspendCancellableCoroutine { cont ->
        // Location access gate: providers look enabled even when no permission
        // is granted, and on a fresh install this is the first location call
        // the app makes. Without the check the throw escapes the coroutine and
        // takes the whole process down (the reported launch crash).
        if (!hasPermission() || !isLocationEnabled()) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val providers = usableProviders()

        if (providers.isEmpty()) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        // Try last-known first
        providers.firstNotNullOfOrNull { lastKnown(it) }
            ?.let {
                if (System.currentTimeMillis() - it.time < 60_000) {
                    cont.resume(it.toLocationData())
                    return@suspendCancellableCoroutine
                }
            }

        // Request fresh location
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                locationManager.removeUpdates(this)
                if (cont.isActive) cont.resume(location.toLocationData())
            }

            @Deprecated("Deprecated in API 29", ReplaceWith(""))
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }

        try {
            locationManager.requestLocationUpdates(
                providers.first(),
                0L,
                0f,
                listener,
                android.os.Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation {
            locationManager.removeUpdates(listener)
        }
    }

    /**
     * Continuous location updates — used for in-app compass / bearing mode.
     */
    @SuppressLint("MissingPermission")
    fun observeLocation(): Flow<LocationData> = callbackFlow {
        val listener = LocationListener { location ->
            trySend(location.toLocationData())
        }

        val providers = usableProviders()

        // Covers "permission not granted" as well as "every provider off": in
        // both cases there is nothing to subscribe to, and requesting anyway
        // would throw on a collector that has no way to catch it.
        if (providers.isEmpty()) {
            close()
            return@callbackFlow
        }

        try {
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    1000L,
                    5f,
                    listener,
                    android.os.Looper.getMainLooper()
                )
            }
        } catch (e: SecurityException) {
            // Permission revoked mid-flight. Ending the stream quietly is the
            // honest signal: the collector cannot act on the exception, and
            // rethrowing it into a viewModelScope would kill the process.
            close()
            return@callbackFlow
        }

        awaitClose {
            locationManager.removeUpdates(listener)
        }
    }

    /**
     * Reads every enabled provider at once and emits a running best estimate.
     *
     * GPS and NETWORK are requested together rather than in priority order:
     * under a roof or heavy canopy the cell-tower fix may be the only one that
     * ever arrives, and outdoors it costs nothing because inverse-variance
     * weighting gives it almost no influence next to a real satellite fix.
     *
     * The flow finishes on its own once [LocationAveraging.shouldStop] is
     * satisfied, so the caller just collects to completion.
     */
    @SuppressLint("MissingPermission")
    fun captureAveraged(): Flow<CaptureProgress> = callbackFlow {
        val providers = usableProviders()

        // No permission or no provider — report a finished capture with no fix
        // so the UI leaves its "capturing" state instead of hanging forever.
        if (providers.isEmpty()) {
            trySend(CaptureProgress(fix = null, samples = 0, done = true))
            close()
            return@callbackFlow
        }

        val started = System.currentTimeMillis()
        val samples = mutableListOf<GeoSample>()

        fun publish(done: Boolean) {
            trySend(
                CaptureProgress(
                    fix = LocationAveraging.average(samples),
                    samples = samples.size,
                    done = done
                )
            )
        }

        // A recent last-known fix gives the user something on screen immediately.
        // Anything older is left out — it may predate them walking here.
        providers.forEach { provider ->
            lastKnown(provider)
                ?.takeIf { System.currentTimeMillis() - it.time < 15_000 }
                ?.let { samples += it.toGeoSample(provider) }
        }
        if (samples.isNotEmpty()) publish(done = false)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (samples.size >= LocationAveraging.MAX_SAMPLES) return
                val sample = location.toGeoSample(location.provider)
                // The same fix can arrive twice — the fused provider often
                // forwards the GPS one verbatim, and both are subscribed. Counting
                // it as two independent readings would make the averaged accuracy
                // look better than the receiver ever managed, which is exactly the
                // number that ends up printed beside someone's boundary.
                if (samples.any { it.isSameFixAs(sample) }) return
                samples += sample
                val stop = LocationAveraging.shouldStop(
                    samples,
                    System.currentTimeMillis() - started
                )
                publish(done = stop)
                if (stop) close()
            }

            @Deprecated("Deprecated in API 29", ReplaceWith(""))
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }

        try {
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    listener,
                    android.os.Looper.getMainLooper()
                )
            }
        } catch (e: SecurityException) {
            trySend(CaptureProgress(fix = null, samples = 0, done = true))
            close()
            return@callbackFlow
        }

        // Backstop: end the capture even if the radios go quiet.
        launch {
            delay(LocationAveraging.WINDOW_MS)
            publish(done = true)
            close()
        }

        awaitClose { locationManager.removeUpdates(listener) }
    }.buffer(Channel.UNLIMITED)

    /**
     * Reverse geocode to address string, or null if unavailable.
     */
    suspend fun getAddress(lat: Double, lon: Double): String? = suspendCancellableCoroutine { cont ->
        val geo = geocoder ?: run {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geo.getFromLocation(lat, lon, 1) { addresses ->
                cont.resume(addresses.firstOrNull()?.toSingleLine())
            }
        } else {
            @Suppress("DEPRECATION")
            val addresses = runCatching { geo.getFromLocation(lat, lon, 1) }.getOrNull()
            cont.resume(addresses?.firstOrNull()?.toSingleLine())
        }
    }

    /**
     * Whether two samples are one fix seen twice.
     *
     * Coordinates are compared exactly on purpose: a forwarded fix is the same
     * `Location` object's numbers, bit for bit, while two genuinely separate
     * readings a metre apart differ far below this in the last decimal places.
     */
    private fun GeoSample.isSameFixAs(other: GeoSample): Boolean =
        timestamp == other.timestamp &&
            latitude == other.latitude &&
            longitude == other.longitude

    private fun Location.toGeoSample(provider: String?) = GeoSample(
        latitude = latitude,
        longitude = longitude,
        altitude = if (hasAltitude()) altitude else null,
        accuracy = if (hasAccuracy()) accuracy else null,
        source = FixSource.of(provider),
        timestamp = time
    )

    private fun Location.toLocationData() = LocationData(
        latitude = latitude,
        longitude = longitude,
        altitude = if (hasAltitude()) altitude else null,
        accuracy = if (hasAccuracy()) accuracy else null,
        bearing = if (hasBearing()) bearing else null,
        speed = if (hasSpeed()) speed else null,
        provider = provider,
        timestamp = time
    )

    private fun Address.toSingleLine(): String {
        val parts = listOfNotNull(
            featureName,
            thoroughfare,
            subLocality,
            locality,
            adminArea,
            countryName
        ).distinct()
        return parts.joinToString(", ")
    }
}
