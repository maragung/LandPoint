package com.landpoint.app.ui.shape

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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.landpoint.app.R
import com.landpoint.app.ui.components.BasemapSheet
import com.landpoint.app.ui.components.LandMap
import com.landpoint.app.ui.components.MapAttribution
import com.landpoint.app.ui.components.MapCorner
import com.landpoint.app.ui.components.MapFitAction
import com.landpoint.app.ui.components.MapFix
import com.landpoint.app.ui.components.MapPin
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
import com.landpoint.app.util.GeoUtils
import kotlin.math.roundToInt

/**
 * The saved boundary of one land, on the whole screen.
 *
 * The thumbnail on the record says *a* shape is stored; this says *which* shape,
 * on the ground, against the surroundings — the check someone makes before
 * trusting the area figure. Which is why the map gets every pixel and the chrome
 * floats over it: an app bar and a bottom bar together would spend a fifth of a
 * phone screen on decoration, and the ground is the point.
 *
 * Read-only: the editor owns the draft and the undo, so the only change offered
 * here is a way back into it.
 */
@Composable
fun LandShapeScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: LandShapeViewModel = viewModel(factory = LandShapeViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = rememberMapPrefs()
    val controller = rememberLandMapController()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val highlight = MaterialTheme.colorScheme.tertiary.toArgb()
    val land = state.land

    // Which corner the user last tapped, if any. Saved rather than remembered, so
    // turning the phone to look at the shape in landscape does not close the
    // coordinates they turned it to read.
    var openCorner by rememberSaveable { mutableStateOf<String?>(null) }

    // Resolved up here: these read string resources, and the map's update block is
    // not composable.
    val cornerTitles = state.boundary.mapIndexed { index, point ->
        val coordinates = stringResource(
            R.string.boundary_corner_number,
            index + 1,
            if (state.dms) GeoUtils.formatDMS(point.latitude, point.longitude)
            else GeoUtils.formatDecimal(point.latitude, point.longitude)
        )
        // Tapping a corner here is someone asking what this corner is; how well
        // it was known is part of the answer.
        val accuracy = point.accuracyM?.let {
            stringResource(R.string.boundary_corner_accuracy, GeoUtils.formatAccuracy(it))
        }
        if (accuracy == null) coordinates else "$coordinates  $accuracy"
    }

    // The map spoken as a sentence. The corner coordinates themselves are on the
    // record this screen was opened from, as a numbered list of text, so this
    // says the shape's size and points there rather than reading out dozens of
    // latitudes.
    val mapDescription = when {
        state.boundary.isEmpty() -> stringResource(R.string.shape_a11y_map_point)
        else -> {
            val corners = pluralStringResource(
                R.plurals.boundary_corners,
                state.boundary.size,
                state.boundary.size
            )
            val area = state.areaSqm?.let {
                stringResource(state.areaUnit.valueRes, AreaFormat.value(it, state.areaUnit))
            }
            if (area == null) stringResource(R.string.shape_a11y_map_no_area, corners)
            else stringResource(R.string.shape_a11y_map, corners, area)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            land == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text(stringResource(R.string.detail_missing)) }

            else -> {
                // The boundary, or the pin of a land that has none: either way
                // there is something to open the screen on.
                val framePoints = remember(state.boundary, land.id) {
                    state.boundary.ifEmpty {
                        listOf(GeoPoint(land.latitude, land.longitude))
                    }
                }

                val shapes = remember(state.boundary, land.id) {
                    listOf(MapShape(id = land.id, points = state.boundary))
                }
                val corners = remember(state.boundary, openCorner) {
                    state.boundary.mapIndexed { index, point ->
                        val id = index.toString()
                        MapCorner(
                            id = id,
                            latitude = point.latitude,
                            longitude = point.longitude,
                            label = (index + 1).toString(),
                            selected = id == openCorner
                        )
                    }
                }
                // A land with no boundary still deserves to be findable on the map
                // it was opened from; one with a boundary is its own marker.
                val pins = remember(state.boundary, land.id, land.latitude, land.longitude) {
                    if (state.boundary.isEmpty()) {
                        listOf(MapPin(id = land.id, latitude = land.latitude, longitude = land.longitude))
                    } else {
                        emptyList()
                    }
                }
                val fix = state.currentLocation?.let {
                    MapFix(latitude = it.latitude, longitude = it.longitude, accuracyM = it.accuracyM)
                }

                // Framed once per visit and remembered across rotation, so turning
                // the phone keeps whatever the user had panned to.
                LaunchedEffect(framePoints) { controller.frameOnce(framePoints, PARCEL_ZOOM) }

                LandMap(
                    style = prefs.style,
                    controller = controller,
                    accentColour = accent,
                    modifier = Modifier.fillMaxSize(),
                    shapes = shapes,
                    pins = pins,
                    corners = corners,
                    fix = fix,
                    selectedColour = highlight,
                    contentDescription = mapDescription,
                    // A tap on a corner asks what that corner is; a tap anywhere
                    // else is done asking.
                    onTap = { tap ->
                        openCorner = when (tap) {
                            is MapTap.Corner -> tap.id.takeUnless { it == openCorner }
                            else -> null
                        }
                    }
                )

                Column(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                    MapTopChrome(
                        onBack = onBack,
                        title = land.name.ifBlank { stringResource(R.string.shape_title) },
                        actions = {
                            MapFitAction {
                                controller.frame(framePoints, PARCEL_ZOOM, animated = true)
                            }
                            MapStyleAction { prefs.showSheet() }
                        }
                    )

                    if (state.boundary.isEmpty()) {
                        MapNotice(
                            text = stringResource(R.string.shape_no_boundary),
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(horizontal = 12.dp)
                        )
                    }
                }

                MapSideControls(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onZoomIn = controller::zoomIn,
                    onZoomOut = controller::zoomOut,
                    onMyLocation = {
                        viewModel.refreshLocation()
                        state.currentLocation?.let {
                            controller.frame(listOf(it), PARCEL_ZOOM, animated = true)
                        }
                    }
                )

                // Everything that belongs to the bottom edge, stacked, so nothing
                // has to be squeezed in beside anything else.
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // What the tapped corner replaced: the old engine popped an info
                    // window over the marker, which covered the very corner it was
                    // describing. Down here it covers nothing that is being looked at.
                    openCorner?.toIntOrNull()?.let { index ->
                        cornerTitles.getOrNull(index)?.let { title ->
                            MapNotice(
                                text = title,
                                monospace = true,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // A boundary is a measurement, so give it a scale to be read
                        // against.
                        MapScaleBar(controller = controller, imperial = prefs.imperial)
                        Box(modifier = Modifier.weight(1f))
                        MapAttribution(prefs.mode)
                    }

                    ShapeSummary(state = state, onEdit = { onEdit(land.id) })
                }
            }
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
 * A short line of text over the map, legible against whatever is under it.
 *
 * Shared by the "no boundary" note and the tapped-corner readout because they are
 * the same thing to the eye — one sentence the map is telling you — and two
 * differently-shaped chips saying different sorts of thing would read as chrome.
 */
@Composable
private fun MapNotice(
    text: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = MAP_NOTICE_ALPHA),
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/** Enough to read text through, enough to see the map is still under it. */
private const val MAP_NOTICE_ALPHA = 0.92f

/**
 * Area, perimeter, corner count — and the way back into the editor.
 *
 * A card that floats over the map rather than a bar bolted under it: the ground it
 * covers is ground the map could have used, so it keeps its own margins and lets
 * the tiles run past it on every side.
 */
@Composable
private fun ShapeSummary(state: LandShapeUiState, onEdit: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                state.areaSqm?.let { area ->
                    Text(
                        stringResource(
                            state.areaUnit.valueRes,
                            AreaFormat.value(area, state.areaUnit)
                        ),
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                val corners = pluralStringResource(
                    R.plurals.boundary_corners,
                    state.boundary.size,
                    state.boundary.size
                )
                val perimeter = state.perimeterM?.let {
                    stringResource(R.string.boundary_perimeter, it.roundToInt().toString())
                }
                Text(
                    if (perimeter == null) corners else "$perimeter · $corners",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilledTonalButton(onClick = onEdit) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text("  " + stringResource(R.string.shape_edit_boundary))
            }
        }
    }
}
