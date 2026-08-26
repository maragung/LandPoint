package com.landpoint.app.ui.offline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.landpoint.app.R
import com.landpoint.app.map.offline.RegionStatus
import com.landpoint.app.map.offline.SavedRegion
import com.landpoint.app.ui.components.BasemapSheet
import com.landpoint.app.ui.components.LandMap
import com.landpoint.app.ui.components.MapAttribution
import com.landpoint.app.ui.components.MapScaleBar
import com.landpoint.app.ui.components.MapSideControls
import com.landpoint.app.ui.components.MapStyleAction
import com.landpoint.app.ui.components.MapTopChrome
import com.landpoint.app.ui.components.rememberLandMapController
import com.landpoint.app.ui.components.rememberMapPrefs
import com.landpoint.app.util.Localization
import com.landpoint.app.util.formatBytes
import com.landpoint.app.util.formatCount
import kotlin.math.roundToLong

/**
 * How far the selection frame sits from each edge of the map.
 *
 * Wide enough that the ground outside it is visible — the frame has to read as a
 * window onto a larger map, or a user cannot tell whether the edge of the area is
 * where they wanted it.
 */
private val SELECTION_INSET = 28.dp

/** Where the map opens when there is one saved place to open it at. */
private const val ANCHOR_ZOOM = 12.0

/**
 * The zoom levels a download may be asked for.
 *
 * Not the full 0–21 the camera allows: how deep you save is not how deep you look.
 * Past 19 the street tiles are being redrawn from geometry the phone already has, so
 * saving another level would double the download for no extra detail. Below 6 an area
 * is a continent and the tiles are shared by every download anyway; the interesting
 * range for a plot of land is the top of it.
 */
private const val ZOOM_FLOOR = 6
private const val ZOOM_CEILING = 19
private const val DEFAULT_MIN_ZOOM = 12
private const val DEFAULT_MAX_ZOOM = 17

/** Below this an area is measured to a decimal, above it to the nearest whole. */
private const val FINE_AREA_LIMIT = 10.0

