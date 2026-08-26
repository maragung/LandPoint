package com.landpoint.app.ui.edit

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.East
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.South
import androidx.compose.material.icons.filled.West
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.landpoint.app.R
import com.landpoint.app.ui.components.BasemapSheet
import com.landpoint.app.ui.components.LandMap
import com.landpoint.app.ui.components.MapAttribution
import com.landpoint.app.ui.components.MapControl
import com.landpoint.app.ui.components.MapCorner
import com.landpoint.app.ui.components.MapCrosshair
import com.landpoint.app.ui.components.MapFitAction
import com.landpoint.app.ui.components.MapFix
import com.landpoint.app.ui.components.MapScaleBar
import com.landpoint.app.ui.components.MapShape
import com.landpoint.app.ui.components.MapSideControls
import com.landpoint.app.ui.components.MapStyleAction
import com.landpoint.app.ui.components.MapTap
import com.landpoint.app.ui.components.MapTopChrome
import com.landpoint.app.ui.components.PARCEL_ZOOM
import com.landpoint.app.ui.components.rememberLandMapController
import com.landpoint.app.ui.components.rememberMapPrefs
import com.landpoint.app.util.AreaFormat
import com.landpoint.app.util.GeoPoint
import com.landpoint.app.util.GeoUtils
import com.landpoint.app.util.Localization
import com.landpoint.app.util.MarkSource
import com.landpoint.app.util.PendingMark
import com.landpoint.app.util.Placement

/**
 * How close a tap has to be to an outline to count as landing *on* it rather
 * than beside it. Smaller than a touch target on purpose: overshooting turns an
 * intended new corner into a corner inserted mid-ring, which is the more
 * surprising of the two mistakes.
 */
private val EDGE_TOUCH_TARGET = 20.dp

/**
 * Picks boundary corners on a map that fills the screen.
 *
 * Every corner now arrives through here — tapped, typed off a certificate,
 * measured as a bearing, averaged from the receiver or copied from the parcel
 * next door — because all five used to be able to write a corner without the
 * user ever seeing that spot on their own land.
 *
 * Nothing here commits on contact. A touch, a button or a dialog *hangs a mark*:
 * a ring drawn on top of the map that can be dragged under a finger, nudged a
 * tenth of a metre at a time, and then used or thrown away. Two levels of "not
 * yet" — the mark is not a corner until Use, and the draft is not the boundary
 * until Done — so backing out of either loses only what was provisional.
 *
 * The screen is the map. Placing a corner means judging a spot against a tree, a
 * roof line or the edge of a field, and every strip of chrome is a strip of that
 * judgement taken away — so what stays on screen while nothing is happening is a
 * scale bar, the map's credit, and icons.
 */
