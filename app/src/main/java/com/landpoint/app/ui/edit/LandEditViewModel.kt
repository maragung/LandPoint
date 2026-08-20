package com.landpoint.app.ui.edit

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.createSavedStateHandle
import com.landpoint.app.R
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.OfflineMapStore
import com.landpoint.app.data.PhotoStamper
import com.landpoint.app.data.PhotoStore
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.data.StampData
import com.landpoint.app.data.model.GeometryCodec
import com.landpoint.app.data.model.GeometryType
import com.landpoint.app.data.model.LandEntity
import com.landpoint.app.location.AveragedFix
import com.landpoint.app.location.FixQuality
import com.landpoint.app.location.FixSource
import com.landpoint.app.location.GeoSample
import com.landpoint.app.location.WalkTrack
import com.landpoint.app.ui.container
import com.landpoint.app.util.AppStrings
import com.landpoint.app.util.CornerDraft
import com.landpoint.app.util.GeoPoint
import com.landpoint.app.util.PolygonMath
import org.osmdroid.mapsforge.MapsForgeTileSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * A photo in the editor. [persistedId] is null while the land has never been
 * saved — those files exist on disk but have no database row yet.
 */
data class EditPhoto(
    val path: String,
    val persistedId: String? = null,
    /**
     * What the photo is of. A boundary marker shot from the road looks like any
     * other patch of ground a year later, so this is what turns the picture into
     * a record of something.
     */
    val caption: String = ""
)

/**
 * A corner while the map picker is open.
 *
 * [id] is what survives a drag: markers are rebuilt from this list on every
 * change, and an index would shift the moment a corner is undone, so a dragged
 * marker is matched back to its corner by id and never by position in the list.
 * Session-only — the saved boundary is a plain list of [GeoPoint].
 */
data class DraftCorner(
    val id: String,
    val point: GeoPoint
)

data class LandEditUiState(
    val id: String? = null,
    val name: String = "",
    val description: String = "",
    val notes: String = "",
    val parcelNumber: String = "",
    val latitude: String = "",
    val longitude: String = "",
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val address: String? = null,
    val photos: List<EditPhoto> = emptyList(),
    val isCapturing: Boolean = false,
    val sampleCount: Int = 0,
    val rejectedSamples: Int = 0,
    val fixQuality: FixQuality? = null,
    val networkOnly: Boolean = false,
    val areaUnit: SettingsRepository.AreaUnit = SettingsRepository.AreaUnit.SQM,
    /** Mirrors the coordinate-format setting so a stamp reads like the rest of the app. */
    val dms: Boolean = false,
    /** Distance unit for the walk readout, from the same setting the list uses. */
    val units: SettingsRepository.Units = SettingsRepository.Units.METRIC,
    val boundary: List<GeoPoint> = emptyList(),
    val isCapturingCorner: Boolean = false,
    val cornerSamples: Int = 0,
    /** True while the map corner picker is on screen. */
    val isPickerOpen: Boolean = false,
    /**
     * The corners being edited on the map. Seeded from [boundary] when the
     * picker opens and written back only on commit, so cancelling cannot lose a
     * boundary that was already there.
     */
    val draftBoundary: List<DraftCorner> = emptyList(),
    /** True while a walk-around measurement is recording. */
    val isWalking: Boolean = false,
    val walkedM: Double = 0.0,
    val walkPoints: Int = 0,
    val isLoadingAddress: Boolean = false,
    val isSaving: Boolean = false,
    @StringRes val nameError: Int? = null,
    @StringRes val coordinateError: Int? = null,
    val message: String? = null,
    val createdAt: Long? = null
) {
    val isEdit: Boolean get() = createdAt != null
    val hasCoordinates: Boolean
        get() = latitude.toDoubleOrNull() != null && longitude.toDoubleOrNull() != null

    /** A boundary only encloses an area once it has three distinct corners. */
    val hasPolygon: Boolean get() = boundary.size >= 3
    val areaSqm: Double? get() = if (hasPolygon) PolygonMath.areaSqm(boundary) else null
    val perimeterM: Double?
        get() = when {
            hasPolygon -> PolygonMath.perimeterM(boundary)
            boundary.size == 2 -> PolygonMath.pathLengthM(boundary)
            else -> null
        }

    /** The draft as plain points — what the map draws and what commit writes back. */
    val draftPoints: List<GeoPoint> get() = draftBoundary.map { it.point }

    /** Area of the shape being drawn, so the figure moves with each tap. */
    val draftAreaSqm: Double?
        get() = if (draftBoundary.size >= 3) PolygonMath.areaSqm(draftPoints) else null

    /**
     * Where the land's pin ends up. A polygon pins to its centroid so the marker
     * sits on the land itself rather than wherever the phone happened to be
     * standing; a single point pins to the coordinates on the form.
     */
    val pinPoint: GeoPoint?
        get() = if (hasPolygon) PolygonMath.centroid(boundary) else null
}

