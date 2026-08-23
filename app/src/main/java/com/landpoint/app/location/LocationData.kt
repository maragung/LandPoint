package com.landpoint.app.location

/**
 * One position as Android reported it, with nothing inferred.
 *
 * Every field except the coordinates is nullable because every field except the
 * coordinates is genuinely optional on a real device: a network fix carries no
 * altitude, a stationary phone reports no bearing, and a receiver that has not
 * yet resolved its clock reports no speed. Substituting zero for "unknown" is how
 * a map ends up drawing a heading the phone never claimed.
 *
 * @param speed metres per second along the ground, when the fix carries one.
 * @param provider which [android.location.LocationManager] provider produced this,
 *   kept so the telemetry panel can say where the fix came from — a GPS fix and a
 *   cell-tower fix at the same accuracy are not equally trustworthy.
 */
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val bearing: Float?,
    val speed: Float? = null,
    val provider: String? = null,
    val timestamp: Long
)
