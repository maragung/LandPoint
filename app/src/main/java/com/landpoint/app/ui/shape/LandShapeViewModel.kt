package com.landpoint.app.ui.shape

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.data.model.Land
import com.landpoint.app.location.LocationProvider
import com.landpoint.app.ui.container
import com.landpoint.app.util.GeoPoint
import com.landpoint.app.util.PolygonMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LandShapeUiState(
    val land: Land? = null,
    /**
     * Decoded once per emission, and the area and perimeter with it. A walked
     * boundary runs to hundreds of points, and `Land.boundary` re-parses the
     * stored JSON on every read.
     */
    val boundary: List<GeoPoint> = emptyList(),
    val areaSqm: Double? = null,
    val perimeterM: Double? = null,
    val isLoading: Boolean = true,
    val dms: Boolean = false,
    val areaUnit: SettingsRepository.AreaUnit = SettingsRepository.AreaUnit.SQM,
    /**
     * Where the phone is, carrying the accuracy Android reported with it, so the
     * map can draw a circle of that radius rather than a bare dot.
     */
    val currentLocation: GeoPoint? = null
)

/**
 * The boundary of one land, full screen.
 *
 * Read-only by design: editing lives in the editor, which owns the draft and the
 * undo behaviour. This screen's job is to answer "is that the right shape?" —
 * and to hand over to the editor when the answer is no.
 */
class LandShapeViewModel(
    repository: LandRepository,
    private val locationProvider: LocationProvider,
    settings: SettingsRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val landId: String = checkNotNull(savedStateHandle["landId"])
    private val currentLocation = MutableStateFlow<GeoPoint?>(null)

    val uiState: StateFlow<LandShapeUiState> = combine(
        repository.observeLand(landId),
        currentLocation,
        settings.coordFormat,
        settings.areaUnit
    ) { land, location, format, areaUnit ->
        val ring = land?.boundary.orEmpty()
        val closed = ring.size >= 3

        LandShapeUiState(
            land = land,
            boundary = ring,
            areaSqm = if (closed) PolygonMath.areaSqm(ring) else null,
            perimeterM = if (closed) PolygonMath.perimeterM(ring) else null,
            isLoading = false,
            dms = format == SettingsRepository.CoordFormat.DMS,
            areaUnit = areaUnit,
            currentLocation = location
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LandShapeUiState()
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
                LandShapeViewModel(
                    c.repository,
                    c.locationProvider,
                    c.settings,
                    createSavedStateHandle()
                )
            }
        }
    }
}
