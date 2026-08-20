package com.landpoint.app.ui.edit

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.landpoint.app.R
import com.landpoint.app.util.AreaFormat
import com.landpoint.app.util.GeoPoint as LandGeoPoint
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.TilesOverlay

/** Close enough to read a fence line; MAPNIK stops here too. */
private const val CORNER_ZOOM = 19.0

/**
 * Picks boundary corners on a map.
 *
 * The corner-by-corner GPS capture on the form stays the accurate method and is
 * untouched. This is for the corners that cannot be stood on — across a ditch,
 * inside someone else's crop, out in flooded paddy — and for seeing the shape
 * take form while it is being drawn rather than after.
 *
 * Everything here edits a draft. Nothing reaches the saved boundary until the
 * user accepts it, so leaving the map cannot lose a boundary already recorded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CornerPickerScreen(
    state: LandEditUiState,
    vectorSource: MapsForgeTileSource?,
    currentLocation: Pair<Double, Double>?,
    onTapCorner: (Double, Double) -> Unit,
    onMoveCorner: (String, Double, Double) -> Unit,
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

    // Rebuilt only when an imported vector map turns up: a mapsforge source is
    // not something MapView's default provider can draw.
    val mapView = remember(vectorSource) {
        val view = if (vectorSource != null) {
            MapView(
                context,
                MapsForgeTileProvider(
                    SimpleRegisterReceiver(context),
                    vectorSource,
                    SqlTileWriter()
                )
            )
        } else {
            MapView(context)
        }
        view.apply {
            setTileSource(vectorSource ?: TileSourceFactory.MAPNIK)
            setUseDataConnection(vectorSource == null)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            // Without a vector map there are no tiles past MAPNIK's last zoom, so
            // stop there rather than let the user zoom into a blank grey field.
            if (vectorSource == null) setMaxZoomLevel(CORNER_ZOOM)
        }
    }

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
                title = { Text(stringResource(R.string.corner_picker_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.corner_picker_cancel)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (currentLocation != null) {
                FloatingActionButton(onClick = {
                    mapView.controller.animateTo(
                        GeoPoint(currentLocation.first, currentLocation.second)
                    )
                    mapView.controller.setZoom(CORNER_ZOOM)
                }) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = stringResource(R.string.map_my_location)
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxSize(),
                    update = { map ->
                        map.overlayManager.tilesOverlay.setColorFilter(
                            if (isDark) TilesOverlay.INVERT_COLORS else null
                        )
                        map.overlays.clear()
                        addTaplistener(map, onTapCorner)
                        addShape(map, state.draftPoints, shapeColor)
                        addCornerMarkers(map, state.draftBoundary, markerColor, onMoveCorner)
                        currentLocation?.let { addLocationMarker(map, it) }
                        map.overlays.add(ScaleBarOverlay(map).apply { setAlignBottom(true) })

                        if (!hasCentred.value) {
                            hasCentred.value = centreOnStart(map, state, currentLocation)
                        }
                        map.invalidate()
                    }
                )

                HintBanner(
                    hasVectorMap = vectorSource != null,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                )

                if (vectorSource != null) {
                    Text(
                        stringResource(R.string.map_attribution_osm),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            PickerControls(
                state = state,
                onUseGps = onUseGps,
                onUndo = onUndo,
                onClear = onClear,
                onDone = onDone,
                onCancel = onCancel
            )
        }
    }
}

/**
 * Turns a tap on empty map into a corner.
 *
 * Added at index 0 so the markers and shape stacked above it get first refusal
 * on the touch — dragging a corner must not also drop a new one underneath it.
 */
private fun addTaplistener(map: MapView, onTapCorner: (Double, Double) -> Unit) {
    map.overlays.add(
        0,
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p ?: return false
                onTapCorner(p.latitude, p.longitude)
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        })
    )
}

