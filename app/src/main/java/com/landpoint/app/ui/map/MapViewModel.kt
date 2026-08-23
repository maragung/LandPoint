package com.landpoint.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.model.Land
import com.landpoint.app.location.LocationTracker
import com.landpoint.app.location.TrackingState
import com.landpoint.app.ui.container
import com.landpoint.app.util.GeoPoint
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MapUiState(
    val lands: List<Land> = emptyList(),
    /**
     * Every mapped boundary, by land id.
     *
     * Parsed here rather than read from `Land.boundary` at draw time: that
     * property re-decodes the stored JSON on every read, and the map's overlay
     * block runs on every recomposition over every land on screen. A walked
     * boundary holds hundreds of points, so the difference is a stutter on pan.
     */
    val boundaries: Map<String, List<GeoPoint>> = emptyMap(),
    /**
     * Where the phone is, carrying the accuracy Android reported with it.
     *
     * The accuracy travels with the position because the map draws a circle of that
     * radius on the ground, and a dot with no circle claims a precision no receiver
     * offers.
     */
    val currentLocation: GeoPoint? = null,
    /**
     * Everything else the receiver is saying, for the readout over the map.
     *
     * Present even before there is a position: the satellite count is what tells a
     * user standing under a canopy whether waiting will help.
     */
    val tracking: TrackingState = TrackingState(),
    val isLoading: Boolean = true
)

class MapViewModel(
    repository: LandRepository,
    tracker: LocationTracker
) : ViewModel() {

    /**
     * Live tracking while the map is on screen, and nothing while it is not.
     *
     * `WhileSubscribed` is doing real work here rather than tidying: the flow
     * underneath holds the GNSS callback, the accelerometer, the rotation vector and
     * a location request, and all four stop the moment the last collector goes. Five
     * seconds of grace so that turning the phone — which tears the screen down and
     * builds it again — does not restart the radios and lose the fix.
     */
    private val tracking: StateFlow<TrackingState> = tracker.observe()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TrackingState()
        )

    val uiState: StateFlow<MapUiState> = combine(
        repository.observeLands(),
        tracking
    ) { lands, tracked ->
        MapUiState(
            lands = lands,
            boundaries = lands.filter { it.isPolygon }
                .associate { it.id to it.boundary }
                .filterValues { it.size >= 3 },
            // The map's dot and the readout's numbers are the same fix seen twice, so
            // they are derived from one value rather than kept in step by hand.
            currentLocation = tracked.fix?.let {
                GeoPoint(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracyM = it.accuracyM
                )
            },
            tracking = tracked,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MapUiState()
    )

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = container()
                MapViewModel(c.repository, c.locationTracker)
            }
        }
    }
}
