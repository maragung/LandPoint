package com.landpoint.app.ui.detail

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landpoint.app.PendingDeletes
import com.landpoint.app.R
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.OfflineMapStore
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.data.export.PdfExporter
import com.landpoint.app.data.model.Land
import com.landpoint.app.location.LocationProvider
import com.landpoint.app.ui.container
import com.landpoint.app.util.AppStrings
import com.landpoint.app.util.GeoPoint
import com.landpoint.app.util.GeoUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.osmdroid.mapsforge.MapsForgeTileSource

data class LandDetailUiState(
    val land: Land? = null,
    /**
     * The saved corners, decoded once per emission — `Land.boundary` re-parses
     * the stored JSON on every read, and the mini map redraws them on every
     * recomposition of a screen that scrolls.
     */
    val boundary: List<GeoPoint> = emptyList(),
    val isLoading: Boolean = true,
    val dms: Boolean = false,
    val imperial: Boolean = false,
    val areaUnit: SettingsRepository.AreaUnit = SettingsRepository.AreaUnit.SQM,
    val distanceMeters: Double? = null,
    val bearing: Double? = null,
    val message: String? = null
)

class LandDetailViewModel(
    private val repository: LandRepository,
    private val locationProvider: LocationProvider,
    private val pdfExporter: PdfExporter,
    private val strings: AppStrings,
    private val pendingDeletes: PendingDeletes,
    private val offlineMapStore: OfflineMapStore,
    settings: SettingsRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val landId: String = checkNotNull(savedStateHandle["landId"])
    private val currentLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    private val message = MutableStateFlow<String?>(null)

    /**
     * An open file handle rather than state, so it is kept out of the UI state
     * and disposed when the screen goes — the same arrangement as `MapViewModel`.
     */
    private val _vectorSource = MutableStateFlow<MapsForgeTileSource?>(null)
    val vectorSource: StateFlow<MapsForgeTileSource?> = _vectorSource.asStateFlow()

    val uiState: StateFlow<LandDetailUiState> = combine(
        repository.observeLand(landId),
        currentLocation,
        settings.coordFormat,
        settings.units,
        combine(settings.areaUnit, message) { area, msg -> area to msg }
    ) { land, location, format, units, areaAndMessage ->
        val (areaUnit, msg) = areaAndMessage
        val distance = if (land != null && location != null) {
            GeoUtils.distance(location.first, location.second, land.latitude, land.longitude)
        } else null
        val bearing = if (land != null && location != null) {
            GeoUtils.bearing(location.first, location.second, land.latitude, land.longitude)
        } else null

        LandDetailUiState(
            land = land,
            boundary = land?.boundary.orEmpty(),
            isLoading = false,
            dms = format == SettingsRepository.CoordFormat.DMS,
            imperial = units == SettingsRepository.Units.IMPERIAL,
            areaUnit = areaUnit,
            distanceMeters = distance,
            bearing = bearing,
            message = msg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LandDetailUiState()
    )

    init {
        refreshLocation()
        viewModelScope.launch {
            _vectorSource.value = offlineMapStore.vectorTileSource()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Holds every imported .map file open while this screen is alive.
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

    /**
     * Hides this land and closes the screen at once; the delete itself happens
     * when the undo window closes.
     *
     * The old version launched the delete in `viewModelScope` and then popped
     * the screen, which cancelled the scope mid-delete — the photo files could
     * be gone with the row still present. [PendingDeletes] outlives this screen,
     * so the delete now finishes wherever the user goes next.
     */
    fun delete(onDeleted: () -> Unit) {
        pendingDeletes.schedule(setOf(landId))
        onDeleted()
    }

    fun exportPdf(uri: Uri) {
        val land = uiState.value.land ?: return
        viewModelScope.launch {
            val result = pdfExporter.exportSingle(
                uri,
                land,
                uiState.value.dms,
                uiState.value.areaUnit
            )
            message.value = if (result.isFailure) {
                strings.get(R.string.msg_pdf_failed, result.error ?: "")
            } else {
                strings.get(R.string.msg_pdf_saved)
            }
        }
    }

    fun suggestPdfName(): String {
        val name = uiState.value.land?.name
            ?.replace(Regex("[^A-Za-z0-9 _-]"), "")
            ?.trim()
            ?.ifBlank { "land" }
            ?: "land"
        return "landpoint-$name.pdf"
    }

    fun consumeMessage() {
        message.value = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = container()
                LandDetailViewModel(
                    c.repository,
                    c.locationProvider,
                    c.pdfExporter,
                    c.strings,
                    c.pendingDeletes,
                    c.offlineMapStore,
                    c.settings,
                    createSavedStateHandle()
                )
            }
        }
    }
}
