package com.landpoint.app.ui.shape

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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.landpoint.app.util.GeoUtils
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import kotlin.math.roundToInt

/**
 * How much of the bottom edge the summary card and the attribution take, so the
 * scale bar can be told to sit above them instead of behind them.
 */
private const val SUMMARY_INSET_DP = 140f

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
    val vectorSource by viewModel.vectorSource.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val boundaryColour = MaterialTheme.colorScheme.primary.toArgb()
    val land = state.land

    val basemap = rememberBasemapChoice(hasVectorMap = vectorSource != null)
    val mapView = rememberLandMapView(vectorSource = vectorSource, basemap = basemap.mode)
    val hasCentred = remember(mapView) { mutableStateOf(false) }

    // Resolved up here: the overlay block below is not composable, and building
    // these inside it would re-read the resources on every pan.
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
                        listOf(LandGeoPoint(land.latitude, land.longitude))
                    }
                }

                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxSize(),
                    update = { map ->
                        map.applyTileTheme(isDark, basemap.mode)
                        map.describeForAccessibility(mapDescription)
                        map.overlays.clear()

                        map.drawBoundary(state.boundary, boundaryColour)
                        state.boundary.forEachIndexed { index, point ->
                            map.overlays.add(
                                Marker(map).apply {
                                    position = GeoPoint(point.latitude, point.longitude)
                                    title = cornerTitles[index]
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                    icon = cornerMarkerIcon(
                                        context,
                                        (index + 1).toString(),
                                        boundaryColour
                                    )
                                }
                            )
                        }

                        // A land with no boundary still deserves to be findable
                        // on the map it was opened from.
                        if (state.boundary.isEmpty()) {
                            map.overlays.add(
                                Marker(map).apply {
                                    position = GeoPoint(land.latitude, land.longitude)
                                    title = land.name
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                }
                            )
                        }

                        state.currentLocation?.let { (lat, lon) ->
                            map.drawLocationDot(lat, lon)
                        }
                        // A boundary is a measurement, so give it a scale to be
                        // read against.
                        map.addScaleBar(SUMMARY_INSET_DP)

                        if (!hasCentred.value && map.frame(framePoints, PARCEL_ZOOM)) {
                            hasCentred.value = true
                        }
                        map.invalidate()
                    }
                )

                Column(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                    MapTopChrome(
                        onBack = onBack,
                        title = land.name.ifBlank { stringResource(R.string.shape_title) },
                        actions = {
                            MapFitAction {
                                mapView.frame(framePoints, PARCEL_ZOOM, animated = true)
                            }
                            MapStyleAction { basemap.showSheet() }
                        }
                    )

                    if (state.boundary.isEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                stringResource(R.string.shape_no_boundary),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                MapSideControls(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onZoomIn = mapView::zoomInAStep,
                    onZoomOut = mapView::zoomOutAStep,
                    onMyLocation = {
                        viewModel.refreshLocation()
                        state.currentLocation?.let { (lat, lon) ->
                            mapView.frame(
                                listOf(LandGeoPoint(lat, lon)),
                                PARCEL_ZOOM,
                                animated = true
                            )
                        }
                    }
                )

                // Attribution and summary share the bottom edge, stacked so
                // neither has to be squeezed in beside the other.
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    horizontalAlignment = Alignment.End
                ) {
                    MapAttribution(basemap.mode, Modifier.padding(horizontal = 12.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                    ShapeSummary(state = state, onEdit = { onEdit(land.id) })
                }
            }
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