/**
 * Saving part of the map to the phone.
 *
 * The area is chosen by moving the map rather than by dragging a box: the frame is
 * fixed to the screen and the ground moves under it. That is the one design here that
 * everything else follows from — it needs no handles to hit, cannot be confused with
 * the map's own pan and pinch, survives rotation without arithmetic, and works the
 * same on a phone held in one hand as on a tablet.
 *
 * What can actually be saved is decided elsewhere: [OfflineMapsViewModel] costs the
 * frame against the limits, and the style itself says whether its licence permits
 * being stored at all. Both refusals are shown as sentences next to a disabled
 * button, never as a control that quietly does nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(
    onBack: () -> Unit,
    viewModel: OfflineMapsViewModel = viewModel(factory = OfflineMapsViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val anchor by viewModel.anchor.collectAsStateWithLifecycle()
    val prefs = rememberMapPrefs()
    val controller = rememberLandMapController()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val snackbarHost = remember { SnackbarHostState() }
    val insetPx = with(LocalDensity.current) { SELECTION_INSET.toPx() }

    var minZoom by rememberSaveable { mutableStateOf(DEFAULT_MIN_ZOOM) }
    var maxZoom by rememberSaveable { mutableStateOf(DEFAULT_MAX_ZOOM) }
    var listVisible by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }

    // Switches the controller's frame sampling on. Idempotent, and in a SideEffect so
    // it runs after composition succeeds rather than during it.
    SideEffect { controller.trackSelection(insetPx) }

    val provider = prefs.provider
    val selection = controller.selection

    LaunchedEffect(anchor) { controller.frameOnce(anchor, ANCHOR_ZOOM) }

    // Re-costed whenever the frame or the zoom range changes. GeoBounds is a data
    // class, so panning back to where the map already was does not recost anything.
    LaunchedEffect(provider, selection, minZoom, maxZoom) {
        if (provider != null && selection != null) {
            viewModel.propose(provider, selection, minZoom, maxZoom)
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // A licence refusal outranks the budget: an area that is the right size is still
    // not downloadable from imagery that may not be stored, and saying "too large"
    // about it would send the user off to fix the wrong thing.
    val licenceRefusal = provider
        ?.takeIf { !it.allowsOfflineDownload }
        ?.mode?.offlineRefusalRes
        ?.let { stringResource(it) }
    val refusal = licenceRefusal ?: state.refusal
    val plan = state.quote?.plan
    val canStart = provider != null &&
        selection != null &&
        licenceRefusal == null &&
        state.quote?.allowed == true &&
        !state.busy

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LandMap(
                    style = prefs.style,
                    controller = controller,
                    accentColour = accent,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = stringResource(R.string.offline_a11y_map)
                )

                SelectionFrame(
                    insetPx = insetPx,
                    colour = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize()
                )

                MapTopChrome(
                    modifier = Modifier.align(Alignment.TopStart),
                    onBack = onBack,
                    title = stringResource(R.string.offline_title),
                    actions = { MapStyleAction { prefs.showSheet() } }
                )

                MapSideControls(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onZoomIn = controller::zoomIn,
                    onZoomOut = controller::zoomOut,
                    // Nothing to go to: this screen never asks for a position, so a
                    // button that would need one has nothing to do.
                    onMyLocation = null
                )

                MapScaleBar(
                    controller = controller,
                    imperial = prefs.imperial,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )

                MapAttribution(
                    prefs.mode,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

            DownloadPanel(
                minZoom = minZoom,
                maxZoom = maxZoom,
                onZoomRange = { low, high ->
                    minZoom = low
                    maxZoom = high
                },
                estimate = plan?.let {
                    stringResource(
                        R.string.offline_estimate,
                        formatCount(it.tiles),
                        formatBytes(it.estimatedBytes),
                        areaText(it.areaSquareKm)
                    )
                },
                capped = plan?.takeIf { it.cappedBySource }?.let {
                    stringResource(R.string.offline_capped, it.effectiveMaxZoom)
                },
                refusal = refusal,
                savedCount = state.saved.size,
                canStart = canStart,
                onStart = { confirming = true },
                onOpenSaved = { listVisible = true }
            )
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (prefs.sheetVisible) {
            BasemapSheet(
                current = prefs.mode,
                hasVectorMap = prefs.hasVectorMap,
                onPick = prefs::select,
                onDismiss = prefs::hideSheet
            )
        }

        if (listVisible) {
            SavedAreasSheet(
                saved = state.saved,
                activeId = state.active?.id,
                activeStatus = state.active?.status,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onUpdate = viewModel::update,
                onRename = { renaming = it },
                onDelete = { deleting = it },
                onClearCache = viewModel::clearBrowsingCache,
                onDismiss = { listVisible = false }
            )
        }

        if (confirming && provider != null && selection != null) {
            NameDialog(
                title = stringResource(R.string.offline_start),
                initial = stringResource(R.string.offline_default_name, state.saved.size + 1),
                confirm = stringResource(R.string.offline_start),
                onConfirm = { chosen ->
                    confirming = false
                    viewModel.start(provider, chosen, selection, minZoom, maxZoom)
                },
                onDismiss = { confirming = false }
            )
        }

        renaming?.let { id ->
            val region = state.saved.firstOrNull { it.id == id }
            NameDialog(
                title = stringResource(R.string.offline_rename_title),
                initial = region?.meta?.name ?: stringResource(R.string.offline_area_unnamed),
                confirm = stringResource(R.string.action_save),
                onConfirm = { chosen ->
                    renaming = null
                    viewModel.rename(id, chosen)
                },
                onDismiss = { renaming = null }
            )
        }

        deleting?.let { id ->
            val region = state.saved.firstOrNull { it.id == id }
            val label = region?.meta?.name ?: stringResource(R.string.offline_area_unnamed)
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(stringResource(R.string.offline_delete_title, label)) },
                text = { Text(stringResource(R.string.offline_delete_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        deleting = null
                        viewModel.delete(id, label)
                    }) { Text(stringResource(R.string.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

/**
 * The window the download is taken from.
 *
 * Drawn as four bands of shade around a hole rather than as one translucent layer
 * with a cut-out: four rectangles need no graphics layer, no blend mode and no
 * off-screen buffer, on a view that is already asking a lot of the GPU. Nothing here
 * takes touches, so the map underneath still pans and pinches through the shade.
 */
@Composable
private fun SelectionFrame(insetPx: Float, colour: Color, modifier: Modifier = Modifier) {
    val shade = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    Canvas(modifier = modifier) {
        val inset = insetPx.coerceAtMost(minOf(size.width, size.height) / 2f)
        val innerWidth = size.width - 2 * inset
        val innerHeight = size.height - 2 * inset
        drawRect(shade, size = Size(size.width, inset))
        drawRect(
            shade,
            topLeft = Offset(0f, size.height - inset),
            size = Size(size.width, inset)
        )
        drawRect(shade, topLeft = Offset(0f, inset), size = Size(inset, innerHeight))
        drawRect(
            shade,
            topLeft = Offset(size.width - inset, inset),
            size = Size(inset, innerHeight)
        )
        drawRect(
            colour,
            topLeft = Offset(inset, inset),
            size = Size(innerWidth, innerHeight),
            style = Stroke(width = strokePx)
        )
    }
}