@Composable
fun CornerPickerScreen(
    state: LandEditUiState,
    currentLocation: GeoPoint?,
    onMessageShown: () -> Unit,
    onHintShown: () -> Unit,
    onPlaceMark: (Double, Double, Double) -> Unit,
    onMoveMark: (Double, Double, Double) -> Unit,
    onNudgeMark: (Double) -> Unit,
    onMarkStep: (Double) -> Unit,
    onMarkFromFix: () -> Unit,
    onMarkTyped: (String, String, Double) -> Int?,
    onMarkFromBearing: (Int, String, String) -> Int?,
    onUseMark: () -> Unit,
    onDiscardMark: () -> Unit,
    onLoadNeighbours: () -> Unit,
    onTakeFromNeighbour: (String, Set<Int>) -> Unit,
    onMoveCorner: (String, Double, Double) -> Unit,
    onSelectCorner: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val highlight = MaterialTheme.colorScheme.tertiary.toArgb()

    val mark = state.pendingMark

    // One step of undo before the screen closes. Someone who has lined a mark up
    // and then reaches for back nearly always means "not that spot", and closing
    // the whole picker would take the draft with it.
    val stepBack: () -> Unit = { if (mark != null) onDiscardMark() else onCancel() }
    BackHandler(onBack = stepBack)

    val prefs = rememberMapPrefs()
    val controller = rememberLandMapController()

    var dialog by remember { mutableStateOf<PickerDialog?>(null) }

    // A finger is a fixed size on the glass and a wildly varying distance on the
    // ground, so how far a mark may reach for a side can only be answered at the
    // zoom it was placed at. Taps get this from the map view; the buttons here
    // have to work it out from the scale in force when they are pressed, which is
    // why it is a lambda rather than a value read during composition.
    val tolerancePx = with(LocalDensity.current) { EDGE_TOUCH_TARGET.toPx() }
    val reach: () -> Double = { controller.metresPerPixel * tolerancePx }

    // Where to open, and where the fit button goes back to: the corners already
    // drawn, else the coordinates the form holds, else the phone.
    val framePoints: List<GeoPoint> = remember(
        state.draftPoints,
        state.latitude,
        state.longitude,
        currentLocation
    ) {
        state.draftPoints.ifEmpty {
            val formLat = state.latitude.toDoubleOrNull()
            val formLon = state.longitude.toDoubleOrNull()
            when {
                state.hasCoordinates && formLat != null && formLon != null ->
                    listOf(GeoPoint(formLat, formLon))
                currentLocation != null -> listOf(currentLocation)
                else -> emptyList()
            }
        }
    }

    val shapes = remember(state.draftPoints) {
        listOf(MapShape(id = DRAFT_SHAPE_ID, points = state.draftPoints))
    }
    val corners = remember(state.draftBoundary, state.selectedCornerId) {
        state.draftBoundary.mapIndexed { index, corner ->
            MapCorner(
                id = corner.id,
                latitude = corner.point.latitude,
                longitude = corner.point.longitude,
                label = (index + 1).toString(),
                selected = corner.id == state.selectedCornerId
            )
        }
    }
    val fix = currentLocation?.let {
        MapFix(latitude = it.latitude, longitude = it.longitude, accuracyM = it.accuracyM)
    }

    // Touching a spot on the glass is not a gesture a screen reader can offer, so
    // the description says how many corners stand and then how to do the whole job
    // without the map: place a mark at the centre, walk it there with the four
    // arrows, or type the coordinates in. All three now live on this screen.
    val mapDescription = stringResource(
        R.string.picker_a11y_map,
        pluralStringResource(
            R.plurals.boundary_corners,
            state.draftBoundary.size,
            state.draftBoundary.size
        )
    )

    // Declared, not inlined at the call site, so the Unit coercion on the lambda
    // is plain to read. The editor supplies the position; there is nothing to ask
    // for here, so with no fix yet there is no button either.
    val jumpToLocation: (() -> Unit)? = currentLocation?.let { here ->
        { controller.frame(listOf(here), PARCEL_ZOOM, animated = true) }
    }

    // The form's snackbar host is not composed while this screen is up, so a
    // message raised here — a fix that never arrived, a boundary that cannot be
    // finished with a mark still hanging — had nowhere to appear until the picker
    // closed. It gets its own host.
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            onMessageShown()
        }
    }

    // Framed once per opening of the picker, and kept across rotation.
    LaunchedEffect(framePoints) { controller.frameOnce(framePoints, PARCEL_ZOOM) }

    Box(modifier = Modifier.fillMaxSize()) {
        LandMap(
            style = prefs.style,
            controller = controller,
            accentColour = accent,
            modifier = Modifier.fillMaxSize(),
            shapes = shapes,
            corners = corners,
            fix = fix,
            pending = mark?.point,
            selectedColour = highlight,
            contentDescription = mapDescription,
            touchTolerance = EDGE_TOUCH_TARGET,
            onTap = { tap ->
                when (tap) {
                    // Selects rather than swallowing the tap: a mistaken corner in
                    // the middle of a ring used to be removable only by clearing
                    // the lot.
                    is MapTap.Corner -> onSelectCorner(tap.id)
                    // Hangs a mark, or moves the one already hanging. Nothing is
                    // written to the draft by touching the map at all.
                    is MapTap.Ground ->
                        onPlaceMark(tap.point.latitude, tap.point.longitude, tap.toleranceM)
                    else -> Unit
                }
            },
            onMoveCorner = onMoveCorner,
            onMovePending = { latitude, longitude -> onMoveMark(latitude, longitude, reach()) }
        )

        // The sight the "place a mark here" button aims: gone once there is a mark,
        // because from then on the thing being positioned is the mark itself.
        if (mark == null) {
            MapCrosshair(modifier = Modifier.align(Alignment.Center))
        }

        Column(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            MapTopChrome(
                onBack = stepBack,
                title = stringResource(R.string.corner_picker_title),
                actions = {
                    MapControl(
                        icon = Icons.Default.Info,
                        contentDescription = stringResource(R.string.corner_picker_about_accuracy),
                        onClick = { dialog = PickerDialog.About }
                    )
                    MapFitAction { controller.frame(framePoints, PARCEL_ZOOM, animated = true) }
                    MapStyleAction { prefs.showSheet() }
                }
            )

            // Once, on the first opening ever, and then never again — the flag
            // behind it was already written when the picker opened, so a process
            // death mid-sentence does not bring it back. Everything it says is
            // still available afterwards behind the ⓘ.
            if (state.showCornerHint) {
                PickerNotice(
                    text = stringResource(R.string.corner_picker_tap_hint),
                    onDismiss = onHintShown,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                )
            }
        }

        MapSideControls(
            modifier = Modifier.align(Alignment.CenterEnd),
            onZoomIn = controller::zoomIn,
            onZoomOut = controller::zoomOut,
            onMyLocation = jumpToLocation
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SnackbarHost(snackbarHost, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Always. Hiding it while the tools were open was half the reason a
                // 10 m scale bar was never seen: the bar only reaches 10 m at zoom
                // 19, which is exactly where a corner gets placed.
                MapScaleBar(controller = controller, imperial = prefs.imperial)
                Box(modifier = Modifier.weight(1f))
                MapAttribution(prefs.mode)
            }

            val sources = markSources(
                state = state,
                onFromFix = onMarkFromFix,
                onByNumbers = { dialog = PickerDialog.Coordinates },
                onByBearing = { from -> dialog = PickerDialog.Bearing(from) },
                onFromNeighbour = {
                    onLoadNeighbours()
                    dialog = PickerDialog.Neighbours
                }
            )

            if (mark == null) {
                IdleControls(
                    state = state,
                    sources = sources,
                    onPlaceMark = {
                        controller.centerTarget()?.let {
                            onPlaceMark(it.latitude, it.longitude, reach())
                        }
                    },
                    onDeleteSelected = onDeleteSelected,
                    onUndo = onUndo,
                    onClear = onClear,
                    onDone = onDone
                )
            } else {
                MarkControls(
                    state = state,
                    mark = mark,
                    sources = sources,
                    onNudgeMark = onNudgeMark,
                    onMarkStep = onMarkStep,
                    onUseMark = onUseMark,
                    onDiscardMark = onDiscardMark
                )
            }
        }

        if (prefs.sheetVisible) {
            BasemapSheet(
                current = prefs.mode,
                hasVectorMap = prefs.hasVectorMap,
                onPick = prefs::select,
                onDismiss = prefs::hideSheet
            )
        }

        when (val open = dialog) {
            null -> Unit

            PickerDialog.About -> AboutAccuracyDialog(
                onlineTiles = prefs.provider?.needsNetwork == true,
                onDismiss = { dialog = null }
            )

            // No corner number in the title: which corner these figures become is
            // decided by where they land, and the confirm bar says so once the mark
            // is on the map.
            PickerDialog.Coordinates -> CornerCoordinateDialog(
                number = null,
                inserting = false,
                initial = null,
                onDismiss = { dialog = null },
                onConfirm = { latitude, longitude ->
                    val refusal = onMarkTyped(latitude, longitude, reach())
                    if (refusal == null) dialog = null
                    refusal
                }
            )

            is PickerDialog.Bearing -> {
                val origin = state.draftPoints.getOrNull(open.fromIndex)
                // Undo or clear can take the corner away from under an open dialog.
                LaunchedEffect(origin == null) { if (origin == null) dialog = null }
                if (origin != null) {
                    CornerBearingDialog(
                        fromNumber = open.fromIndex + 1,
                        newNumber = open.fromIndex + 2,
                        origin = origin,
                        onDismiss = { dialog = null },
                        onConfirm = { bearing, distance ->
                            val refusal = onMarkFromBearing(open.fromIndex, bearing, distance)
                            if (refusal == null) dialog = null
                            refusal
                        }
                    )
                }
            }

            PickerDialog.Neighbours -> NeighbourSheet(
                state = state,
                onDismiss = { dialog = null },
                onTake = { id, ticked ->
                    onTakeFromNeighbour(id, ticked)
                    dialog = null
                }
            )
        }
    }
}

