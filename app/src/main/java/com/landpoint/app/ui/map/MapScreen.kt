package com.landpoint.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.landpoint.app.R
import com.landpoint.app.data.model.Land
import com.landpoint.app.ui.components.applyTileTheme
import com.landpoint.app.ui.components.boundsOf
import com.landpoint.app.ui.components.describeForAccessibility
import com.landpoint.app.ui.components.drawBoundary
import com.landpoint.app.ui.components.drawLocationDot
import com.landpoint.app.ui.components.rememberLandMapView
import com.landpoint.app.util.GeoPoint as LandGeoPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** Where a single record sits comfortably on screen with its surroundings. */
private const val SINGLE_LAND_ZOOM = 16.0

/**
 * [onBack] is null when this is reached as a bottom-bar tab — no back arrow is
 * drawn then, because there is nothing underneath to return to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onBack: (() -> Unit)? = null,
    onOpenLand: (String) -> Unit,
    viewModel: MapViewModel = viewModel(factory = MapViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val vectorSource by viewModel.vectorSource.collectAsStateWithLifecycle()
    val isDark = isSystemInDarkTheme()
    val boundaryColour = MaterialTheme.colorScheme.primary.toArgb()

    val mapView = rememberLandMapView(vectorSource = vectorSource, initialZoom = 4.5)

    // Fit-to-content should happen once per visit, not once per process.
    val hasCentred = remember(mapView) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.map_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                viewModel.refreshLocation()
                state.currentLocation?.let { (lat, lon) ->
                    mapView.controller.animateTo(GeoPoint(lat, lon))
                    mapView.controller.setZoom(SINGLE_LAND_ZOOM)
                }
            }) {
                Icon(
                    Icons.Default.MyLocation,
                    contentDescription = stringResource(R.string.map_my_location)
                )
            }
        }
    ) { padding ->
        // Resolved here because the AndroidView update block is not composable.
        val youAreHere = stringResource(R.string.map_you_are_here)

        // What a screen reader gets instead of the map. Counts rather than names:
        // a list of every plot read out on entering the tab would be unusable,
        // and the list of lands — where each record is a labelled, openable row —
        // is the same information in a form that can be navigated.
        val mapDescription = if (state.lands.isEmpty()) {
            stringResource(R.string.map_a11y_empty)
        } else {
            stringResource(
                R.string.map_a11y_overview,
                pluralStringResource(R.plurals.list_summary, state.lands.size, state.lands.size),
                state.boundaries.size
            )
        }

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { map ->
                    map.applyTileTheme(isDark)
                    map.describeForAccessibility(mapDescription)

                    map.overlays.clear()

                    // Shapes first, pins second: osmdroid hands a touch to the
                    // topmost overlay, and the pin has to stay reachable inside
                    // its own plot. Both open the same record either way.
                    state.lands.forEach { land ->
                        state.boundaries[land.id]?.let { ring ->
                            map.drawBoundary(ring, boundaryColour) { onOpenLand(land.id) }
                        }
                    }

                    state.lands.forEach { land ->
                        map.overlays.add(land.toMarker(map, onOpenLand))
                    }

                    state.currentLocation?.let { (lat, lon) ->
                        map.drawLocationDot(lat, lon, youAreHere)
                    }

                    if (!hasCentred.value && centreOnContent(map, state)) {
                        hasCentred.value = true
                    }

                    map.invalidate()
                }
            )

            // Required, not decorative: the vector maps render OpenStreetMap data
            // under CC-BY-SA, which obliges us to name the source on screen.
            if (vectorSource != null) {
                Text(
                    text = stringResource(R.string.map_attribution_osm),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun Land.toMarker(map: MapView, onOpenLand: (String) -> Unit): Marker =
    Marker(map).apply {
        position = GeoPoint(latitude, longitude)
        title = name
        snippet = address ?: description.takeIf { it.isNotBlank() }
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        setOnMarkerClickListener { _, _ ->
            onOpenLand(this@toMarker.id)
            true
        }
    }

/**
 * Fits the viewport to everything saved. Returns true once it had something to
 * fit.
 *
 * Measured over boundary corners, not just pins: a plot's pin is its centroid,
 * so fitting to pins alone would cut the far edge of a large field off the
 * screen — the one thing this view exists to show.
 */
private fun centreOnContent(map: MapView, state: MapUiState): Boolean {
    val points = state.lands.flatMap { land ->
        state.boundaries[land.id] ?: listOf(LandGeoPoint(land.latitude, land.longitude))
    }

    boundsOf(points)?.let { box ->
        // Posted because a bounding box cannot be fitted to a view that has not
        // been measured yet.
        map.post { map.zoomToBoundingBox(box, false) }
        return true
    }

    val centre = points.firstOrNull()
        ?: state.currentLocation?.let { (lat, lon) -> LandGeoPoint(lat, lon) }
        ?: return false

    map.controller.setZoom(SINGLE_LAND_ZOOM)
    map.controller.setCenter(GeoPoint(centre.latitude, centre.longitude))
    return true
}