/** The controls under the map: how deep to save, what it will cost, and go. */
@Composable
private fun DownloadPanel(
    minZoom: Int,
    maxZoom: Int,
    onZoomRange: (Int, Int) -> Unit,
    estimate: String?,
    capped: String?,
    refusal: String?,
    savedCount: Int,
    canStart: Boolean,
    onStart: () -> Unit,
    onOpenSaved: () -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.offline_frame_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(R.string.offline_detail, minZoom, maxZoom),
                style = MaterialTheme.typography.labelLarge
            )

            RangeSlider(
                value = minZoom.toFloat()..maxZoom.toFloat(),
                onValueChange = { range ->
                    onZoomRange(
                        range.start.roundToLong().toInt(),
                        range.endInclusive.roundToLong().toInt()
                    )
                },
                valueRange = ZOOM_FLOOR.toFloat()..ZOOM_CEILING.toFloat(),
                steps = ZOOM_CEILING - ZOOM_FLOOR - 1
            )

            Text(
                text = estimate ?: stringResource(R.string.offline_estimate_waiting),
                style = MaterialTheme.typography.bodyMedium
            )

            capped?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            refusal?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStart,
                    enabled = canStart,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.offline_start)) }
                OutlinedButton(onClick = onOpenSaved) {
                    Text(stringResource(R.string.offline_saved_open, savedCount))
                }
            }
        }
    }
}

/**
 * Every area already on the phone.
 *
 * [activeStatus] is preferred over the region's own snapshot for the one region that
 * is downloading, because that snapshot was taken when the list was last read and the
 * live one arrives several times a second.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedAreasSheet(
    saved: List<SavedRegion>,
    activeId: Long?,
    activeStatus: RegionStatus?,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onUpdate: (Long) -> Unit,
    onRename: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onClearCache: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.offline_saved_title),
                style = MaterialTheme.typography.titleMedium
            )

            if (saved.isEmpty()) {
                Text(
                    text = stringResource(R.string.offline_saved_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            saved.forEach { region ->
                SavedAreaRow(
                    region = region,
                    status = activeStatus.takeIf { region.id == activeId } ?: region.status,
                    onPause = { onPause(region.id) },
                    onResume = { onResume(region.id) },
                    onUpdate = { onUpdate(region.id) },
                    onRename = { onRename(region.id) },
                    onDelete = { onDelete(region.id) }
                )
                HorizontalDivider()
            }

            TextButton(onClick = onClearCache, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.offline_clear_cache))
            }
        }
    }
}

@Composable
private fun SavedAreaRow(
    region: SavedRegion,
    status: RegionStatus,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onUpdate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val meta = region.meta
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = meta?.name ?: stringResource(R.string.offline_area_unnamed),
            style = MaterialTheme.typography.titleSmall
        )

        // Absent for a region this app did not write, which is exactly when the row
        // still has to be usable: deleting it is the only thing left to do with it.
        meta?.let {
            Text(
                text = stringResource(
                    R.string.offline_area_extent,
                    it.minZoom,
                    it.maxZoom,
                    areaText(it.bounds.areaSquareKm)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = when {
                status.downloading -> stringResource(
                    R.string.offline_area_working,
                    formatCount(status.completedTiles),
                    formatBytes(status.completedBytes)
                )
                status.complete -> stringResource(
                    R.string.offline_area_ready,
                    formatBytes(status.completedBytes)
                )
                else -> stringResource(
                    R.string.offline_area_partial,
                    formatBytes(status.completedBytes)
                )
            },
            style = MaterialTheme.typography.bodySmall
        )

        if (status.downloading) {
            val fraction = status.fraction
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // The ceiling is the app's own limit, not a failure of the download, so it is
        // said plainly: what was fetched before it was reached is still usable.
        if (status.tileCeiling != null && !status.complete) {
            Text(
                text = stringResource(R.string.offline_area_ceiling),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                status.downloading -> TextButton(onClick = onPause) {
                    Text(stringResource(R.string.offline_pause))
                }
                status.complete -> TextButton(onClick = onUpdate) {
                    Text(stringResource(R.string.offline_update))
                }
                else -> TextButton(onClick = onResume) {
                    Text(stringResource(R.string.offline_resume))
                }
            }
            TextButton(onClick = onRename) { Text(stringResource(R.string.offline_rename)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
        }
    }
}

/** One text field in a dialog, used for both naming and renaming an area. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.offline_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim().ifBlank { initial }) }
            ) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * An area in square kilometres, to a decimal while that decimal still means
 * something. A plot-sized selection reading "0 km²" would look broken.
 */
private fun areaText(squareKm: Double): String = if (squareKm < FINE_AREA_LIMIT) {
    "%.1f".format(Localization.numberLocale(), squareKm)
} else {
    formatCount(squareKm.roundToLong())
}