/**
 * The draft outline's overlay id.
 *
 * A constant rather than the land's id: a land being created for the first time has
 * no id yet, and the overlay only needs it to tell one shape from another — there
 * is exactly one shape here.
 */
private const val DRAFT_SHAPE_ID = "draft"

/** Which of the picker's four asides is open, if any. */
private sealed interface PickerDialog {

    /** The legal note and the two sentences about what the map can and cannot do. */
    data object About : PickerDialog

    /** Coordinates typed straight off a certificate. */
    data object Coordinates : PickerDialog

    /** A bearing and a length measured out from the corner at [fromIndex]. */
    data class Bearing(val fromIndex: Int) : PickerDialog

    /** Corners copied from the parcel next door. */
    data object Neighbours : PickerDialog
}

/**
 * One of the four ways a mark can be placed without touching the map.
 *
 * A list rather than four call sites, because the same four appear twice — once
 * while nothing hangs and once beside the nudge pad — and they must not drift
 * apart between the two.
 */
private data class MarkSourceAction(
    val icon: ImageVector,
    @StringRes val label: Int,
    val enabled: Boolean,
    val busy: Boolean,
    val onClick: () -> Unit
)

/**
 * The four alternative sources, in the order they are worth trying: where the
 * phone is standing, what the certificate says, what the survey letter says, and
 * what the neighbour's record already holds.
 */
