package com.landpoint.app.ui.shape

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.landpoint.app.ui.components.PARCEL_ZOOM
import com.landpoint.app.ui.components.applyTileTheme
import com.landpoint.app.ui.components.boundsOf
import com.landpoint.app.ui.components.cornerMarkerIcon
import com.landpoint.app.ui.components.describeForAccessibility
import com.landpoint.app.ui.components.drawBoundary
import com.landpoint.app.ui.components.drawLocationDot
import com.landpoint.app.ui.components.rememberLandMapView
import com.landpoint.app.util.AreaFormat
import com.landpoint.app.util.GeoUtils
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.ScaleBarOverlay
import kotlin.math.roundToInt

/**
 * The saved boundary of one land, on a full screen map.
 *
 * The thumbnail on the record says *a* shape is stored; this says *which* shape,
 * on the ground, against the surroundings — the check someone makes before
 * trusting the area figure. Read-only: the editor owns the draft and the undo,
 * so the only change offered here is a way back into it.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    val mapView = rememberLandMapView(
        vectorSource = vectorSource,
        maxZoomWithoutVector = PARCEL_ZOOM
    )
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
    val attribution = stringResource(R.string.map_attribution_osm)

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(land?.name ?: stringResource(R.string.shape_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                viewModel.refreshLocation()
                state.currentLocation?.let { (lat, lon) ->
                    mapView.controller.animateTo(GeoPoint(lat, lon))
                }
            }) {
                Icon(
                    Icons.Default.MyLocation,
                    contentDescription = stringResource(R.string.map_my_location)
                )
            }
        },
        bottomBar = {
            if (land != null) {
                ShapeSummary(state = state, onEdit = { onEdit(land.id) })
            }
        }
    ) { padding ->
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            land == null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { Text(stringResource(R.string.detail_missing)) }

            else -> Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxSize(),
                    update = { map ->
                        map.applyTileTheme(isDark)
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
                        map.overlays.add(ScaleBarOverlay(map).apply { setAlignBottom(true) })

                        if (!hasCentred.value) {
                            hasCentred.value = centreOnShape(map, state, land.latitude, land.longitude)
                        }
                        map.invalidate()
                    }
                )

                if (state.boundary.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.shape_no_boundary),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                // The vector maps render OpenStreetMap data under CC-BY-SA, which
                // obliges us to name the source on screen.
                if (vectorSource != null) {
                    Text(
                        text = attribution,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/** Area, perimeter, corner count — and the way back into the editor. */
@Composable
private fun ShapeSummary(state: LandShapeUiState, onEdit: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
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

/**
 * Frames the boundary, or the land's pin when there is none. Returns false while
 * there is nothing to frame, so the caller keeps trying as data arrives.
 */
private fun centreOnShape(
    map: MapView,
    state: LandShapeUiState,
    fallbackLat: Double,
    fallbackLon: Double
): Boolean {
    boundsOf(state.boundary)?.let { box ->
        // Posted because a bounding box cannot be fitted to a view that has not
        // been measured yet.
        map.post { map.zoomToBoundingBox(box, false) }
        return true
    }
    val single = state.boundary.firstOrNull()
    map.controller.setZoom(PARCEL_ZOOM)
    map.controller.setCenter(
        GeoPoint(single?.latitude ?: fallbackLat, single?.longitude ?: fallbackLon)
    )
    return true
}
