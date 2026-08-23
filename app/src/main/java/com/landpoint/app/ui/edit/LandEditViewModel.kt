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
import com.landpoint.app.location.WalkRecorder
import com.landpoint.app.location.WalkTrack
import com.landpoint.app.ui.container
import com.landpoint.app.util.AppStrings
import com.landpoint.app.util.BoundaryEdits
import com.landpoint.app.util.CornerDraft
import com.landpoint.app.util.GeoPoint
import com.landpoint.app.util.GeoUtils
import com.landpoint.app.util.PolygonMath
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * Another saved land offered as a source of corners.
 *
 * Held with its corners already decoded, because the sheet draws them and the
 * copy uses them, and [Land.boundary] re-parses its JSON on every read.
 */
data class NeighbourLand(
    val id: String,
    val name: String,
    val corners: List<GeoPoint>,
    /** Metres from this land to the nearest corner of that one, where known. */
    val distanceM: Double?
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
    /**
     * The corner picked on the map, if any. Held as an id rather than an index
     * for the same reason a drag is: the list is rebuilt on every change.
     */
    val selectedCornerId: String? = null,
    /**
     * Other saved lands whose corners can be borrowed, nearest first. Loaded on
     * demand rather than held all the time: it is a list of every land in the
     * database and it is wanted once, while the sheet is open.
     */
    val neighbours: List<NeighbourLand> = emptyList(),
    val isLoadingNeighbours: Boolean = false,
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

    /**
     * The two sides that cross each other, if any do, numbered from 1.
     *
     * Not computed for a walked track. Checking every pair of sides is quadratic
     * and this is a getter read on each recomposition, and a track of six hundred
     * readings would be checked a hundred and eighty thousand pairs at a time.
     * More to the point, someone walking the edge of a field does cross their own
     * path near where they started, and that is not the mistake this is looking
     * for: a corner typed or tapped out of sequence.
     */
    val selfCrossing: Pair<Int, Int>?
        get() = if (boundary.size in 4..CROSSING_CHECK_LIMIT) {
            PolygonMath.selfCrossing(boundary)
        } else {
            null
        }

    /** The draft as plain points — what the map draws and what commit writes back. */
    val draftPoints: List<GeoPoint> get() = draftBoundary.map { it.point }

    /** Position in the drawn ring of the selected corner, counting from 1. */
    val selectedCornerNumber: Int?
        get() = selectedCornerId
            ?.let { id -> draftBoundary.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?.plus(1)

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

/**
 * Above this many corners the boundary was walked rather than placed, and the
 * crossing check is both too expensive for a getter and looking for a mistake
 * that a walked track does not make.
 */
private const val CROSSING_CHECK_LIMIT = 60

class LandEditViewModel(
    private val repository: LandRepository,
    private val locationProvider: com.landpoint.app.location.LocationProvider,
    private val walkRecorder: WalkRecorder,
    private val photoStore: PhotoStore,
    private val photoStamper: PhotoStamper,
    private val strings: AppStrings,
    settings: SettingsRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(LandEditUiState())
    val uiState: StateFlow<LandEditUiState> = _uiState.asStateFlow()

    /**
     * Where the phone is right now, for the picker's "you are here" dot, with the
     * accuracy Android reported alongside it so the map can draw a circle of that
     * radius rather than a bare point. Kept apart from the form's
     * latitude/longitude, which on an existing land are the saved coordinates and
     * say nothing about where the user is standing.
     */
    private val _currentLocation = MutableStateFlow<GeoPoint?>(null)
    val currentLocation: StateFlow<GeoPoint?> = _currentLocation.asStateFlow()

    private val landId: String? = savedStateHandle["landId"]

    /** Held so a second tap restarts the capture instead of racing the first. */
    private var captureJob: Job? = null

    /** Separate from [captureJob] so a corner capture cannot cancel the main fix. */
    private var cornerJob: Job? = null

    /**
     * Watches the walk in progress. Not the recording itself — that belongs to the
     * service, which is why a locked screen does not interrupt it.
     */
    private var walkJob: Job? = null

    override fun onCleared() {
        walkJob?.cancel()
        // Leaving the editor ends the walk. The service exists so a *locked screen*
        // keeps recording, not so recording outlives the screen that would show the
        // result: a walk still running here would hold the GPS open for a boundary
        // nobody can see or finish.
        if (walkRecorder.state.value.running) walkRecorder.stop()
        super.onCleared()
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
                    val candidate = GeoPoint(fix.latitude, fix.longitude, fix.accuracy)
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

        // Android refuses a foreground service started from the background, and a
        // walk that is not being recorded must not look like one that is.
        if (!walkRecorder.start()) {
            _uiState.update { it.copy(message = strings.get(R.string.msg_walk_start_failed)) }
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

        // Recording runs in a foreground service and the track lives outside this
        // ViewModel, so what is left here is to follow it. That is also how a walk
        // ended from the notification reaches the screen: the state simply stops
        // being a running one, and [finishWalk] takes it from there.
        walkJob = viewModelScope.launch {
            walkRecorder.state.collect { walk ->
                if (!walk.running) {
                    finishWalk(walk.track, failed = walk.failed)
                    return@collect
                }
                _uiState.update {
                    it.copy(
                        walkPoints = walk.progress.points,
                        walkedM = walk.progress.walkedM,
                        boundary = walk.track
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
        // Stopping the recorder is enough: it ends the session, the observer above
        // sees a walk that is no longer running, and the finish happens in one place
        // whether the tap came from here or from the notification.
        walkRecorder.stop()
    }

    /**
     * Turns a finished recording into a boundary, or explains why there is none.
     *
     * Three outcomes, kept apart because the answer to each is different: Android
     * refused to start the service, the walk ran but gathered too few usable fixes,
     * or there is a ring to keep.
     */
    private fun finishWalk(track: List<GeoPoint>, failed: Boolean) {
        walkJob?.cancel()
        walkJob = null

        _uiState.update { state ->
            val closed = if (failed) emptyList() else WalkTrack.close(track)
            when {
                failed -> state.copy(
                    isWalking = false,
                    walkPoints = 0,
                    walkedM = 0.0,
                    boundary = emptyList(),
                    message = strings.get(R.string.msg_walk_start_failed)
                )

                closed.size < WalkTrack.MIN_POINTS -> state.copy(
                    isWalking = false,
                    walkPoints = 0,
                    walkedM = 0.0,
                    boundary = emptyList(),
                    message = strings.get(R.string.msg_walk_too_short)
                )

                else -> state.copy(
                    isWalking = false,
                    walkPoints = closed.size,
                    walkedM = state.walkedM,
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

    // ---- Editing corners one at a time ------------------------------------
    //
    // Capture puts corners in; these take them apart again. Two things make this
    // necessary rather than convenient: a boundary is often copied off a survey
    // letter, where the coordinates are printed and there is nothing to stand
    // on, and the *order* of the corners is the outline — a ring recorded out of
    // sequence draws a bow tie and reports the wrong area.
    //
    // All of them refuse while a walk or an averaged capture is running, which is
    // the same guard `openCornerPicker` uses: those two rewrite the boundary
    // underneath, and an edit interleaved with them would be lost or fight them.

    /**
     * Adds a typed corner, or returns the reason it could not be added.
     *
     * Null means it went in. Anything else is a string resource for the caller to
     * show *inside its own dialog*, beside the fields, rather than as a snackbar
     * the dialog covers — and the dialog keeps the typed text, because retyping
     * both coordinates over one wrong digit is how people give up on entering a
     * boundary at all.
     */
    @StringRes
    fun addBoundaryPointManual(latitude: String, longitude: String): Int? {
        if (boundaryEditsBlocked()) return R.string.error_corner_busy
        val point = BoundaryEdits.parseLatLon(latitude, longitude)
            ?: return R.string.error_corner_invalid
        if (!BoundaryEdits.isDistinct(_uiState.value.boundary, point)) {
            return R.string.error_corner_duplicate
        }
        _uiState.update { it.copy(boundary = it.boundary + point) }
        return null
    }

    /** Retypes one corner in place, keeping its position in the ring. */
    @StringRes
    fun updateBoundaryPoint(index: Int, latitude: String, longitude: String): Int? {
        if (boundaryEditsBlocked()) return R.string.error_corner_busy
        val point = BoundaryEdits.parseLatLon(latitude, longitude)
            ?: return R.string.error_corner_invalid
        // The corner being edited is excluded from the duplicate check, or
        // correcting a typo in the longitude alone would be refused by itself.
        if (!BoundaryEdits.isDistinct(_uiState.value.boundary, point, ignoreIndex = index)) {
            return R.string.error_corner_duplicate
        }
        _uiState.update { it.copy(boundary = BoundaryEdits.replaceAt(it.boundary, index, point)) }
        return null
    }

    /**
     * Types a corner into the middle of the ring rather than onto the end.
     *
     * [index] is where it lands, so `1` puts it between corner #1 and corner #2.
     * Appending and then pressing move-up until it arrives is the same edit, and
     * on a seven-corner parcel it is five presses that each redraw the outline
     * into a shape the user never meant.
     */
    @StringRes
    fun insertBoundaryPointManual(index: Int, latitude: String, longitude: String): Int? {
        if (boundaryEditsBlocked()) return R.string.error_corner_busy
        val point = BoundaryEdits.parseLatLon(latitude, longitude)
            ?: return R.string.error_corner_invalid
        if (!BoundaryEdits.isDistinct(_uiState.value.boundary, point)) {
            return R.string.error_corner_duplicate
        }
        _uiState.update { it.copy(boundary = BoundaryEdits.insertAt(it.boundary, index, point)) }
        return null
    }

    /**
     * Adds the corner a survey letter states as a bearing and a length from the
     * corner before it, rather than as coordinates.
     *
     * This is how most of these boundaries are actually written down: one corner
     * fixed, then each side as an azimuth and a distance. Typed in that form the
     * figures go in as printed and the arithmetic is the app's problem, instead
     * of the owner converting eight sides to coordinates by hand and having no
     * way to find which one they got wrong.
     *
     * The new corner lands straight after [fromIndex], so measuring from the last
     * corner continues the ring and measuring from a middle one splits its side.
     */
    @StringRes
    fun insertBoundaryPointFromBearing(fromIndex: Int, bearing: String, distance: String): Int? {
        if (boundaryEditsBlocked()) return R.string.error_corner_busy
        val origin = _uiState.value.boundary.getOrNull(fromIndex)
            ?: return R.string.error_corner_no_origin
        val azimuth = BoundaryEdits.parseBearing(bearing) ?: return R.string.error_corner_bearing
        val metres = BoundaryEdits.parseDistance(distance) ?: return R.string.error_corner_distance
        val point = GeoUtils.destination(origin.latitude, origin.longitude, azimuth, metres)
        if (!BoundaryEdits.isDistinct(_uiState.value.boundary, point)) {
            return R.string.error_corner_duplicate
        }
        _uiState.update {
            it.copy(boundary = BoundaryEdits.insertAt(it.boundary, fromIndex + 1, point))
        }
        return null
    }

    fun removeBoundaryPoint(index: Int) {
        if (boundaryEditsBlocked()) return
        _uiState.update { it.copy(boundary = BoundaryEdits.removeAt(it.boundary, index)) }
    }

    fun moveBoundaryPointUp(index: Int) = swapBoundaryPoints(index, index - 1)

    fun moveBoundaryPointDown(index: Int) = swapBoundaryPoints(index, index + 1)

    private fun swapBoundaryPoints(from: Int, to: Int) {
        if (boundaryEditsBlocked()) return
        _uiState.update { it.copy(boundary = BoundaryEdits.swap(it.boundary, from, to)) }
    }

    /**
     * Brings in corners from a saved land whose boundary passes near this one.
     *
     * Called when the "from neighbour" sheet opens. Held on demand rather than
     * pre-loaded: the list is every land in the database, and someone using the
     * app for three neighbours on the other side of the district does not benefit
     * from that query running six times through the form.
     */
    fun loadNeighbours() {
        val thisId = _uiState.value.id
        val from = neighbourReference()
        _uiState.update { it.copy(isLoadingNeighbours = true) }
        viewModelScope.launch {
            val lands = repository.getAllLands()
                .filter { it.id != thisId && it.isPolygon }
                .mapNotNull { land ->
                    val corners = land.boundary
                    if (corners.isEmpty()) return@mapNotNull null
                    NeighbourLand(
                        id = land.id,
                        name = land.name,
                        corners = corners,
                        distanceM = from?.let {
                            PolygonMath.nearestCornerM(corners, it.latitude, it.longitude)
                        }
                    )
                }
                // Nearest first: the land that shares a peg with this one is the
                // land standing next to it. With nothing to measure from — a form
                // with no pin and no corner yet — name order is all there is.
                .sortedWith(
                    compareBy<NeighbourLand>({ it.distanceM ?: Double.MAX_VALUE }, { it.name })
                )
            _uiState.update { it.copy(neighbours = lands, isLoadingNeighbours = false) }
        }
    }

    /**
     * Where "near" is measured from: the corner the ring would continue from, or
     * failing that the coordinates on the form.
     *
     * Not the centroid, which does not exist until three corners do. A parcel
     * being recorded beside a known one has its pin long before it has a corner,
     * and that is exactly when borrowing a shared side is most useful.
     */
    private fun neighbourReference(): GeoPoint? {
        val state = _uiState.value
        return state.boundary.lastOrNull()
            ?: BoundaryEdits.parseLatLon(state.latitude, state.longitude)
    }

    /**
     * Adds whichever corners of [neighbourId] the user ticked, skipping any that
     * duplicate a corner already here.
     *
     * The skip count is returned through [showMessage] rather than swallowed:
     * someone who ticks four and gets two corners has to be told which of those
     * happened, or it looks like half their work disappeared. The message is not
     * an error — reusing the shared peg is the right call — but it is a material
     * outcome they need to see.
     */
    fun appendFromNeighbour(neighbourId: String, selectedIndices: Set<Int>) {
        if (boundaryEditsBlocked()) {
            showMessage(strings.get(R.string.error_corner_busy))
            return
        }
        val land = _uiState.value.neighbours.find { it.id == neighbourId } ?: return
        val additions = selectedIndices.sorted().mapNotNull { land.corners.getOrNull(it) }
        if (additions.isEmpty()) return
        val (updated, skipped) = BoundaryEdits.appendDistinct(_uiState.value.boundary, additions)
        _uiState.update { it.copy(boundary = updated) }
        val added = additions.size - skipped
        showMessage(
            when {
                added > 0 && skipped == 0 -> strings.plural(R.plurals.msg_corners_added, added)
                added > 0 && skipped > 0 -> strings.get(
                    R.string.msg_corners_added_some_skipped,
                    added.toString(),
                    skipped.toString()
                )
                else -> strings.get(R.string.msg_corners_all_duplicate)
            }
        )
    }

    private fun boundaryEditsBlocked(): Boolean =
        _uiState.value.let { it.isWalking || it.isCapturingCorner }

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
                selectedCornerId = null,
                message = null
            )
        }

        viewModelScope.launch {
            if (!locationProvider.hasPermission()) return@launch
            locationProvider.getCurrentLocation()?.let { fix ->
                _currentLocation.value = GeoPoint(
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracyM = fix.accuracy?.toDouble()
                )
            }
        }
    }

    /** Accepts the drawn shape as the boundary. */
    fun commitCornerPicker() {
        cornerJob?.cancel()
        _uiState.update {
            it.copy(
                boundary = it.draftPoints,
                draftBoundary = emptyList(),
                selectedCornerId = null,
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
        _uiState.update {
            it.copy(
                draftBoundary = emptyList(),
                selectedCornerId = null,
                isPickerOpen = false,
                isCapturingCorner = false,
                cornerSamples = 0
            )
        }
    }

    /**
     * Turns a tap into a corner — appended, or slotted into the side it landed on.
     *
     * [edgeToleranceM] is a fingertip's width converted to ground distance at the
     * current zoom, and it is the whole of the gesture: tap an outline where a
     * corner is missing and the corner appears *there*, in sequence, instead of
     * at the end of the ring where it would draw a spike across the parcel. A tap
     * away from the outline still appends, which is what drawing a fresh shape
     * needs.
     */
    fun addDraftCornerAt(latitude: Double, longitude: Double, edgeToleranceM: Double = 0.0) {
        val candidate = GeoPoint(latitude, longitude)
        _uiState.update { state ->
            val points = state.draftPoints
            if (!CornerDraft.acceptTap(points, candidate)) {
                state.copy(message = strings.get(R.string.msg_corner_too_close))
            } else {
                val at = BoundaryEdits.edgeNear(points, candidate, edgeToleranceM)
                val corner = DraftCorner(newCornerId(), candidate)
                if (at == null || at > state.draftBoundary.size) {
                    state.copy(draftBoundary = state.draftBoundary + corner)
                } else {
                    state.copy(
                        draftBoundary = state.draftBoundary.toMutableList()
                            .apply { add(at, corner) },
                        // Said out loud: a corner that quietly appeared as #3
                        // rather than #7 is a renumbering the user has to be told
                        // about, or the list below stops matching what they expect.
                        message = strings.get(R.string.msg_corner_inserted, at + 1)
                    )
                }
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

    /**
     * Selects the corner tapped on the map, or clears the selection when the
     * same one is tapped again. Selection is what makes deleting a *particular*
     * corner possible on the map — undo only ever reaches the last one placed.
     */
    fun selectDraftCorner(id: String?) {
        _uiState.update {
            it.copy(selectedCornerId = if (it.selectedCornerId == id) null else id)
        }
    }

    fun removeDraftCorner(id: String) {
        _uiState.update { state ->
            if (state.draftBoundary.none { it.id == id }) state
            else state.copy(
                draftBoundary = state.draftBoundary.filterNot { it.id == id },
                selectedCornerId = state.selectedCornerId?.takeIf { it != id }
            )
        }
    }

    /** What the picker's delete button calls; a no-op when nothing is selected. */
    fun removeSelectedDraftCorner() {
        _uiState.value.selectedCornerId?.let(::removeDraftCorner)
    }

    fun undoDraftCorner() {
        _uiState.update {
            if (it.draftBoundary.isEmpty()) it
            else {
                val dropped = it.draftBoundary.last().id
                it.copy(
                    draftBoundary = it.draftBoundary.dropLast(1),
                    selectedCornerId = it.selectedCornerId?.takeIf { id -> id != dropped }
                )
            }
        }
    }

    fun clearDraftCorners() {
        cornerJob?.cancel()
        _uiState.update {
            it.copy(
                draftBoundary = emptyList(),
                selectedCornerId = null,
                isCapturingCorner = false,
                cornerSamples = 0
            )
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
                    val candidate = GeoPoint(fix.latitude, fix.longitude, fix.accuracy)
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
                    c.walkRecorder,
                    c.photoStore,
                    c.photoStamper,
                    c.strings,
                    c.settings,
                    createSavedStateHandle()
                )
            }
        }
    }
}