@Composable
private fun markSources(
    state: LandEditUiState,
    onFromFix: () -> Unit,
    onByNumbers: () -> Unit,
    onByBearing: (Int) -> Unit,
    onFromNeighbour: () -> Unit
): List<MarkSourceAction> {
    // Measured out from the corner the user has selected, else from the last one
    // drawn — which is the corner a survey letter's next line is measured from.
    val selected = state.selectedCornerNumber
    val bearingFrom: Int? = when {
        selected != null -> selected - 1
        state.draftBoundary.isNotEmpty() -> state.draftBoundary.lastIndex
        else -> null
    }

    return listOf(
        MarkSourceAction(
            icon = Icons.Outlined.GpsFixed,
            label = if (state.averagingFix) R.string.corner_picker_averaging
            else R.string.corner_picker_from_fix,
            enabled = !state.averagingFix,
            busy = state.averagingFix,
            onClick = onFromFix
        ),
        MarkSourceAction(
            icon = Icons.Outlined.EditLocationAlt,
            label = R.string.corner_picker_by_numbers,
            enabled = true,
            busy = false,
            onClick = onByNumbers
        ),
        MarkSourceAction(
            icon = Icons.Outlined.Explore,
            label = R.string.corner_picker_by_bearing,
            // A bearing is measured from somewhere. With nothing drawn yet there is
            // no somewhere, and the dialog would have nothing to show its result
            // against.
            enabled = bearingFrom != null,
            busy = false,
            onClick = { bearingFrom?.let(onByBearing) }
        ),
        MarkSourceAction(
            icon = Icons.Outlined.ContentCopy,
            label = R.string.boundary_from_neighbour,
            enabled = true,
            busy = false,
            onClick = onFromNeighbour
        )
    )
}

/** [actions] laid out [columns] to a row, each icon carrying its own label. */
@Composable
private fun MarkSourceButtons(
    actions: List<MarkSourceAction>,
    columns: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        actions.chunked(columns).forEach { row ->
            Row {
                row.forEach { action ->
                    val label = stringResource(action.label)
                    IconButton(onClick = action.onClick, enabled = action.enabled) {
                        if (action.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(20.dp)
                                    .semantics { contentDescription = label },
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(action.icon, contentDescription = label)
                        }
                    }
                }
            }
        }
    }
}

/**
 * What is on screen while no mark hangs: what has been drawn so far, the four
 * alternative sources, and the one button that starts a corner.
 *
 * Undo, clear and delete appear only when there is something to undo, clear or
 * delete. A row of permanently greyed-out buttons was a row of map covered up for
 * nothing.
 */
