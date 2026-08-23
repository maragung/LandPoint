package com.landpoint.app.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.landpoint.app.R
import com.landpoint.app.ui.components.BasemapSheet
import com.landpoint.app.ui.components.LandMap
import com.landpoint.app.ui.components.MapAttribution
import com.landpoint.app.ui.components.MapCorner
import com.landpoint.app.ui.components.MapFitAction
import com.landpoint.app.ui.components.MapFix
import com.landpoint.app.ui.components.MapScaleBar
import com.landpoint.app.ui.components.MapShape
import com.landpoint.app.ui.components.MapSideControls
import com.landpoint.app.ui.components.MapStyleAction
import com.landpoint.app.ui.components.MapTap
import com.landpoint.app.ui.components.MapTopChrome
import com.landpoint.app.ui.components.PARCEL_ZOOM
import com.landpoint.app.ui.components.rememberLandMapController
import com.landpoint.app.ui.components.rememberMapPrefs
import com.landpoint.app.util.AreaFormat
import com.landpoint.app.util.GeoPoint

/**
 * How close a tap has to be to an outline to count as landing *on* it rather
 * than beside it. Smaller than a touch target on purpose: overshooting turns an
 * intended new corner into a corner inserted mid-ring, which is the more
 * surprising of the two mistakes.
 */
private val EDGE_TOUCH_TARGET = 20.dp

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
    currentLocation: GeoPoint?,
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
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val highlight = MaterialTheme.colorScheme.tertiary.toArgb()

    BackHandler { onCancel() }

    val prefs = rememberMapPrefs()
    val controller = rememberLandMapController()

    // Kept across rotation: turning the phone to get a wider view of a long plot
    // should not put the panel back over the map.
    var toolsShown by rememberSaveable { mutableStateOf(true) }

    // Where to open, and where the fit button goes back to: the corners already
    // drawn, else the coordinates the form holds, else the phone.
    val framePoints: List<GeoPoint> = remember(
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
                    listOf(GeoPoint(formLat, formLon))
                currentLocation != null -> listOf(currentLocation)
                else -> emptyList()
            }
        }
    }

    val shapes = remember(state.draftPoints) {
        listOf(MapShape(id = DRAFT_SHAPE_ID, points = state.draftPoints))
    }
    val corners = remember(state.draftBoundary, state.selectedCornerId) {
        state.draftBoundary.mapIndexed { index, corner ->
            MapCorner(
                id = corner.id,
                latitude = corner.point.latitude,
                longitude = corner.point.longitude,
                label = (index + 1).toString(),
                selected = corner.id == state.selectedCornerId
            )
        }
    }
    val fix = currentLocation?.let {
        MapFix(latitude = it.latitude, longitude = it.longitude, accuracyM = it.accuracyM)
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
    val jumpToLocation: (() -> Unit)? = currentLocation?.let { here ->
        { controller.frame(listOf(here), PARCEL_ZOOM, animated = true) }
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

    // Framed once per opening of the picker, and kept across rotation.
    LaunchedEffect(framePoints) { controller.frameOnce(framePoints, PARCEL_ZOOM) }

    Box(modifier = Modifier.fillMaxSize()) {
        LandMap(
            style = prefs.style,
            controller = controller,
            accentColour = accent,
            modifier = Modifier.fillMaxSize(),
            shapes = shapes,
            corners = corners,
            fix = fix,
            selectedColour = highlight,
            contentDescription = mapDescription,
            // A finger is a fixed size on the glass and a wildly varying distance
            // on the ground, so "did this tap land on the outline?" can only be
            // answered at the zoom the tap was made — which is what the map view
            // converts this into before handing the tap on.
            touchTolerance = EDGE_TOUCH_TARGET,
            onTap = { tap ->
                when (tap) {
                    // Selects rather than swallowing the tap: a mistaken corner in
                    // the middle of a ring used to be removable only by clearing
                    // the lot.
                    is MapTap.Corner -> onSelectCorner(tap.id)
                    is MapTap.Ground ->
                        onTapCorner(tap.point.latitude, tap.point.longitude, tap.toleranceM)
                    else -> Unit
                }
            },
            onMoveCorner = onMoveCorner
        )

        Column(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            MapTopChrome(
                onBack = onCancel,
                title = stringResource(R.string.corner_picker_title),
                actions = {
                    MapFitAction { controller.frame(framePoints, PARCEL_ZOOM, animated = true) }
                    MapStyleAction { prefs.showSheet() }
                }
            )

            // Folded away with the tools: someone who asked for more map did not
            // ask to keep reading the instructions.
            if (toolsShown) {
                HintBanner(
                    onlineTiles = prefs.provider?.needsNetwork == true,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 8.dp)
                )
            }
        }

        MapSideControls(
            modifier = Modifier.align(Alignment.CenterEnd),
            onZoomIn = controller::zoomIn,
            onZoomOut = controller::zoomOut,
            onMyLocation = jumpToLocation
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SnackbarHost(snackbarHost, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Only with the panel folded away. A scale bar behind the tools is
                // worse than none: the map would look like it had one.
                if (!toolsShown) {
                    MapScaleBar(controller = controller, imperial = prefs.imperial)
                }
                Box(modifier = Modifier.weight(1f))
                MapAttribution(prefs.mode)
            }
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

        if (prefs.sheetVisible) {
            BasemapSheet(
                current = prefs.mode,
                hasVectorMap = prefs.hasVectorMap,
                onPick = prefs::select,
                onDismiss = prefs::hideSheet
            )
        }
    }
}

/**
 * The draft outline's overlay id.
 *
 * A constant rather than the land's id: a land being created for the first time has
 * no id yet, and the overlay only needs it to tell one shape from another — there
 * is exactly one shape here.
 */
private const val DRAFT_SHAPE_ID = "draft"

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
