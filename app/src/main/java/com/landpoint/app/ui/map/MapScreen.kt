package com.landpoint.app.ui.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import com.landpoint.app.ui.components.BasemapSheet
import com.landpoint.app.ui.components.MapAttribution
import com.landpoint.app.ui.components.MapFitAction
import com.landpoint.app.ui.components.MapSideControls
import com.landpoint.app.ui.components.MapStyleAction
import com.landpoint.app.ui.components.MapTopChrome
import com.landpoint.app.ui.components.addScaleBar
import com.landpoint.app.ui.components.applyTileTheme
import com.landpoint.app.ui.components.describeForAccessibility
import com.landpoint.app.ui.components.drawBoundary
import com.landpoint.app.ui.components.drawLocationDot
import com.landpoint.app.ui.components.frame
import com.landpoint.app.ui.components.rememberBasemapChoice
import com.landpoint.app.ui.components.rememberLandMapView
import com.landpoint.app.ui.components.zoomInAStep
import com.landpoint.app.ui.components.zoomOutAStep
import com.landpoint.app.util.GeoPoint as LandGeoPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** Where a single record sits comfortably on screen with its surroundings. */
private const val SINGLE_LAND_ZOOM = 16.0

/**
 * Every mapped land, on the whole screen.
 *
 * No app bar. A map is looked *into*, not read, and a title bar spends a strip of
 * it on a word the user already knows — so the controls float over the tiles
 * instead, out of the way of the ground.
 *
 * [onBack] is null when this is reached as a bottom-bar tab — no back arrow is
 * drawn then, because there is nothing underneath to return to.
 */
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

    val basemap = rememberBasemapChoice(hasVectorMap = vectorSource != null)
    val mapView = rememberLandMapView(
        vectorSource = vectorSource,
        basemap = basemap.mode,
        initialZoom = 4.5
    )

    // Fit-to-content should happen once per visit, not once per process.
    val hasCentred = remember(mapView) { mutableStateOf(false) }

    // Everything worth having on screen: boundary corners where a land has them,
    // its pin where it does not, and failing both, wherever the phone is. Measured
    // over corners rather than pins because a pin is the plot's centroid, so
    // fitting to pins alone cuts the far edge off a large field.
    val framePoints = remember(state.lands, state.boundaries, state.currentLocation) {
        val saved = state.lands.flatMap { land ->
            state.boundaries[land.id] ?: listOf(LandGeoPoint(land.latitude, land.longitude))
        }
        saved.ifEmpty {
            state.currentLocation?.let { (lat, lon) -> listOf(LandGeoPoint(lat, lon)) }
                ?: emptyList()
        }
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { map ->
                map.applyTileTheme(isDark, basemap.mode)
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

                map.addScaleBar()

                if (!hasCentred.value && map.frame(framePoints, SINGLE_LAND_ZOOM)) {
                    hasCentred.value = true
                }

                map.invalidate()
            }
        )

        MapTopChrome(
            modifier = Modifier.align(Alignment.TopStart),
            onBack = onBack,
            title = stringResource(R.string.map_title),
            actions = {
                MapFitAction { mapView.frame(framePoints, SINGLE_LAND_ZOOM, animated = true) }
                MapStyleAction { basemap.showSheet() }
            }
        )

        MapSideControls(
            modifier = Modifier.align(Alignment.CenterEnd),
            onZoomIn = mapView::zoomInAStep,
            onZoomOut = mapView::zoomOutAStep,
            // Always offered, unlike on the screens that are handed a position:
            // this one can go and ask for a fresh fix, so the button has
            // something to do even before there is a dot to jump to.
            onMyLocation = {
                viewModel.refreshLocation()
                state.currentLocation?.let { (lat, lon) ->
                    mapView.frame(
                        listOf(LandGeoPoint(lat, lon)),
                        SINGLE_LAND_ZOOM,
                        animated = true
                    )
                }
            }
        )

        // Required, not decorative: every style here is somebody else's work, shown
        // on the condition that it is credited where it is drawn.
        MapAttribution(
            basemap.mode,
            Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )

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