@Composable
private fun IdleControls(
    state: LandEditUiState,
    sources: List<MarkSourceAction>,
    onPlaceMark: () -> Unit,
    onDeleteSelected: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit
) {
    val corners = state.draftBoundary.size
    val busy = state.isCapturingCorner

    PickerCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                CornerReadout(state = state, corners = corners)
            }

            state.selectedCornerNumber?.let { number ->
                IconButton(onClick = onDeleteSelected, enabled = !busy) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(
                            R.string.corner_picker_delete_selected,
                            number
                        )
                    )
                }
            }
            if (corners > 0) {
                IconButton(onClick = onUndo, enabled = !busy) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.boundary_undo)
                    )
                }
                IconButton(onClick = onClear, enabled = !busy) {
                    Icon(
                        Icons.Outlined.DeleteSweep,
                        contentDescription = stringResource(R.string.boundary_clear)
                    )
                }
            }
        }

        MarkSourceButtons(actions = sources, columns = 4)

        Button(
            onClick = onPlaceMark,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.corner_picker_place_mark))
        }

        // Only when there is something to accept — including a boundary the user
        // has deliberately cleared, which is a change worth being able to keep.
        if (corners > 0 || state.boundary.isNotEmpty()) {
            FilledTonalButton(
                onClick = onDone,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.corner_picker_done))
            }
        }
    }
}

/**
 * The confirm bar: what the mark reads, what using it would do, and the four ways
 * to move it before deciding.
 *
 * The placement line is the point of the whole two-stage business. "Insert between
 * 2 and 3" said *before* the button is pressed is a fact the user can act on;
 * the same sentence as a snackbar afterwards is an explanation of something that
 * has already happened to their record.
 */
@Composable
private fun MarkControls(
    state: LandEditUiState,
    mark: PendingMark,
    sources: List<MarkSourceAction>,
    onNudgeMark: (Double) -> Unit,
    onMarkStep: (Double) -> Unit,
    onUseMark: () -> Unit,
    onDiscardMark: () -> Unit
) {
    val placement = state.markPlacement
    val coordinates =
        if (state.dms) GeoUtils.formatDMS(mark.point.latitude, mark.point.longitude)
        else GeoUtils.formatDecimal(mark.point.latitude, mark.point.longitude)
    val spoken = stringResource(R.string.corner_picker_mark_a11y, coordinates)

    PickerCard {
        // Announced on every change rather than only when the panel appears: with
        // TalkBack on, the arrows are the whole positioning mechanism and a silent
        // 0.1 m step is indistinguishable from a button that did nothing.
        Text(
            coordinates,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = spoken
            }
        )

        // Only while the mark is still what the receiver reported. A fix nudged
        // 40 cm north has no business quoting that reading's accuracy, and
        // PendingMark demotes it to a map mark for exactly that reason.
        if (mark.source == MarkSource.GPS) {
            val accuracy = mark.point.accuracyM?.let {
                stringResource(R.string.boundary_corner_accuracy, GeoUtils.formatAccuracy(it))
            }
            val samples = state.cornerSamples.takeIf { it > 0 }?.let {
                pluralStringResource(R.plurals.edit_samples, it, it)
            }
            val reading = listOfNotNull(accuracy, samples).joinToString("  ")
            if (reading.isNotEmpty()) {
                Text(
                    reading,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        PlacementLine(placement)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            NudgePad(step = state.markStepM, onNudge = onNudgeMark, onStep = onMarkStep)
            MarkSourceButtons(
                actions = sources,
                columns = 2,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(onClick = onDiscardMark, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.corner_picker_discard_mark))
            }
            // Refused rather than accepted-and-then-explained: a corner half a
            // metre from one already recorded is the same peg measured twice.
            Button(
                onClick = onUseMark,
                enabled = placement !is Placement.TooClose,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.corner_picker_use_mark))
            }
        }
    }
}

