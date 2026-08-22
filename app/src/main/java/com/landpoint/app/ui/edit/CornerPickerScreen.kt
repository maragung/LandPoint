package com.landpoint.app.ui.edit

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.landpoint.app.R
import com.landpoint.app.ui.components.BasemapSheet
import com.landpoint.app.ui.components.MapAttribution
import com.landpoint.app.ui.components.MapFitAction
import com.landpoint.app.ui.components.MapSideControls
import com.landpoint.app.ui.components.MapStyleAction
import com.landpoint.app.ui.components.MapTopChrome
import com.landpoint.app.ui.components.PARCEL_ZOOM
import com.landpoint.app.ui.components.addScaleBar
import com.landpoint.app.ui.components.applyTileTheme
import com.landpoint.app.ui.components.cornerMarkerIcon
import com.landpoint.app.ui.components.describeForAccessibility
import com.landpoint.app.ui.components.drawBoundary
import com.landpoint.app.ui.components.drawLocationDot
import com.landpoint.app.ui.components.frame
import com.landpoint.app.ui.components.rememberBasemapChoice
import com.landpoint.app.ui.components.rememberLandMapView
import com.landpoint.app.ui.components.zoomInAStep
import com.landpoint.app.ui.components.zoomOutAStep
import com.landpoint.app.util.AreaFormat
import com.landpoint.app.util.GeoPoint as LandGeoPoint
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * How close a tap has to be to an outline to count as landing *on* it rather
 * than beside it. Smaller than a touch target on purpose: overshooting turns an
 * intended new corner into a corner inserted mid-ring, which is the more
 * surprising of the two mistakes.
 */
private val EDGE_TOUCH_TARGET = 20.dp

/**
 * What the collapsed summary and the attribution take off the bottom edge, so the
 * scale bar can be told to sit above them.
 */
private const val COLLAPSED_INSET_DP = 110f

/**
 * Picks boundary corners on a map that fills the screen.
 *
 * The corner-by-corner GPS capture on the form stays the accurate method and is
 * untouched. This is for the corners that cannot be stood on — across a ditch,
 * inside someone else's crop, out in flooded paddy — and for seeing the shape
 * take form while it is being drawn rather than after.
 *
 * The screen is the map. Placing a corner means judging a spot against a tree, a
 * roof line or the edge of a field, and every strip of chrome is a strip of that
 * judgement taken away — so the tools float on top and fold away entirely when the
 * ground matters more than they do.
 *
 * Everything here edits a draft. Nothing reaches the saved boundary until the
 * user accepts it, so leaving the map cannot lose a boundary already recorded.
 */
