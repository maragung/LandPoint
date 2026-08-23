package com.landpoint.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.landpoint.app.R
import com.landpoint.app.ui.components.BasemapSheet
import com.landpoint.app.ui.components.LandMap
import com.landpoint.app.ui.components.LocationAccessChip
import com.landpoint.app.ui.components.MapAttribution
import com.landpoint.app.ui.components.MapFitAction
import com.landpoint.app.ui.components.MapFix
import com.landpoint.app.ui.components.MapOfflineAction
import com.landpoint.app.ui.components.MapPin
import com.landpoint.app.ui.components.MapScaleBar
import com.landpoint.app.ui.components.MapShape
import com.landpoint.app.ui.components.MapSideControls
import com.landpoint.app.ui.components.MapStyleAction
import com.landpoint.app.ui.components.MapTap
import com.landpoint.app.ui.components.MapTopChrome
import com.landpoint.app.ui.components.TelemetryPanel
import com.landpoint.app.ui.components.rememberLandMapController
import com.landpoint.app.ui.components.rememberLocationPermission
import com.landpoint.app.ui.components.rememberMapPrefs
import com.landpoint.app.util.GeoPoint

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
    onOpenOfflineMaps: () -> Unit,
    viewModel: MapViewModel = viewModel(factory = MapViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = rememberMapPrefs()
    val controller = rememberLandMapController()
    val accent = MaterialTheme.colorScheme.primary.toArgb()

    // Saved, so turning the phone does not close a readout the user opened to watch
    // the accuracy settle.
    var readoutOpen by rememberSaveable { mutableStateOf(false) }

    // Not requested on arrival: the map is worth looking at without a position, and
    // a dialog thrown at someone who only wanted to see their plots is the kind of
    // prompt people refuse for good. The chip below offers it when they want it, and
    // tracking picks the permission up on its own once granted.
    val permission = rememberLocationPermission()

    // Everything worth having on screen: boundary corners where a land has them,
    // its pin where it does not, and failing both, wherever the phone is. Measured
    // over corners rather than pins because a pin is the plot's centroid, so
    // fitting to pins alone cuts the far edge off a large field.
    val framePoints = remember(state.lands, state.boundaries, state.currentLocation) {
        val saved = state.lands.flatMap { land ->
            state.boundaries[land.id] ?: listOf(GeoPoint(land.latitude, land.longitude))
        }
        saved.ifEmpty { listOfNotNull(state.currentLocation) }
    }

    val shapes = remember(state.boundaries) {
        state.boundaries.map { (id, ring) -> MapShape(id = id, points = ring, clickable = true) }
    }
    val pins = remember(state.lands) {
        state.lands.map { MapPin(id = it.id, latitude = it.latitude, longitude = it.longitude) }
    }
    val fix = state.currentLocation?.let {
        MapFix(latitude = it.latitude, longitude = it.longitude, accuracyM = it.accuracyM)
    }

    // Fitting to content happens once per visit and survives rotation, so turning
    // the phone does not throw the camera back to where the screen opened.
    LaunchedEffect(framePoints) { controller.frameOnce(framePoints, SINGLE_LAND_ZOOM) }

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
        LandMap(
            style = prefs.style,
            controller = controller,
            accentColour = accent,
            modifier = Modifier.fillMaxSize(),
            shapes = shapes,
            pins = pins,
            fix = fix,
            contentDescription = mapDescription,
            // A plot is reachable by its pin and by its outline, and both open the
            // record: the pin is the smaller target but the only one a plot without
            // a mapped boundary has.
            onTap = { tap ->
                when (tap) {
                    is MapTap.Pin -> onOpenLand(tap.id)
                    is MapTap.Shape -> onOpenLand(tap.id)
                    else -> Unit
                }
            }
        )

        MapTopChrome(
            modifier = Modifier.align(Alignment.TopStart),
            onBack = onBack,
            title = stringResource(R.string.map_title),
            actions = {
                MapFitAction { controller.frame(framePoints, SINGLE_LAND_ZOOM, animated = true) }
                MapOfflineAction(onOpenOfflineMaps)
                MapStyleAction { prefs.showSheet() }
            }
        )

        MapSideControls(
            modifier = Modifier.align(Alignment.CenterEnd),
            onZoomIn = controller::zoomIn,
            onZoomOut = controller::zoomOut,
            // Dropped until there is somewhere to jump to. Tracking is already
            // running while this screen is open, so the fix arrives on its own and
            // the button appears with it — a button that acknowledges a tap and then
            // does nothing is worse than one that is not there yet.
            onMyLocation = state.currentLocation?.let { fix ->
                { controller.frame(listOf(fix), SINGLE_LAND_ZOOM, animated = true) }
            }
        )

        // The readout sits above the scale bar rather than anywhere of its own: both
        // answer "how big is what I am looking at", and the map's own corners are
        // already spoken for by the chrome.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (permission.isGranted) {
                TelemetryPanel(
                    state = state.tracking,
                    expanded = readoutOpen,
                    onExpandedChange = { readoutOpen = it }
                )
            } else {
                LocationAccessChip(
                    onClick = permission.request,
                    blocked = permission.isBlocked
                )
            }
            MapScaleBar(controller = controller, imperial = prefs.imperial)
        }

        // Required, not decorative: every style here is somebody else's work, shown
        // on the condition that it is credited where it is drawn.
        MapAttribution(
            prefs.mode,
            Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )

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
