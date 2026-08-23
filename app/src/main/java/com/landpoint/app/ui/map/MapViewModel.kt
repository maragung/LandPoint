package com.landpoint.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.model.Land
import com.landpoint.app.location.LocationProvider
import com.landpoint.app.ui.container
import com.landpoint.app.util.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val isLoading: Boolean = true
)

class MapViewModel(
    repository: LandRepository,
    private val locationProvider: LocationProvider
) : ViewModel() {

    private val currentLocation = MutableStateFlow<GeoPoint?>(null)

    val uiState: StateFlow<MapUiState> = combine(
        repository.observeLands(),
        currentLocation
    ) { lands, location ->
        MapUiState(
            lands = lands,
            boundaries = lands.filter { it.isPolygon }
                .associate { it.id to it.boundary }
                .filterValues { it.size >= 3 },
            currentLocation = location,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MapUiState()
    )

    init {
        refreshLocation()
    }

    fun refreshLocation() {
        viewModelScope.launch {
            locationProvider.getCurrentLocation()?.let { fix ->
                currentLocation.value = GeoPoint(
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracyM = fix.accuracy?.toDouble()
                )
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = container()
                MapViewModel(c.repository, c.locationProvider)
            }
        }
    }
}