@Composable
fun CornerPickerScreen(
    state: LandEditUiState,
    vectorSource: MapsForgeTileSource?,
    currentLocation: Pair<Double, Double>?,
    onTapCorner: (Double, Double, Double) -> Unit,
    onMessageShown: () -> Unit,
    onMoveCorner: (String, Double, Double) -> Unit,
    onSelectCorner: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    onUseGps: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val markerColor = MaterialTheme.colorScheme.primary.toArgb()
    val shapeColor = MaterialTheme.colorScheme.primary.toArgb()

    BackHandler { onCancel() }

    val basemap = rememberBasemapChoice(hasVectorMap = vectorSource != null)
    val mapView = rememberLandMapView(vectorSource = vectorSource, basemap = basemap.mode)

    val hasCentred = remember(mapView) { mutableStateOf(false) }
    // Kept across rotation: turning the phone to get a wider view of a long plot
    // should not put the panel back over the map.
    var toolsShown by rememberSaveable { mutableStateOf(true) }

    // A fingertip, in pixels. Converted to ground distance at the moment of each
    // tap, because the same 20dp is a metre at one zoom and fifty at another.
    val edgeTouchPx = with(LocalDensity.current) { EDGE_TOUCH_TARGET.toPx() }

    // Where to open, and where the fit button goes back to: the corners already
    // drawn, else the coordinates the form holds, else the phone.
    val framePoints: List<LandGeoPoint> = remember(
        state.draftPoints,
        state.latitude,
        state.longitude,
        currentLocation
    ) {
        state.draftPoints.ifEmpty {
            val formLat = state.latitude.toDoubleOrNull()
            val formLon = state.longitude.toDoubleOrNull()
            when {
                state.hasCoordinates && formLat != null && formLon != null ->
                    listOf(LandGeoPoint(formLat, formLon))
                currentLocation != null ->
                    listOf(LandGeoPoint(currentLocation.first, currentLocation.second))
                else -> emptyList()
            }
        }
    }

    // Placing a corner here means touching a spot on the glass, which is not a
    // gesture a screen reader can offer. So the description says how many corners
    // stand, and names the button on the form that does the same job by typing
    // numbers — the accessible way to build the same boundary.
    val mapDescription = stringResource(
        R.string.picker_a11y_map,
        pluralStringResource(
            R.plurals.boundary_corners,
            state.draftBoundary.size,
            state.draftBoundary.size
        ),
        stringResource(R.string.boundary_add_manual)
    )

    // Declared, not inlined at the call site, so the Unit coercion on the lambda
    // is plain to read. The editor supplies the position; there is nothing to ask
    // for here, so with no fix yet there is no button either.
    val jumpToLocation: (() -> Unit)? = currentLocation?.let { fix ->
        {
            mapView.frame(
                listOf(LandGeoPoint(fix.first, fix.second)),
                PARCEL_ZOOM,
                animated = true
            )
        }
    }

    // The form's snackbar host is not composed while this screen is up, so a
    // message raised here — a tap refused for sitting on a corner, a corner
    // slotted into a side — had nowhere to appear until the picker closed. It
    // gets its own host.
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            onMessageShown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { map ->
                map.applyTileTheme(isDark, basemap.mode)
                map.describeForAccessibility(mapDescription)
                map.overlays.clear()
                addTaplistener(map, edgeTouchPx, onTapCorner)
                map.drawBoundary(state.draftPoints, shapeColor)
                addCornerMarkers(
                    map = map,
                    context = context,
                    corners = state.draftBoundary,
                    selectedId = state.selectedCornerId,
                    colour = markerColor,
                    onMoveCorner = onMoveCorner,
                    onSelectCorner = onSelectCorner
                )
                currentLocation?.let { (lat, lon) -> map.drawLocationDot(lat, lon) }

                // Only with the panel folded away. Painted onto the map's own
                // canvas, it cannot know what floats above it, and a scale bar
                // hidden behind the tools is worse than none — the map would look
                // like it had one.
                if (!toolsShown) map.addScaleBar(COLLAPSED_INSET_DP)

                if (!hasCentred.value && map.frame(framePoints, PARCEL_ZOOM)) {
                    hasCentred.value = true
                }
                map.invalidate()
            }
        )

        Column(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            MapTopChrome(
                onBack = onCancel,
                title = stringResource(R.string.corner_picker_title),
                actions = {
                    MapFitAction { mapView.frame(framePoints, PARCEL_ZOOM, animated = true) }
                    MapStyleAction { basemap.showSheet() }
                }
            )

            // Folded away with the tools: someone who asked for more map did not
            // ask to keep reading the instructions.
            if (toolsShown) {
                HintBanner(
                    onlineTiles = basemap.mode.needsNetwork,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 8.dp)
                )
            }
        }

        MapSideControls(
            modifier = Modifier.align(Alignment.CenterEnd),
            onZoomIn = mapView::zoomInAStep,
            onZoomOut = mapView::zoomOutAStep,
            onMyLocation = jumpToLocation
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            // Told to span the width: this column right-aligns its children so the
            // attribution sits in the corner, and a snackbar shunted to one side
            // reads as part of the panel rather than as a message about the map.
            SnackbarHost(snackbarHost, modifier = Modifier.fillMaxWidth())
            MapAttribution(basemap.mode, Modifier.padding(horizontal = 12.dp))
            Spacer(modifier = Modifier.height(4.dp))
            PickerControls(
                state = state,
                toolsShown = toolsShown,
                onToggleTools = { toolsShown = !toolsShown },
                onDeleteSelected = onDeleteSelected,
                onUseGps = onUseGps,
                onUndo = onUndo,
                onClear = onClear,
                onDone = onDone,
                onCancel = onCancel
            )
        }

        if (basemap.sheetVisible) {
            BasemapSheet(
                current = basemap.mode,
                hasVectorMap = basemap.hasVectorMap,
                onPick = basemap::select,
                onDismiss = basemap::hideSheet
            )
        }
    }
}

/**
 * Turns a tap on the map into a corner.
 *
 * Added at index 0 so the markers and shape stacked above it get first refusal
 * on the touch — dragging a corner must not also drop a new one underneath it.
 *
 * The third value handed on is how many metres [edgeTouchPx] covers on the ground
 * where the tap landed. A finger is a fixed size on the glass and a wildly
 * varying distance on the ground, so the "did this land on the outline?" question
 * can only be answered at the moment of the tap, at the zoom it was made — which
 * is why the conversion happens here and not in the ViewModel.
 */
private fun addTaplistener(
    map: MapView,
    edgeTouchPx: Float,
    onTapCorner: (Double, Double, Double) -> Unit
) {
    map.overlays.add(
        0,
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p ?: return false
                val pixelsPerMetre = map.projection.metersToPixels(1f)
                val tolerance =
                    if (pixelsPerMetre > 0f) (edgeTouchPx / pixelsPerMetre).toDouble() else 0.0
                onTapCorner(p.latitude, p.longitude, tolerance)
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        })
    )
}