/** The land taking shape: a filled ring once it closes, a line before that. */
private fun addShape(map: MapView, points: List<LandGeoPoint>, colour: Int) {
    if (points.size < 2) return
    val osm = points.map { GeoPoint(it.latitude, it.longitude) }

    if (points.size >= 3) {
        map.overlays.add(
            Polygon(map).apply {
                setPoints(osm)
                fillPaint.color = AndroidColor.argb(60, AndroidColor.red(colour),
                    AndroidColor.green(colour), AndroidColor.blue(colour))
                outlinePaint.color = colour
                outlinePaint.strokeWidth = 4f
                // The shape is a readout, not a control; a tap on it belongs to
                // the map underneath so a corner can still be placed inside.
                setOnClickListener { _, _, _ -> false }
            }
        )
    } else {
        map.overlays.add(
            Polyline(map).apply {
                setPoints(osm)
                outlinePaint.color = colour
                outlinePaint.strokeWidth = 4f
                setOnClickListener { _, _, _ -> false }
            }
        )
    }
}

/**
 * One draggable marker per corner.
 *
 * The marker carries the corner's id, and a finished drag reports that id back
 * rather than a position in the list: undo or clear can land mid-gesture, and an
 * index captured at drag start would by then mean a different corner, or none.
 */
private fun addCornerMarkers(
    map: MapView,
    corners: List<DraftCorner>,
    colour: Int,
    onMoveCorner: (String, Double, Double) -> Unit
) {
    corners.forEach { corner ->
        map.overlays.add(
            Marker(map).apply {
                id = corner.id
                position = GeoPoint(corner.point.latitude, corner.point.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                isDraggable = true
                icon = ShapeDrawable(OvalShape()).apply {
                    intrinsicWidth = 34
                    intrinsicHeight = 34
                    paint.color = colour
                }
                setOnMarkerClickListener { _, _ -> true }
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

/** Where the phone is, so the drawing has a reference even over blank tiles. */
private fun addLocationMarker(map: MapView, location: Pair<Double, Double>) {
    map.overlays.add(
        Marker(map).apply {
            position = GeoPoint(location.first, location.second)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ShapeDrawable(OvalShape()).apply {
                intrinsicWidth = 28
                intrinsicHeight = 28
                paint.color = AndroidColor.parseColor("#1E88E5")
            }
            setOnMarkerClickListener { _, _ -> true }
        }
    )
}

/**
 * Opens where the work is: on the corners already drawn, else on the coordinates
 * the form holds, else on the phone. Returns false when there is nothing to
 * centre on, so the caller keeps trying as a fix arrives.
 */
private fun centreOnStart(
    map: MapView,
    state: LandEditUiState,
    currentLocation: Pair<Double, Double>?
): Boolean {
    val drawn = state.draftPoints
    if (drawn.size >= 2) {
        val box = BoundingBox.fromGeoPointsSafe(
            drawn.map { GeoPoint(it.latitude, it.longitude) }
        ).increaseByScale(1.4f)
        map.zoomToBoundingBox(box, false)
        return true
    }
    val centre = when {
        drawn.size == 1 -> GeoPoint(drawn[0].latitude, drawn[0].longitude)
        state.hasCoordinates -> GeoPoint(
            state.latitude.toDouble(),
            state.longitude.toDouble()
        )
        currentLocation != null -> GeoPoint(currentLocation.first, currentLocation.second)
        else -> return false
    }
    map.controller.setZoom(CORNER_ZOOM)
    map.controller.setCenter(centre)
    return true
}

/**
 * How to use the map, and — when no offline map has been imported — that the
 * blank background is expected. Never blocks the map: coordinates do not need
 * imagery, so every control below still works over an empty backdrop.
 */
@Composable
private fun HintBanner(hasVectorMap: Boolean, modifier: Modifier = Modifier) {
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
            if (!hasVectorMap) {
                Text(
                    stringResource(R.string.corner_picker_no_map),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Everything that acts on the draft, kept in reach of a thumb. */
@Composable
private fun PickerControls(
    state: LandEditUiState,
    onUseGps: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val corners = state.draftBoundary.size
    val busy = state.isCapturingCorner

    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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

            CornerReadout(state = state, corners = corners)

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
