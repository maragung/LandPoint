package com.landpoint.app.ui.map

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.landpoint.app.R
import com.landpoint.app.data.model.Land
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

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
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    // Rebuilt when an imported vector map turns up, because a mapsforge source
    // is not something MapView's default provider can draw — only
    // MapsForgeTileProvider calls renderTile(). That happens at most once per
    // visit, right after the file headers finish loading.
    val mapView = remember(vectorSource) {
        val source = vectorSource
        val view = if (source != null) {
            MapView(
                context,
                MapsForgeTileProvider(
                    SimpleRegisterReceiver(context),
                    source,
                    SqlTileWriter()
                )
            )
        } else {
            MapView(context)
        }
        view.apply {
            setTileSource(source ?: TileSourceFactory.MAPNIK)
            // With a local vector map there is nothing to fetch; saying so keeps
            // the map from reaching for a radio the user may have switched off.
            setUseDataConnection(source == null)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )
            controller.setZoom(4.5)
        }
    }

    // Fit-to-content should happen once per visit, not once per process.
    val hasCentred = remember(mapView) { mutableStateOf(false) }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

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
                    mapView.controller.setZoom(16.0)
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

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { map ->
                    map.overlayManager.tilesOverlay.setColorFilter(
                        if (isDark) TilesOverlay.INVERT_COLORS else null
                    )

                    map.overlays.clear()

                    state.lands.forEach { land ->
                        map.overlays.add(land.toMarker(map, onOpenLand))
                    }

                    state.currentLocation?.let { (lat, lon) ->
                        map.overlays.add(
                            Marker(map).apply {
                                position = GeoPoint(lat, lon)
                                title = youAreHere
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = ShapeDrawable(OvalShape()).apply {
                                    paint.color = AndroidColor.parseColor("#1E88E5")
                                    intrinsicWidth = 28
                                    intrinsicHeight = 28
                                }
                            }
                        )
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

/** Fits the viewport to the markers. Returns true once it had something to fit. */
private fun centreOnContent(map: MapView, state: MapUiState): Boolean {
    val points = state.lands.map { GeoPoint(it.latitude, it.longitude) }
    return when {
        points.size > 1 -> {
            val box = BoundingBox.fromGeoPointsSafe(points)
            map.post { map.zoomToBoundingBox(box.increaseByScale(1.4f), false) }
            true
        }

        points.size == 1 -> {
            map.controller.setZoom(16.0)
            map.controller.setCenter(points.first())
            true
        }

        state.currentLocation != null -> {
            val (lat, lon) = state.currentLocation
            map.controller.setZoom(15.0)
            map.controller.setCenter(GeoPoint(lat, lon))
            true
        }

        else -> false
    }
}