/**
 * One draggable, numbered marker per corner.
 *
 * The marker carries the corner's id, and both a drag and a tap report that id
 * rather than a position in the list: undo or clear can land mid-gesture, and an
 * index captured at drag start would by then mean a different corner, or none.
 *
 * The tap used to be swallowed outright, which left a mistaken corner in the
 * middle of a ring only deletable by clearing the lot. It now selects, and the
 * controls below offer to delete the one selected.
 */
private fun addCornerMarkers(
    map: MapView,
    context: Context,
    corners: List<DraftCorner>,
    selectedId: String?,
    colour: Int,
    onMoveCorner: (String, Double, Double) -> Unit,
    onSelectCorner: (String) -> Unit
) {
    corners.forEachIndexed { index, corner ->
        map.overlays.add(
            Marker(map).apply {
                id = corner.id
                position = GeoPoint(corner.point.latitude, corner.point.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                isDraggable = true
                icon = cornerMarkerIcon(
                    context = context,
                    label = (index + 1).toString(),
                    colour = colour,
                    highlighted = corner.id == selectedId
                )
                // True, so the tap stops here: passing it on would drop a fresh
                // corner on top of the one just selected.
                setOnMarkerClickListener { _, _ ->
                    onSelectCorner(corner.id)
                    true
                }
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(marker: Marker) = Unit
                    override fun onMarkerDrag(marker: Marker) = Unit
                    override fun onMarkerDragEnd(marker: Marker) {
                        onMoveCorner(marker.id, marker.position.latitude, marker.position.longitude)
                    }
                })
            }
        )
    }
}

/**
 * How to use the map, and where its background comes from. Never blocks the map:
 * coordinates do not need imagery, so every control still works over an empty
 * backdrop.
 */
@Composable
private fun HintBanner(onlineTiles: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                stringResource(R.string.corner_picker_tap_hint),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                stringResource(R.string.corner_picker_select_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onlineTiles) {
                Text(
                    stringResource(R.string.corner_picker_online_map),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Everything that acts on the draft, on a card that floats over the map.
 *
 * Folds down to its readout and the one button that finishes the job. What is
 * being drawn is out there on the ground, and there are moments — lining a corner
 * up against a fence post, checking an edge runs where it should — when the tools
 * are worth less than the strip of map they cover.
 */
@Composable
private fun PickerControls(
    state: LandEditUiState,
    toolsShown: Boolean,
    onToggleTools: () -> Unit,
    onDeleteSelected: () -> Unit,
    onUseGps: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val corners = state.draftBoundary.size
    val busy = state.isCapturingCorner

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CornerReadout(state = state, corners = corners)
                }
                // Folded, the finishing button comes up beside the readout: a
                // boundary that is done should not need the panel opened again.
                if (!toolsShown) {
                    Button(onClick = onDone, enabled = !busy) {
                        Text(stringResource(R.string.corner_picker_done))
                    }
                }
                IconButton(onClick = onToggleTools) {
                    Icon(
                        if (toolsShown) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = stringResource(
                            if (toolsShown) R.string.corner_picker_hide_controls
                            else R.string.corner_picker_show_controls
                        )
                    )
                }
            }

            if (toolsShown) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(onClick = onUseGps, enabled = !busy) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.MyLocation,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            "  " + stringResource(
                                if (busy) R.string.boundary_capturing
                                else R.string.corner_picker_use_gps
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    if (corners > 0) {
                        OutlinedButton(onClick = onUndo, enabled = !busy) {
                            Text(stringResource(R.string.boundary_undo))
                        }
                        OutlinedButton(onClick = onClear, enabled = !busy) {
                            Text(stringResource(R.string.boundary_clear))
                        }
                    }
                }

                // Only while a corner is selected, so the row does not sit there
                // greyed out for the whole session — most boundaries need no
                // deleting.
                state.selectedCornerNumber?.let { number ->
                    OutlinedButton(
                        onClick = onDeleteSelected,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "  " + stringResource(
                                R.string.corner_picker_delete_selected,
                                number
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.corner_picker_cancel))
                    }
                    Button(
                        onClick = onDone,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.corner_picker_done))
                    }
                }

                Text(
                    stringResource(R.string.legal_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Corner count and, once the ring closes, the area it encloses. */
@Composable
private fun CornerReadout(state: LandEditUiState, corners: Int) {
    if (corners == 0) return
    val unit = state.areaUnit

    Text(
        pluralStringResource(R.plurals.boundary_corners, corners, corners),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    val area = state.draftAreaSqm
    if (area != null) {
        Text(
            stringResource(R.string.boundary_area) + ": " +
                stringResource(unit.valueRes, AreaFormat.value(area, unit)),
            style = MaterialTheme.typography.bodyLarge
        )
    } else {
        val missing = 3 - corners
        Text(
            pluralStringResource(R.plurals.boundary_need_more, missing, missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