/** What using the mark would do to the ring, in one line. */
@Composable
private fun PlacementLine(placement: Placement?) {
    val refused = placement is Placement.TooClose
    val text = when (placement) {
        is Placement.Append ->
            stringResource(R.string.corner_picker_will_append, placement.number.toString())
        is Placement.Insert -> stringResource(
            R.string.corner_picker_will_insert,
            placement.at.toString(),
            (placement.at + 1).toString()
        )
        is Placement.TooClose ->
            stringResource(R.string.corner_picker_too_close, placement.nearCorner.toString())
        null -> return
    }

    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (refused) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Four arrows around the step they move by.
 *
 * The step sits in the middle of the pad and cycles on a tap — 5 m, 1 m, 0.5 m,
 * 0.1 m — rather than living in a labelled dropdown beside it. It is the only
 * figure on the pad, so it needs no name to say what it is, and it is under the
 * thumb that is already there.
 *
 * Compass directions rather than left and right: the arrows move the mark north
 * and east, which do not swap over in a right-to-left layout the way "back" and
 * "forward" do.
 */
@Composable
private fun NudgePad(step: Double, onNudge: (Double) -> Unit, onStep: (Double) -> Unit) {
    val steps = PendingMark.STEPS_M
    // -1 from indexOf wraps to the head of the list, which is where an unknown
    // step should land anyway.
    val next = steps[(steps.indexOf(step) + 1) % steps.size]
    val stepLabel = stringResource(R.string.corner_picker_step_value, formatStep(step))
    val stepSpoken = stringResource(R.string.corner_picker_nudge_step) + ": " + stepLabel

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            NudgeSpacer()
            NudgeButton(Icons.Default.North, R.string.corner_picker_nudge_north) { onNudge(0.0) }
            NudgeSpacer()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            NudgeButton(Icons.Default.West, R.string.corner_picker_nudge_west) { onNudge(270.0) }
            TextButton(
                onClick = { onStep(next) },
                modifier = Modifier
                    .size(NUDGE_CELL)
                    .semantics { contentDescription = stepSpoken },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(stepLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            NudgeButton(Icons.Default.East, R.string.corner_picker_nudge_east) { onNudge(90.0) }
        }
        Row {
            NudgeSpacer()
            NudgeButton(Icons.Default.South, R.string.corner_picker_nudge_south) { onNudge(180.0) }
            NudgeSpacer()
        }
    }
}

/** One cell of the nudge pad, sized as a full touch target. */
@Composable
private fun NudgeButton(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(NUDGE_CELL)) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

/** The pad's empty corners. */
@Composable
private fun NudgeSpacer() {
    Spacer(modifier = Modifier.size(NUDGE_CELL))
}

/** One cell of the nudge pad: a full touch target, so a 0.1 m step is not a lottery. */
private val NUDGE_CELL = 48.dp

/**
 * The card both control bars sit on.
 *
 * Shared so that the switch from "no mark" to "mark hanging" changes what is
 * written on the card and nothing about the card itself — a panel that also
 * changed shape, colour or position would read as a different screen arriving.
 */
@Composable
private fun PickerCard(content: @Composable ColumnScope.() -> Unit) {
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
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/**
 * One sentence the map is telling you, with a way to put it away.
 *
 * Dismissible rather than timed: it is shown once in the life of the install, and
 * a sentence that vanishes on its own while it is being read cannot be got back.
 */
@Composable
private fun PickerNotice(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 2.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close)
                )
            }
        }
    }
}

/**
 * Where the background comes from, what a tapped corner does, and the legal note.
 *
 * Behind an icon because it is three paragraphs that do not change: read once,
 * and after that it is the ground underneath them that matters. It stays reachable
 * for the same reason it was written — someone deciding whether these coordinates
 * will stand up in an argument needs to be able to find the answer.
 */
@Composable
private fun AboutAccuracyDialog(onlineTiles: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.corner_picker_about_accuracy)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.corner_picker_select_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                if (onlineTiles) {
                    Text(
                        stringResource(R.string.corner_picker_online_map),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    stringResource(R.string.legal_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
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

/**
 * A nudge step as a number, without a unit.
 *
 * Whole metres print whole — "5 m", not "5.0 m" — and the reader's own decimal
 * separator is used, because this is prose on a button rather than a coordinate
 * anything will parse back.
 */
private fun formatStep(metres: Double): String {
    val locale = Localization.numberLocale()
    return if (metres >= 1.0) "%.0f".format(locale, metres) else "%.1f".format(locale, metres)
}
