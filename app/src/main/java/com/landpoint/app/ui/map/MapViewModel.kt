package com.landpoint.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.OfflineMapStore
import com.landpoint.app.data.model.Land
import com.landpoint.app.location.LocationProvider
import com.landpoint.app.ui.container
import com.landpoint.app.util.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.osmdroid.mapsforge.MapsForgeTileSource

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
    val currentLocation: Pair<Double, Double>? = null,
    val isLoading: Boolean = true
)

class MapViewModel(
    repository: LandRepository,
    private val locationProvider: LocationProvider,
    private val offlineMapStore: OfflineMapStore
) : ViewModel() {

    private val currentLocation = MutableStateFlow<Pair<Double, Double>?>(null)

    /**
     * Kept out of [MapUiState] because it is an open file handle, not state: it
     * has to be disposed, and it must not be recreated on every land edit.
     */
    private val _vectorSource = MutableStateFlow<MapsForgeTileSource?>(null)
    val vectorSource: StateFlow<MapsForgeTileSource?> = _vectorSource.asStateFlow()

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
        loadVectorSource()
    }

    /**
     * Opens the imported vector maps, if any. Silently does nothing when there
     * are none — an empty offline folder is the normal case, not an error.
     */
    private fun loadVectorSource() {
        viewModelScope.launch {
            _vectorSource.value = offlineMapStore.vectorTileSource()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Holds every .map file open while the map is on screen.
        _vectorSource.value?.dispose()
        _vectorSource.value = null
    }

    fun refreshLocation() {
        viewModelScope.launch {
            locationProvider.getCurrentLocation()?.let {
                currentLocation.value = it.latitude to it.longitude
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = container()
                MapViewModel(c.repository, c.locationProvider, c.offlineMapStore)
            }
        }
    }
}
