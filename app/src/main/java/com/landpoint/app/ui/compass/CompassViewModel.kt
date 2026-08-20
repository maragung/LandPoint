package com.landpoint.app.ui.compass

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
import com.landpoint.app.util.GeoUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CompassUiState(
    val land: Land? = null,
    val distanceMeters: Double? = null,
    val bearing: Double? = null,
    val accuracy: Float? = null,
    val imperial: Boolean = false,
    val isWaitingForFix: Boolean = true
)

class CompassViewModel(
    repository: LandRepository,
    private val locationProvider: LocationProvider,
    settings: SettingsRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val landId: String = checkNotNull(savedStateHandle["landId"])
    private val position = MutableStateFlow<Triple<Double, Double, Float?>?>(null)

    val uiState: StateFlow<CompassUiState> = combine(
        repository.observeLand(landId),
        position,
        settings.units
    ) { land, pos, units ->
        val distance = if (land != null && pos != null) {
            GeoUtils.distance(pos.first, pos.second, land.latitude, land.longitude)
        } else null
        val bearing = if (land != null && pos != null) {
            GeoUtils.bearing(pos.first, pos.second, land.latitude, land.longitude)
        } else null

        CompassUiState(
            land = land,
            distanceMeters = distance,
            bearing = bearing,
            accuracy = pos?.third,
            imperial = units == SettingsRepository.Units.IMPERIAL,
            isWaitingForFix = pos == null
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CompassUiState()
    )

    init {
        viewModelScope.launch {
            // The compass keeps whatever fix it last had rather than dying: a
            // failure here means no further updates, not a broken screen.
            locationProvider.observeLocation()
                .catch { }
                .collect { location ->
                    position.value = Triple(location.latitude, location.longitude, location.accuracy)
                }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = container()
                CompassViewModel(
                    c.repository,
                    c.locationProvider,
                    c.settings,
                    createSavedStateHandle()
                )
            }
        }
    }
}
