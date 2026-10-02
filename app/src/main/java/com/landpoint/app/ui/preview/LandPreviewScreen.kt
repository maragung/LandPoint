package com.landpoint.app.ui.preview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.landpoint.app.R
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.ui.components.LandMap
import com.landpoint.app.ui.components.MapAttribution
import com.landpoint.app.ui.components.MapCorner
import com.landpoint.app.ui.components.MapScaleBar
import com.landpoint.app.ui.components.MapShape
import com.landpoint.app.ui.components.MapSideControls
import com.landpoint.app.ui.components.MapTopChrome
import com.landpoint.app.ui.components.PARCEL_ZOOM
import com.landpoint.app.ui.components.rememberLandMapController
import com.landpoint.app.ui.components.rememberMapPrefs
import com.landpoint.app.util.AreaFormat
import com.landpoint.app.util.GeoUtils
import com.landpoint.app.util.PolygonMath
import kotlin.math.roundToInt

/**
 * Preview of the unsaved shape on its own screen.
 *
 * Lines connect the corners in order automatically (closed when >= 3), so what
 * is seen here is what saving will store. From here the user can save, go back
 * to keep editing (add photos, move corners), or discard — every destructive
 * choice asks first.
 */
@Composable
fun LandPreviewScreen(
    onBackToEdit: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    areaUnit: SettingsRepository.AreaUnit = SettingsRepository.AreaUnit.SQM
) {
    val draft = remember { PreviewDraftStore.draft }
    val points = remember(draft) { draft.corners.map { it.toGeoPoint() } }
    val prefs = rememberMapPrefs()
    val controller = rememberLandMapController()
    val accent = MaterialTheme.colorScheme.primary.toArgb()

    var showDiscard by remember { mutableStateOf(false) }
    BackHandler { onBackToEdit() }

    val area = if (points.size >= 3) PolygonMath.areaSqm(points) else null
    val perimeter = when {
        points.size >= 3 -> PolygonMath.perimeterM(points)
        points.size == 2 -> PolygonMath.pathLengthM(points)
        else -> null
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_body)) },
            confirmButton = {
                TextButton(onClick = { showDiscard = false }) {
                    Text(stringResource(R.string.discard_keep))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false; onDiscard() }) {
                    Text(stringResource(R.string.discard_throw))
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (points.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.preview_empty))
            }
        } else {
            val shapes = remember(points) {
                listOf(MapShape(id = "preview", points = points))
            }
            val corners = remember(draft) {
                draft.corners.mapIndexed { index, corner ->
                    MapCorner(
                        id = corner.id,
                        latitude = corner.latitude,
                        longitude = corner.longitude,
                        label = (index + 1).toString(),
                        selected = false
                    )
                }
            }
            androidx.compose.runtime.LaunchedEffect(points) {
                controller.frameOnce(points, PARCEL_ZOOM)
            }
            LandMap(
                style = prefs.style,
                controller = controller,
                accentColour = accent,
                modifier = Modifier.fillMaxSize(),
                shapes = shapes,
                corners = corners,
                contentDescription = stringResource(R.string.preview_title)
            )
        }

        Column(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            MapTopChrome(
                onBack = onBackToEdit,
                title = draft.name.ifBlank { stringResource(R.string.preview_title) }
            )
        }

        MapSideControls(
            modifier = Modifier.align(Alignment.CenterEnd),
            onZoomIn = controller::zoomIn,
            onZoomOut = controller::zoomOut,
            onMyLocation = null
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                MapScaleBar(controller = controller, imperial = prefs.imperial)
                Box(modifier = Modifier.weight(1f))
                MapAttribution(prefs.mode)
            }
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
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (area != null) {
                        Text(
                            stringResource(areaUnit.valueRes, AreaFormat.value(area, areaUnit)),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                    val cornerText = pluralStringResource(
                        R.plurals.boundary_corners, points.size, points.size
                    )
                    val periText = perimeter?.let {
                        stringResource(R.string.boundary_perimeter, it.roundToInt().toString())
                    }
                    Text(
                        if (periText == null) cornerText else "$periText · $cornerText",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.preview_auto_line),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (draft.corners.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            itemsIndexed(
                                draft.corners,
                                key = { _, corner -> corner.id }
                            ) { index, corner ->
                                val count = draft.photoCounts[corner.id] ?: 0
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.boundary_corner_number,
                                            index + 1,
                                            GeoUtils.formatDecimal(
                                                corner.latitude, corner.longitude
                                            )
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (count > 0) {
                                        Icon(
                                            Icons.Outlined.PhotoCamera,
                                            contentDescription = pluralStringResource(
                                                R.plurals.corner_photo_count, count, count
                                            ),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onBackToEdit,
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.preview_back_edit)) }
                        Button(
                            onClick = onSave,
                            enabled = points.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.preview_save)) }
                    }
                    TextButton(onClick = { showDiscard = true }) {
                        Text(stringResource(R.string.discard_throw))
                    }
                }
            }
        }
    }
}
