package com.landpoint.app.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.landpoint.app.R
import com.landpoint.app.data.model.Land
import com.landpoint.app.data.model.Photo
import androidx.compose.ui.res.pluralStringResource
import com.landpoint.app.ui.components.LandShapeThumb
import com.landpoint.app.ui.components.PARCEL_ZOOM
import com.landpoint.app.ui.components.applyTileTheme
import com.landpoint.app.ui.components.boundsOf
import com.landpoint.app.ui.components.cornerMarkerIcon
import com.landpoint.app.ui.components.describeForAccessibility
import com.landpoint.app.ui.components.drawBoundary
import com.landpoint.app.ui.components.rememberLandMapView
import com.landpoint.app.util.AreaFormat
import com.landpoint.app.util.GeoPoint
import com.landpoint.app.util.GeoUtils
import com.landpoint.app.util.PolygonMath
import com.landpoint.app.util.ShareUtils
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.overlay.Marker
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onCompass: (String) -> Unit,
    onShape: (String) -> Unit,
    viewModel: LandDetailViewModel = viewModel(factory = LandDetailViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val vectorSource by viewModel.vectorSource.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> uri?.let { viewModel.exportPdf(it) } }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val land = state.land

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(land?.name ?: stringResource(R.string.detail_title_fallback)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (land != null) {
                        IconButton(onClick = { ShareUtils.shareLand(context, land, state.dms) }) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = stringResource(R.string.action_share)
                            )
                        }
                        IconButton(onClick = { onEdit(land.id) }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.action_edit)
                            )
                        }
                        // Delete used to sit directly beside Edit. Two icons, one
                        // of them irreversible, a thumb's width apart — moving it
                        // behind the overflow costs a tap and prevents a misfire.
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.action_more)
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_export_pdf)) },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
                                    },
                                    onClick = {
                                        showMenu = false
                                        pdfLauncher.launch(viewModel.suggestPdfName())
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.action_delete),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showDeleteDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
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

            else -> DetailContent(
                land = land,
                state = state,
                vectorSource = vectorSource,
                onNavigate = { ShareUtils.navigateTo(context, land) },
                onCompass = { onCompass(land.id) },
                onShape = { onShape(land.id) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            )
        }
    }

    if (showDeleteDialog && land != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_dialog_title, land.name)) },
            text = { Text(stringResource(R.string.delete_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete(onBack)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun DetailContent(
    land: Land,
    state: LandDetailUiState,
    vectorSource: MapsForgeTileSource?,
    onNavigate: () -> Unit,
    onCompass: () -> Unit,
    onShape: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // The outline and the area, side by side and large: between them
                // they say which plot this is and how big it is, which is what the
                // record is kept for.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LandShapeThumb(land = land, size = 72.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(
                                if (land.isPolygon) R.string.label_shape_polygon
                                else R.string.label_shape_point
                            ).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        land.areaSqm?.let { area ->
                            Text(
                                stringResource(
                                    state.areaUnit.valueRes,
                                    AreaFormat.value(area, state.areaUnit)
                                ),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Text(
                    stringResource(R.string.label_coordinates).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    if (state.dms) GeoUtils.formatDMS(land.latitude, land.longitude)
                    else GeoUtils.formatDecimal(land.latitude, land.longitude),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                state.distanceMeters?.let { distance ->
                    val cardinals = stringArrayResource(R.array.cardinal_directions)
                    val away = stringResource(
                        R.string.detail_distance_away,
                        GeoUtils.formatDistance(distance, state.imperial)
                    )
                    Text(
                        buildString {
                            append(away)
                            state.bearing?.let {
                                append(" · ${cardinals[GeoUtils.cardinalIndex(it)]}")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        BoundaryMapCard(
            land = land,
            boundary = state.boundary,
            vectorSource = vectorSource,
            onOpen = onShape
        )

        if (state.boundary.isNotEmpty()) {
            CornerCoordinates(boundary = state.boundary, dms = state.dms)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onNavigate, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  " + stringResource(R.string.action_navigate))
            }
            OutlinedButton(onClick = onCompass, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  " + stringResource(R.string.action_compass))
            }
        }

        if (land.photos.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(land.photos, key = { it.id }) { photo ->
                    DetailPhoto(photo)
                }
            }
        }

        DetailField(
            stringResource(R.string.label_description),
            land.description.takeIf { it.isNotBlank() }
        )
        DetailField(
            stringResource(R.string.label_notes),
            land.notes.takeIf { it.isNotBlank() }
        )
        DetailField(
            stringResource(R.string.label_parcel_number),
            land.parcelNumber?.takeIf { it.isNotBlank() }
        )
        DetailField(
            stringResource(R.string.label_address),
            land.address?.takeIf { it.isNotBlank() }
        )
        DetailField(
            stringResource(R.string.label_altitude),
            land.altitude?.let { stringResource(R.string.value_metres, it.toInt()) }
        )
        DetailField(
            stringResource(R.string.label_gps_accuracy),
            land.accuracy?.let { stringResource(R.string.value_accuracy, it.toInt()) }
        )
        DetailField(
            stringResource(R.string.label_area),
            land.areaSqm?.let {
                stringResource(
                    state.areaUnit.valueRes,
                    AreaFormat.value(it, state.areaUnit)
                )
            }
        )
        if (land.isPolygon) {
            val corners = land.boundary
            DetailField(
                stringResource(R.string.boundary_title),
                pluralStringResource(R.plurals.boundary_corners, corners.size, corners.size)
            )
            DetailField(
                stringResource(R.string.label_perimeter),
                stringResource(
                    R.string.value_metres,
                    PolygonMath.perimeterM(corners).roundToInt()
                )
            )
        }
        DetailField(
            stringResource(R.string.label_saved),
            dateFormat.format(Date(land.createdAt))
        )
        if (land.updatedAt != land.createdAt) {
            DetailField(
                stringResource(R.string.label_last_edited),
                dateFormat.format(Date(land.updatedAt))
            )
        }

        Text(
            stringResource(R.string.legal_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * The plot as it sits on the ground, tappable through to the full-screen map.
 *
 * Deliberately not interactive: this card lives inside a scrolling form, and a
 * pannable map here would swallow every drag meant for the page. It is a picture
 * with one gesture — a tap that opens the real map, where panning belongs.
 */
@Composable
private fun BoundaryMapCard(
    land: Land,
    boundary: List<GeoPoint>,
    vectorSource: MapsForgeTileSource?,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val boundaryColour = MaterialTheme.colorScheme.primary.toArgb()
    val openLabel = stringResource(R.string.shape_open_preview)
    // Said by the transparent button in front of the map, not by the map: one
    // stop in the reading order, describing the picture and what tapping it does.
    val mapDescription = if (boundary.size >= 2) {
        stringResource(
            R.string.detail_a11y_map,
            pluralStringResource(R.plurals.boundary_corners, boundary.size, boundary.size)
        )
    } else {
        stringResource(R.string.detail_a11y_map_point)
    }

    val mapView = rememberLandMapView(
        vectorSource = vectorSource,
        interactive = false,
        maxZoomWithoutVector = PARCEL_ZOOM
    )
    val hasCentred = remember(mapView) { mutableStateOf(false) }

    Card {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { map ->
                    map.applyTileTheme(isDark)
                    map.describeForAccessibility(null)
                    map.overlays.clear()

                    map.drawBoundary(boundary, boundaryColour)
                    boundary.forEachIndexed { index, point ->
                        map.overlays.add(
                            Marker(map).apply {
                                position = OsmGeoPoint(point.latitude, point.longitude)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = cornerMarkerIcon(
                                    context,
                                    (index + 1).toString(),
                                    boundaryColour
                                )
                                // No info window: the tap belongs to the card.
                                setOnMarkerClickListener { _, _ -> false }
                            }
                        )
                    }
                    if (boundary.isEmpty()) {
                        map.overlays.add(
                            Marker(map).apply {
                                position = OsmGeoPoint(land.latitude, land.longitude)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                setOnMarkerClickListener { _, _ -> false }
                            }
                        )
                    }

                    if (!hasCentred.value) {
                        val box = boundsOf(boundary)
                        if (box != null) {
                            map.post { map.zoomToBoundingBox(box, false) }
                        } else {
                            map.controller.setZoom(PARCEL_ZOOM - 2.0)
                            map.controller.setCenter(OsmGeoPoint(land.latitude, land.longitude))
                        }
                        hasCentred.value = true
                    }
                    map.invalidate()
                }
            )

            // In front of the map, because the MapView consumes touches itself.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .semantics { contentDescription = mapDescription }
                    .clickable(onClickLabel = openLabel, onClick = onOpen)
            )
        }
    }
}

/**
 * Every corner, numbered and in order.
 *
 * The numbers matter as much as the coordinates: the order is the outline, and
 * this is the list someone reads against a survey letter. A walked boundary can
 * hold hundreds of points, so only the first few are shown until asked.
 */
@Composable
private fun CornerCoordinates(boundary: List<GeoPoint>, dms: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val collapsedCount = 10
    val shown = if (expanded) boundary else boundary.take(collapsedCount)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.boundary_corners_title).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        shown.forEachIndexed { index, point ->
            val coordinates = stringResource(
                R.string.boundary_corner_number,
                index + 1,
                if (dms) GeoUtils.formatDMS(point.latitude, point.longitude)
                else GeoUtils.formatDecimal(point.latitude, point.longitude)
            )
            // How well the corner was known when it was taken. On a record being
            // read back — or shown to someone else — that is part of what the
            // coordinates mean.
            val accuracy = point.accuracyM?.let {
                stringResource(R.string.boundary_corner_accuracy, GeoUtils.formatAccuracy(it))
            }
            Text(
                if (accuracy == null) coordinates else "$coordinates  $accuracy",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
        }
        if (boundary.size > collapsedCount) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) stringResource(R.string.boundary_corners_collapse)
                    else stringResource(R.string.boundary_corners_show_all, boundary.size)
                )
            }
        }
    }
}

@Composable
private fun DetailField(label: String, value: String?) {
    if (value == null) return
    ListItem(
        headlineContent = { Text(value, style = MaterialTheme.typography.bodyLarge) },
        overlineContent = {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        // The rows sit on the screen's own background rather than a raised
        // surface, so a column of them reads as one block instead of a stack of
        // separate cards.
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * One photo, with its caption underneath when there is one.
 *
 * A boundary marker photographed from the road looks like any other patch of
 * ground a year later; the caption is what makes it evidence.
 */
@Composable
private fun DetailPhoto(photo: Photo) {
    val path = photo.filePath
    val bitmap = remember(path) {
        runCatching {
            android.graphics.BitmapFactory.decodeFile(
                path,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            )
        }.getOrNull()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.size(width = 160.dp, height = 200.dp)
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(MaterialTheme.shapes.medium)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = photo.caption.ifBlank {
                        stringResource(R.string.photo_description)
                    },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (photo.caption.isNotBlank()) {
            Text(
                photo.caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}