class LandEditViewModel(
    private val repository: LandRepository,
    private val locationProvider: com.landpoint.app.location.LocationProvider,
    private val photoStore: PhotoStore,
    private val photoStamper: PhotoStamper,
    private val strings: AppStrings,
    private val offlineMapStore: OfflineMapStore,
    settings: SettingsRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(LandEditUiState())
    val uiState: StateFlow<LandEditUiState> = _uiState.asStateFlow()

    /**
     * Kept out of [LandEditUiState] because it is an open file handle, not state.
     * Opened when the picker opens and disposed when it closes, so an edit
     * session that never touches the map never holds a `.map` file open.
     */
    private val _vectorSource = MutableStateFlow<MapsForgeTileSource?>(null)
    val vectorSource: StateFlow<MapsForgeTileSource?> = _vectorSource.asStateFlow()

    /**
     * Where the phone is right now, for the picker's "you are here" dot. Kept
     * apart from the form's latitude/longitude, which on an existing land are
     * the saved coordinates and say nothing about where the user is standing.
     */
    private val _currentLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val currentLocation: StateFlow<Pair<Double, Double>?> = _currentLocation.asStateFlow()

    private val landId: String? = savedStateHandle["landId"]

    /** Held so a second tap restarts the capture instead of racing the first. */
    private var captureJob: Job? = null

    /** Separate from [captureJob] so a corner capture cannot cancel the main fix. */
    private var cornerJob: Job? = null

    /** The walk-around recording, cancelled by stopWalk or by leaving the screen. */
    private var walkJob: Job? = null

    override fun onCleared() {
        // A walk left running would keep the GPS on with nothing listening.
        walkJob?.cancel()
        // Backstop: the picker disposes this itself, but the screen can die with
        // the map still open and a leaked handle keeps the .map file locked.
        releaseVectorSource()
        super.onCleared()
    }

    private fun releaseVectorSource() {
        _vectorSource.value?.dispose()
        _vectorSource.value = null
    }

    init {
        // Display-only preferences, so they are folded into the existing state
        // rather than each being given its own combine().
        viewModelScope.launch {
            settings.areaUnit.collect { unit ->
                _uiState.update { it.copy(areaUnit = unit) }
            }
        }
        viewModelScope.launch {
            settings.coordFormat.collect { format ->
                _uiState.update {
                    it.copy(dms = format == SettingsRepository.CoordFormat.DMS)
                }
            }
        }
        viewModelScope.launch {
            settings.units.collect { units ->
                _uiState.update { it.copy(units = units) }
            }
        }
        if (landId != null) load(landId) else captureLocation()
    }

    private fun load(id: String) {
        viewModelScope.launch {
            repository.getLand(id)?.let { land ->
                // copy() rather than a fresh state so the area unit collected in
                // init survives whichever of the two arrives second.
                _uiState.update { current ->
                    current.copy(
                        id = land.id,
                        name = land.name,
                        description = land.description,
                        notes = land.notes,
                        parcelNumber = land.parcelNumber ?: "",
                        latitude = land.latitude.toString(),
                        longitude = land.longitude.toString(),
                        altitude = land.altitude,
                        accuracy = land.accuracy,
                        address = land.address,
                        photos = land.photos.map { EditPhoto(it.filePath, it.id, it.caption) },
                        boundary = land.boundary,
                        createdAt = land.createdAt
                    )
                }
            }
        }
    }

    /**
     * Takes many readings instead of one and averages them.
     *
     * The screen updates on every sample, so the accuracy figure visibly tightens
     * while the user stands still — which is also the honest signal for when to
     * stop waiting. Capture ends by itself once the fix is good enough or the
     * window closes; [LocationAveraging] does the maths.
     */
    fun captureLocation() {
        captureJob?.cancel()
        if (!locationProvider.isLocationEnabled()) {
            _uiState.update {
                it.copy(isCapturing = false, message = strings.get(R.string.msg_location_off))
            }
            return
        }

        _uiState.update {
            it.copy(isCapturing = true, message = null, sampleCount = 0, fixQuality = null)
        }

        captureJob = viewModelScope.launch {
            var best: AveragedFix? = null
            locationProvider.captureAveraged().collect { progress ->
                val fix = progress.fix
                if (fix == null) {
                    _uiState.update { it.copy(sampleCount = progress.samples) }
                    return@collect
                }
                best = fix
                _uiState.update {
                    it.copy(
                        latitude = formatCoordinate(fix.latitude),
                        longitude = formatCoordinate(fix.longitude),
                        altitude = fix.altitude,
                        accuracy = fix.accuracy.toFloat(),
                        fixQuality = fix.quality,
                        sampleCount = progress.samples,
                        rejectedSamples = fix.rejected,
                        networkOnly = fix.sources == setOf(FixSource.NETWORK),
                        coordinateError = null
                    )
                }
            }

            _uiState.update {
                it.copy(
                    isCapturing = false,
                    message = if (best == null) strings.get(R.string.msg_no_fix) else it.message
                )
            }
            if (best != null) lookupAddress()
        }
    }

    /**
     * Adds the corner the user is standing on, averaged like a normal capture.
     *
     * Corners are taken deliberately, one tap per corner, rather than by
     * auto-recording a walked track: a track samples GPS jitter as if it were
     * shape, which inflates both the perimeter and the area. Standing still at
     * each corner for a few seconds gives a boundary worth printing.
     */
    fun addBoundaryPoint() {
        if (_uiState.value.isCapturingCorner) return
        if (!locationProvider.isLocationEnabled()) {
            _uiState.update { it.copy(message = strings.get(R.string.msg_location_off)) }
            return
        }

        _uiState.update { it.copy(isCapturingCorner = true, message = null) }

        cornerJob = viewModelScope.launch {
            var best: AveragedFix? = null
            locationProvider.captureAveraged().collect { progress ->
                progress.fix?.let { best = it }
                _uiState.update { it.copy(cornerSamples = progress.samples) }
            }

            val fix = best
            _uiState.update { state ->
                if (fix == null) {
                    state.copy(
                        isCapturingCorner = false,
                        cornerSamples = 0,
                        message = strings.get(R.string.msg_no_fix)
                    )
                } else {
                    val candidate = GeoPoint(fix.latitude, fix.longitude)
                    val previous = state.boundary.lastOrNull()
                    if (!PolygonMath.isMeaningfulStep(previous, candidate, fix.accuracy)) {
                        // Too close to the last corner to be a distinct one — most
                        // likely a double tap, so say so rather than silently
                        // adding a degenerate edge.
                        state.copy(
                            isCapturingCorner = false,
                            cornerSamples = 0,
                            message = strings.get(R.string.msg_corner_too_close)
                        )
                    } else {
                        state.copy(
                            boundary = state.boundary + candidate,
                            isCapturingCorner = false,
                            cornerSamples = 0
                        )
                    }
                }
            }
        }
    }

    fun undoBoundaryPoint() {
        _uiState.update {
            if (it.boundary.isEmpty()) it
            else it.copy(boundary = it.boundary.dropLast(1))
        }
    }

    /**
     * Starts recording a boundary by walking it.
     *
     * The corner-by-corner method above stays the more accurate one and is the
     * right choice wherever the corners can be stood on. This exists for the
     * boundary that is too long, too overgrown or too steep for that — a rough
     * shape recorded in one lap beats no shape at all.
     *
     * Points are filtered by [WalkTrack] as they arrive; the raw stream would
     * otherwise record GPS wander as fence line.
     */
    fun startWalk() {
        if (_uiState.value.isWalking) return
        if (!locationProvider.hasPermission()) {
            _uiState.update { it.copy(message = strings.get(R.string.msg_location_permission_needed)) }
            return
        }
        if (!locationProvider.isLocationEnabled()) {
            _uiState.update { it.copy(message = strings.get(R.string.msg_location_off)) }
            return
        }

        // A walk replaces whatever boundary was on screen; warn by way of the
        // undo button rather than a dialog, but never merge two different shapes.
        _uiState.update {
            it.copy(
                isWalking = true,
                walkedM = 0.0,
                walkPoints = 0,
                boundary = emptyList(),
                message = strings.get(R.string.msg_walk_started)
            )
        }

        walkJob = viewModelScope.launch {
            val track = mutableListOf<GeoPoint>()
            locationProvider.observeLocation()
                .catch { /* stream ended, e.g. permission revoked mid-walk */ }
                .collect { location ->
                    val sample = GeoSample(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitude = location.altitude,
                        accuracy = location.accuracy,
                        timestamp = location.timestamp
                    )
                    if (!WalkTrack.accept(track.lastOrNull(), sample)) return@collect
                    track += GeoPoint(sample.latitude, sample.longitude)
                    val progress = WalkTrack.progressOf(track)
                    _uiState.update {
                        it.copy(
                            walkPoints = progress.points,
                            walkedM = progress.walkedM,
                            boundary = track.toList()
                        )
                    }
                }
        }
    }

    /**
     * Ends the walk and simplifies what was recorded into a boundary.
     *
     * A walk that never gathered enough usable fixes leaves the boundary empty
     * and says so, rather than saving a two-point "polygon" that would report a
     * meaningless area.
     */
    fun stopWalk() {
        if (!_uiState.value.isWalking) return
        walkJob?.cancel()
        walkJob = null

        _uiState.update { state ->
            val closed = WalkTrack.close(state.boundary)
            if (closed.size < WalkTrack.MIN_POINTS) {
                state.copy(
                    isWalking = false,
                    walkPoints = 0,
                    walkedM = 0.0,
                    boundary = emptyList(),
                    message = strings.get(R.string.msg_walk_too_short)
                )
            } else {
                state.copy(
                    isWalking = false,
                    walkPoints = closed.size,
                    boundary = closed,
                    message = strings.plural(R.plurals.msg_walk_done, closed.size)
                )
            }
        }
    }

    fun clearBoundary() {
        cornerJob?.cancel()
        _uiState.update {
            it.copy(boundary = emptyList(), isCapturingCorner = false, cornerSamples = 0)
        }
    }

    // ---- Map corner picker ------------------------------------------------
    //
    // Standing on every corner is the accurate method and stays the default. The
    // map is for the corners that cannot be stood on: across a ditch, inside
    // somebody else's crop, out in flooded paddy. Edits are held in a draft and
    // only written back on commit, so backing out of the map leaves whatever
    // boundary was already recorded exactly as it was.

    fun openCornerPicker() {
        val state = _uiState.value
        if (state.isPickerOpen) return
        // A walk rewrites the boundary as it runs; editing the same shape on a
        // map at the same time would have the two fighting over it.
        if (state.isWalking || state.isCapturingCorner) return

        _uiState.update {
            it.copy(
                isPickerOpen = true,
                draftBoundary = it.boundary.map { point -> DraftCorner(newCornerId(), point) },
                message = null
            )
        }

        viewModelScope.launch {
            // Null when no .map file has been imported. The picker still works —
            // it just draws on a blank background.
            _vectorSource.value = offlineMapStore.vectorTileSource()
        }

        viewModelScope.launch {
            if (!locationProvider.hasPermission()) return@launch
            locationProvider.getCurrentLocation()?.let {
                _currentLocation.value = it.latitude to it.longitude
            }
        }
    }

    /** Accepts the drawn shape as the boundary. */
    fun commitCornerPicker() {
        cornerJob?.cancel()
        releaseVectorSource()
        _uiState.update {
            it.copy(
                boundary = it.draftPoints,
                draftBoundary = emptyList(),
                isPickerOpen = false,
                isCapturingCorner = false,
                cornerSamples = 0
            )
        }
    }

    /** Leaves the map without touching the saved boundary. */
    fun closeCornerPicker() {
        // An averaged capture left running would keep the radio on and write
        // into a draft nobody is looking at any more.
        cornerJob?.cancel()
        releaseVectorSource()
        _uiState.update {
            it.copy(
                draftBoundary = emptyList(),
                isPickerOpen = false,
                isCapturingCorner = false,
                cornerSamples = 0
            )
        }
    }

    fun addDraftCornerAt(latitude: Double, longitude: Double) {
        val candidate = GeoPoint(latitude, longitude)
        _uiState.update { state ->
            if (!CornerDraft.acceptTap(state.draftPoints, candidate)) {
                state.copy(message = strings.get(R.string.msg_corner_too_close))
            } else {
                state.copy(draftBoundary = state.draftBoundary + DraftCorner(newCornerId(), candidate))
            }
        }
    }

    /**
     * Moves a corner that was dragged on the map.
     *
     * Looked up by id: the list can be undone or cleared while a finger is still
     * down, and an index captured when the drag started would by then point at a
     * different corner, or none.
     */
    fun moveDraftCorner(id: String, latitude: Double, longitude: Double) {
        _uiState.update { state ->
            if (state.draftBoundary.none { it.id == id }) state
            else state.copy(
                draftBoundary = state.draftBoundary.map { corner ->
                    if (corner.id == id) corner.copy(point = GeoPoint(latitude, longitude))
                    else corner
                }
            )
        }
    }

    fun undoDraftCorner() {
        _uiState.update {
            if (it.draftBoundary.isEmpty()) it
            else it.copy(draftBoundary = it.draftBoundary.dropLast(1))
        }
    }

    fun clearDraftCorners() {
        cornerJob?.cancel()
        _uiState.update {
            it.copy(draftBoundary = emptyList(), isCapturingCorner = false, cornerSamples = 0)
        }
    }

    /**
     * Adds the phone's own position to the draft — the "this corner is already
     * right, take it" path, for when the user is in fact standing on one.
     *
     * Keeps [PolygonMath.isMeaningfulStep] rather than the tap rule: this point
     * comes from GPS and is subject to the same jitter the corner-by-corner
     * capture guards against.
     */
    fun captureDraftCornerFromGps() {
        if (_uiState.value.isCapturingCorner) return
        if (!locationProvider.isLocationEnabled()) {
            _uiState.update { it.copy(message = strings.get(R.string.msg_location_off)) }
            return
        }
        _uiState.update { it.copy(isCapturingCorner = true, message = null) }
        cornerJob = viewModelScope.launch {
            var best: AveragedFix? = null
            locationProvider.captureAveraged().collect { progress ->
                progress.fix?.let { best = it }
                _uiState.update { it.copy(cornerSamples = progress.samples) }
            }
            val fix = best
            _uiState.update { state ->
                if (fix == null) state.copy(
                    isCapturingCorner = false, cornerSamples = 0,
                    message = strings.get(R.string.msg_no_fix)
                )
                else {
                    val candidate = GeoPoint(fix.latitude, fix.longitude)
                    val previous = state.draftBoundary.lastOrNull()?.point
                    if (!PolygonMath.isMeaningfulStep(previous, candidate, fix.accuracy)) {
                        state.copy(
                            isCapturingCorner = false, cornerSamples = 0,
                            message = strings.get(R.string.msg_corner_too_close)
                        )
                    } else {
                        state.copy(
                            draftBoundary = state.draftBoundary + DraftCorner(newCornerId(), candidate),
                            isCapturingCorner = false,
                            cornerSamples = 0
                        )
                    }
                }
            }
        }
    }

    private fun newCornerId(): String = UUID.randomUUID().toString()

    /**
     * Always Locale.US: these strings are parsed straight back into Doubles and
     * land in CSV and JSON exports. An Indonesian locale would write "-6,914744"
     * and the value would fail to parse on save.
     */
    private fun formatCoordinate(value: Double): String =
        "%.6f".format(java.util.Locale.US, value)

    fun lookupAddress() {
        val lat = _uiState.value.latitude.toDoubleOrNull() ?: return
        val lon = _uiState.value.longitude.toDoubleOrNull() ?: return
        _uiState.update { it.copy(isLoadingAddress = true) }
        viewModelScope.launch {
            val address = locationProvider.getAddress(lat, lon)
            _uiState.update {
                it.copy(
                    address = address,
                    isLoadingAddress = false,
                    message = if (address == null) {
                        strings.get(R.string.msg_no_address)
                    } else it.message
                )
            }
        }
    }

    fun setName(value: String) = _uiState.update { it.copy(name = value, nameError = null) }
    fun setDescription(value: String) = _uiState.update { it.copy(description = value) }
    fun setNotes(value: String) = _uiState.update { it.copy(notes = value) }
    fun setParcelNumber(value: String) = _uiState.update { it.copy(parcelNumber = value) }

    fun setLatitude(value: String) =
        _uiState.update { it.copy(latitude = value, coordinateError = null) }

    fun setLongitude(value: String) =
        _uiState.update { it.copy(longitude = value, coordinateError = null) }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    /** Lets the screen surface a problem it detected itself, e.g. a missing app. */
    fun showMessage(text: String) = _uiState.update { it.copy(message = text) }

    fun newCameraTarget(): Pair<File, Uri> = photoStore.newCameraTarget()

    fun onPhotoCaptured(file: File) {
        viewModelScope.launch {
            // Stamped before it is filed away, so the copy kept on disk is the
            // stamped one and there is no unstamped original to leak later.
            stampIfPossible(file)
            val path = runCatching { photoStore.persistCapture(file) }.getOrNull()
            if (path == null) {
                _uiState.update {
                    it.copy(message = strings.get(R.string.msg_photo_save_failed))
                }
            } else {
                attachPhoto(path)
            }
        }
    }

    /**
     * Burns the current fix into a fresh capture.
     *
     * Skipped silently when there is no position yet — an unstamped photo is
     * fine, but a photo stamped with coordinates it was not taken at is not.
     * Gallery imports never reach here for the same reason.
     */
    private suspend fun stampIfPossible(file: File) {
        val state = _uiState.value
        val lat = state.latitude.toDoubleOrNull() ?: return
        val lon = state.longitude.toDoubleOrNull() ?: return
        photoStamper.stamp(
            file,
            StampData(
                latitude = lat,
                longitude = lon,
                accuracyM = state.accuracy,
                altitudeM = state.altitude,
                takenAt = System.currentTimeMillis(),
                address = state.address,
                dms = state.dms
            )
        )
    }

    fun onPhotoPicked(uri: Uri) {
        viewModelScope.launch {
            val path = photoStore.importFromUri(uri)
            if (path == null) {
                _uiState.update {
                    it.copy(message = strings.get(R.string.msg_photo_import_failed))
                }
            } else {
                attachPhoto(path)
            }
        }
    }

    /** Writes a photo row immediately for a saved land; otherwise defers to [save]. */
    private suspend fun attachPhoto(path: String) {
        val state = _uiState.value
        val id = state.id
        if (state.isEdit && id != null) {
            repository.addPhoto(id, path)
            repository.getLand(id)?.let { land ->
                _uiState.update { s ->
                    s.copy(photos = land.photos.map { EditPhoto(it.filePath, it.id, it.caption) })
                }
            }
        } else {
            _uiState.update { it.copy(photos = it.photos + EditPhoto(path)) }
        }
    }

    /**
     * Captions a photo.
     *
     * A photo that already has a row is written through at once, so a caption
     * typed on an existing land survives leaving the editor without saving —
     * the same way attaching and removing a photo already behave here. One with
     * no row yet is held in state until [save] inserts it.
     */
    fun setPhotoCaption(photo: EditPhoto, caption: String) {
        val text = caption.trim()
        viewModelScope.launch {
            photo.persistedId?.let { repository.setPhotoCaption(it, text) }
            _uiState.update { state ->
                state.copy(
                    photos = state.photos.map {
                        if (it.path == photo.path) it.copy(caption = text) else it
                    }
                )
            }
        }
    }

    fun removePhoto(photo: EditPhoto) {
        viewModelScope.launch {
            val persistedId = photo.persistedId
            if (persistedId != null) {
                repository.removePhotoById(persistedId, photo.path)
            } else {
                photoStore.delete(photo.path)
            }
            _uiState.update { state ->
                state.copy(photos = state.photos.filterNot { it.path == photo.path })
            }
        }
    }

    fun save(onSaved: (String) -> Unit) {
        val state = _uiState.value

        val name = state.name.trim()
        val lat = state.latitude.trim().toDoubleOrNull()
        val lon = state.longitude.trim().toDoubleOrNull()

        var valid = true
        if (name.isEmpty()) {
            _uiState.update { it.copy(nameError = R.string.error_name_required) }
            valid = false
        }
        when {
            lat == null || lon == null -> {
                _uiState.update { it.copy(coordinateError = R.string.error_coordinates_required) }
                valid = false
            }
            lat !in -90.0..90.0 -> {
                _uiState.update { it.copy(coordinateError = R.string.error_latitude_range) }
                valid = false
            }
            lon !in -180.0..180.0 -> {
                _uiState.update { it.copy(coordinateError = R.string.error_longitude_range) }
                valid = false
            }
        }
        if (!valid || lat == null || lon == null) return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = state.id ?: UUID.randomUUID().toString()
            val boundary = state.boundary
            val polygon = boundary.size >= 3
            // A polygon's pin is the middle of the land, not the spot the phone
            // was standing on when the first corner was taken — that can easily
            // be outside the plot, which puts the list, the map and "navigate
            // here" on the wrong side of a fence.
            val pin = if (polygon) PolygonMath.centroid(boundary) else null
            val entity = LandEntity(
                id = id,
                name = name,
                description = state.description.trim(),
                notes = state.notes.trim(),
                latitude = pin?.latitude ?: lat,
                longitude = pin?.longitude ?: lon,
                altitude = state.altitude,
                accuracy = state.accuracy,
                address = state.address,
                parcelNumber = state.parcelNumber.trim().ifBlank { null },
                geometryType = if (polygon) GeometryType.POLYGON else GeometryType.POINT,
                geometryJson = if (polygon) GeometryCodec.encode(boundary) else null,
                areaSqm = if (polygon) PolygonMath.areaSqm(boundary) else null,
                createdAt = state.createdAt ?: now,
                updatedAt = now
            )
            repository.save(entity)

            // Photos taken before the very first save have no row yet.
            state.photos.filter { it.persistedId == null }
                .forEach { repository.addPhoto(id, it.path, it.caption) }

            _uiState.update {
                it.copy(isSaving = false, id = id, createdAt = entity.createdAt)
            }
            onSaved(id)
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = container()
                LandEditViewModel(
                    c.repository,
                    c.locationProvider,
                    c.photoStore,
                    c.photoStamper,
                    c.strings,
                    c.offlineMapStore,
                    c.settings,
                    createSavedStateHandle()
                )
            }
        }
    }
}
