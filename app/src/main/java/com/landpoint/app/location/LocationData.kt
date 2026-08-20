package com.landpoint.app.location

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val bearing: Float?,
    val timestamp: Long
)
