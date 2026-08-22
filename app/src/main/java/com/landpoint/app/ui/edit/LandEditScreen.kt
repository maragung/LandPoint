package com.landpoint.app.ui.edit

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.landpoint.app.R
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.location.FixQuality
import com.landpoint.app.util.AreaFormat
import com.landpoint.app.util.GeoPoint
import com.landpoint.app.util.GeoUtils
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandEditScreen(
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    viewModel: LandEditViewModel = viewModel(factory = LandEditViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val noCameraMessage = stringResource(R.string.msg_no_camera_app)
    val noPickerMessage = stringResource(R.string.msg_photo_import_failed)

    var pendingCapture by remember { mutableStateOf<File?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCapture
        pendingCapture = null
        if (success && file != null) viewModel.onPhotoCaptured(file)
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.onPhotoPicked(it) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // Drawn over the form rather than pushed as its own route: the picker edits
    // the boundary this very view model holds, and there is no way in this app
    // to hand a result back across a navigation boundary.
    if (state.isPickerOpen) {
        val vectorSource by viewModel.vectorSource.collectAsStateWithLifecycle()
        val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()
        CornerPickerScreen(
            state = state,
            vectorSource = vectorSource,
            currentLocation = currentLocation,
            onTapCorner = viewModel::addDraftCornerAt,
            onMoveCorner = viewModel::moveDraftCorner,
            onSelectCorner = viewModel::selectDraftCorner,
            onDeleteSelected = viewModel::removeSelectedDraftCorner,
            onUseGps = viewModel::captureDraftCornerFromGps,
            onUndo = viewModel::undoDraftCorner,
            onClear = viewModel::clearDraftCorners,
            onDone = viewModel::commitCornerPicker,
            onCancel = viewModel::closeCornerPicker
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isEdit) R.string.edit_title_edit else R.string.edit_title_new
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_cancel)
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.save(onDone) },
                        enabled = !state.isSaving,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FormSection(stringResource(R.string.edit_section_details)) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.field_name)) },
                    placeholder = { Text(stringResource(R.string.field_name_hint)) },
                    isError = state.nameError != null,
                    supportingText = state.nameError?.let { res -> { Text(stringResource(res)) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.parcelNumber,
                    onValueChange = viewModel::setParcelNumber,
                    label = { Text(stringResource(R.string.field_parcel_number)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Coordinates stay high in the form on purpose: the capture button is
            // what the user reaches for while standing on the plot, and the two
            // long free-text fields would push it off the screen.
            FormSection(stringResource(R.string.label_coordinates)) {
                CoordinateSection(
                    state = state,
                    onLatitudeChange = viewModel::setLatitude,
                    onLongitudeChange = viewModel::setLongitude,
                    onCapture = viewModel::captureLocation,
                    onLookupAddress = viewModel::lookupAddress
                )
            }

            // No title here — the section draws its own, and it is left exactly
            // as it was.
            FormSection {
                BoundarySection(
                    state = state,
                    onAddCorner = viewModel::addBoundaryPoint,
                    onPickOnMap = viewModel::openCornerPicker,
                    onUndo = viewModel::undoBoundaryPoint,
                    onClear = viewModel::clearBoundary,
                    onStartWalk = viewModel::startWalk,
                    onStopWalk = viewModel::stopWalk,
                    onAddManual = viewModel::addBoundaryPointManual,
                    onUpdateCorner = viewModel::updateBoundaryPoint,
                    onRemoveCorner = viewModel::removeBoundaryPoint,
                    onMoveCornerUp = viewModel::moveBoundaryPointUp,
                    onMoveCornerDown = viewModel::moveBoundaryPointDown
                )
            }

            FormSection {
                PhotoSection(
                    photos = state.photos,
                    onTakePhoto = {
                        // The manifest declares the camera optional, so a device
                        // may genuinely have no app to answer this intent. Letting
                        // the resulting ActivityNotFoundException escape a click
                        // handler would crash the screen.
                        val (file, uri) = viewModel.newCameraTarget()
                        val launched = runCatching {
                            cameraLauncher.launch(uri)
                        }.isSuccess
                        pendingCapture = if (launched) file else null
                        if (!launched) viewModel.showMessage(noCameraMessage)
                    },
                    onPickPhoto = {
                        runCatching {
                            pickerLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }.onFailure { viewModel.showMessage(noPickerMessage) }
                    },
                    onRemove = viewModel::removePhoto,
                    onCaption = viewModel::setPhotoCaption
                )
            }

            FormSection(stringResource(R.string.edit_section_notes)) {
                OutlinedTextField(
                    value = state.description,
                    onValueChange = viewModel::setDescription,
                    label = { Text(stringResource(R.string.field_description)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.notes,
                    onValueChange = viewModel::setNotes,
                    label = { Text(stringResource(R.string.field_notes)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * One group of fields on a toned card.
 *
 * The editor is a long form, and a single column of bare fields gives the eye
 * nothing to land on. Grouping is the only thing this adds — no field changes
 * behaviour by being inside one.
 *
 * [title] is null for sections that already draw their own heading.
 */
@Composable
private fun FormSection(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall)
            }
            content()
        }
    }
}

@Composable
private fun CoordinateSection(
    state: LandEditUiState,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onCapture: () -> Unit,
    onLookupAddress: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = state.latitude,
                onValueChange = onLatitudeChange,
                label = { Text(stringResource(R.string.field_latitude)) },
                singleLine = true,
                isError = state.coordinateError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = state.longitude,
                onValueChange = onLongitudeChange,
                label = { Text(stringResource(R.string.field_longitude)) },
                singleLine = true,
                isError = state.coordinateError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }

        state.coordinateError?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(onClick = onCapture, enabled = !state.isCapturing) {
                if (state.isCapturing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(
                    "  " + stringResource(
                        if (state.isCapturing) R.string.edit_getting_fix
                        else R.string.edit_use_my_location
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (state.hasCoordinates) {
                OutlinedButton(onClick = onLookupAddress, enabled = !state.isLoadingAddress) {
                    Text(
                        stringResource(
                            if (state.isLoadingAddress) R.string.edit_looking_up
                            else R.string.edit_find_address
                        )
                    )
                }
            }
        }

        if (state.accuracy != null || state.altitude != null) {
            val accuracyText = state.accuracy?.let {
                stringResource(R.string.edit_accuracy, it.roundToInt())
            }
            val altitudeText = state.altitude?.let {
                stringResource(R.string.edit_altitude, it.roundToInt())
            }
            val samplesText = state.sampleCount
                .takeIf { it > 0 }
                ?.let { pluralStringResource(R.plurals.edit_samples, it, it) }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    listOfNotNull(accuracyText, altitudeText, samplesText).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.fixQuality?.let { QualityChip(it) }
            }
        }

        if (state.isCapturing) {
            Text(
                stringResource(R.string.edit_averaging),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (state.rejectedSamples > 0) {
            Text(
                pluralStringResource(
                    R.plurals.edit_rejected,
                    state.rejectedSamples,
                    state.rejectedSamples
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (state.networkOnly) {
            Text(
                stringResource(R.string.edit_network_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        state.address?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Colour-coded so the fix quality reads at a glance, with the word as backup. */
@Composable
private fun QualityChip(quality: FixQuality) {
    val (labelRes, colour) = when (quality) {
        FixQuality.EXCELLENT -> R.string.edit_quality_excellent to Color(0xFF2E7D32)
        FixQuality.GOOD -> R.string.edit_quality_good to Color(0xFF558B2F)
        FixQuality.FAIR -> R.string.edit_quality_fair to Color(0xFFEF6C00)
        FixQuality.POOR -> R.string.edit_quality_poor to MaterialTheme.colorScheme.error
    }
    Text(
        stringResource(labelRes),
        style = MaterialTheme.typography.labelSmall,
        color = colour,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colour.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * Corner-by-corner boundary capture, with walking the boundary as the fallback
 * where the corners cannot be stood on. Area only appears once the shape
 * actually closes — showing a number for two corners would be meaningless.
 */
@Composable
private fun BoundarySection(
    state: LandEditUiState,
    onAddCorner: () -> Unit,
    onPickOnMap: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onStartWalk: () -> Unit,
    onStopWalk: () -> Unit,
    onAddManual: (String, String) -> Int?,
    onUpdateCorner: (Int, String, String) -> Int?,
    onRemoveCorner: (Int) -> Unit,
    onMoveCornerUp: (Int) -> Unit,
    onMoveCornerDown: (Int) -> Unit
) {
    val areaUnit = state.areaUnit
    // While a walk records, every other boundary control edits a shape that is
    // being rewritten underneath it. Freeze them rather than race the track.
    val busy = state.isCapturingCorner || state.isWalking

    // Null index means the dialog is adding rather than correcting.
    var dialogOpen by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.boundary_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.boundary_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(onClick = onAddCorner, enabled = !busy) {
                if (state.isCapturingCorner) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.AddLocationAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    "  " + stringResource(
                        if (state.isCapturingCorner) R.string.boundary_capturing
                        else R.string.boundary_add
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (state.boundary.isNotEmpty()) {
                OutlinedButton(onClick = onUndo, enabled = !busy) {
                    Text(stringResource(R.string.boundary_undo))
                }
                OutlinedButton(onClick = onClear, enabled = !busy) {
                    Text(stringResource(R.string.boundary_clear))
                }
            }
        }

        // For the corners that cannot be stood on — across a ditch, inside
        // someone else's crop — and for seeing the shape while drawing it.
        OutlinedButton(onClick = onPickOnMap, enabled = !busy) {
            Icon(
                Icons.Outlined.Map,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "  " + stringResource(R.string.boundary_pick_on_map),
                style = MaterialTheme.typography.labelLarge
            )
        }

        // The path that needs no GPS and no map at all: corners copied off a
        // survey letter or a certificate, which is how most of these boundaries
        // are already written down.
        OutlinedButton(
            onClick = {
                editIndex = null
                dialogOpen = true
            },
            enabled = !busy
        ) {
            Icon(
                Icons.Outlined.EditLocationAlt,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "  " + stringResource(R.string.boundary_add_manual),
                style = MaterialTheme.typography.labelLarge
            )
        }

        WalkControls(state = state, onStartWalk = onStartWalk, onStopWalk = onStopWalk)

        if (state.boundary.isNotEmpty()) {
            Text(
                pluralStringResource(
                    R.plurals.boundary_corners,
                    state.boundary.size,
                    state.boundary.size
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            CornerList(
                boundary = state.boundary,
                enabled = !busy,
                onEdit = { index ->
                    editIndex = index
                    dialogOpen = true
                },
                onRemove = onRemoveCorner,
                onMoveUp = onMoveCornerUp,
                onMoveDown = onMoveCornerDown
            )
        }

        val area = state.areaSqm
        if (area != null) {
            Text(
                stringResource(R.string.boundary_area) + ": " +
                    stringResource(areaUnit.valueRes, AreaFormat.value(area, areaUnit)),
                style = MaterialTheme.typography.bodyLarge
            )
            state.perimeterM?.let {
                Text(
                    stringResource(R.string.boundary_perimeter, it.roundToInt().toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // What gets saved as the land's pin. Shown rather than left implicit,
            // because it is no longer the coordinates in the field above.
            state.pinPoint?.let { pin ->
                Text(
                    stringResource(
                        R.string.boundary_pin_centroid,
                        GeoUtils.formatDecimal(pin.latitude, pin.longitude)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (state.boundary.isNotEmpty()) {
            val missing = 3 - state.boundary.size
            Text(
                pluralStringResource(R.plurals.boundary_need_more, missing, missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (dialogOpen) {
        val index = editIndex
        CornerCoordinateDialog(
            number = index?.plus(1),
            initial = index?.let { state.boundary.getOrNull(it) },
            onDismiss = { dialogOpen = false },
            onConfirm = { lat, lon ->
                val refusal =
                    if (index == null) onAddManual(lat, lon)
                    else onUpdateCorner(index, lat, lon)
                if (refusal == null) dialogOpen = false
                refusal
            }
        )
    }
}

/**
 * The corners in the order they are joined up, each one editable.
 *
 * The numbers are not decoration: the order *is* the outline, so a boundary that
 * draws a bow tie is fixed by moving one corner up or down, and there is no way
 * to see that without seeing the sequence. Undo only ever reaches the last
 * corner placed, which is no help when the wrong one is in the middle.
 *
 * A walked boundary can hold hundreds of points, so the list stays short until
 * asked — and reordering a walked track is not a thing anyone needs to do.
 */
@Composable
private fun CornerList(
    boundary: List<GeoPoint>,
    enabled: Boolean,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<Int?>(null) }
    val collapsedCount = 10
    val shown = if (expanded) boundary.size else minOf(boundary.size, collapsedCount)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (boundary.size >= 3) {
            Text(
                stringResource(R.string.boundary_order_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (boundary.isNotEmpty()) {
            Text(
                stringResource(R.string.boundary_corner_tap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        for (index in 0 until shown) {
            val point = boundary[index]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { sheetFor = index }
            ) {
                Text(
                    stringResource(
                        R.string.boundary_corner_number,
                        index + 1,
                        GeoUtils.formatDecimal(point.latitude, point.longitude)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                )
                CornerAction(
                    icon = Icons.Default.ArrowUpward,
                    description = stringResource(R.string.boundary_corner_up, index + 1),
                    enabled = enabled && index > 0,
                    onClick = { onMoveUp(index) }
                )
                CornerAction(
                    icon = Icons.Default.ArrowDownward,
                    description = stringResource(R.string.boundary_corner_down, index + 1),
                    enabled = enabled && index < boundary.size - 1,
                    onClick = { onMoveDown(index) }
                )
            }
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

    // Nothing is rendered once the corner is gone from under an open sheet —
    // Clear, or a walk rewriting the ring, can both do that.
    sheetFor?.takeIf { it in boundary.indices }?.let { index ->
        CornerActionsSheet(
            number = index + 1,
            onDismiss = { sheetFor = null },
            onEdit = {
                sheetFor = null
                onEdit(index)
            },
            onRemove = {
                sheetFor = null
                onRemove(index)
            }
        )
    }
}

/**
 * Reordering is frequent and shallow, so it stays on the row; editing and
 * deleting are rarer and consequential, so they moved into [CornerActionsSheet].
 *
 * The previous version fitted four of these beside a coordinate by shrinking
 * them to 36dp — under Material's 48dp minimum, on a row where the neighbouring
 * button deletes a surveyed corner.
 */
@Composable
private fun CornerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

/** What tapping a corner offers: the actions that do not belong on a 48dp row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CornerActionsSheet(
    number: Int,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Text(
                stringResource(R.string.corner_sheet_title, number),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            SheetAction(
                icon = Icons.Outlined.Edit,
                label = stringResource(R.string.corner_sheet_edit),
                onClick = onEdit
            )
            SheetAction(
                icon = Icons.Outlined.Delete,
                label = stringResource(R.string.corner_sheet_delete),
                destructive = true,
                onClick = onRemove
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    val colour =
        if (destructive) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = colour)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colour)
    }
}

/**
 * Types one corner in, or corrects one already recorded.
 *
 * [onConfirm] returns null once the corner is accepted, or the string resource
 * explaining the refusal — which is shown here rather than as a snackbar this
 * dialog would sit on top of, with the typed text left where it is.
 */
@Composable
private fun CornerCoordinateDialog(
    number: Int?,
    initial: GeoPoint?,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Int?
) {
    var latitude by remember(initial) {
        mutableStateOf(initial?.latitude?.toString() ?: "")
    }
    var longitude by remember(initial) {
        mutableStateOf(initial?.longitude?.toString() ?: "")
    }
    var error by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (number == null) stringResource(R.string.corner_dialog_title_add)
                else stringResource(R.string.corner_dialog_title_edit, number)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it; error = null },
                    label = { Text(stringResource(R.string.corner_dialog_lat)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it; error = null },
                    label = { Text(stringResource(R.string.corner_dialog_lon)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.corner_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onConfirm(latitude, longitude) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * Start/stop for the walk-around measurement.
 *
 * Deliberately below the corner buttons and worded as the fallback it is: a
 * walked track is a rougher shape than corners stood on, and someone who can
 * reach the corners should not be nudged away from doing so.
 */
@Composable
private fun WalkControls(
    state: LandEditUiState,
    onStartWalk: () -> Unit,
    onStopWalk: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.isWalking) {
            Button(onClick = onStopWalk) {
                Text(stringResource(R.string.boundary_walk_stop))
            }
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            OutlinedButton(onClick = onStartWalk, enabled = !state.isCapturingCorner) {
                Icon(
                    Icons.AutoMirrored.Filled.DirectionsWalk,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "  " + stringResource(R.string.boundary_walk_start),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }

    if (state.isWalking) {
        Text(
            stringResource(
                R.string.boundary_walk_recording,
                GeoUtils.formatDistance(state.walkedM, state.units == SettingsRepository.Units.IMPERIAL)
            ),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary
        )
    } else {
        Text(
            stringResource(R.string.boundary_walk_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PhotoSection(
    photos: List<EditPhoto>,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onRemove: (EditPhoto) -> Unit,
    onCaption: (EditPhoto, String) -> Unit
) {
    // Which photo is being captioned, if any. Held by path rather than by object
    // so the dialog survives the list being rebuilt when the row is written.
    var captioning by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.photos_title), style = MaterialTheme.typography.titleSmall)

        if (photos.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos, key = { it.path }) { photo ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.width(110.dp)
                    ) {
                        Box {
                            PhotoThumbnail(
                                path = photo.path,
                                caption = photo.caption,
                                onClick = { captioning = photo.path }
                            )
                            IconButton(
                                onClick = { onRemove(photo) },
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.photo_remove),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(
                            photo.caption.ifBlank { stringResource(R.string.photo_caption) },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (photo.caption.isBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onTakePhoto) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  " + stringResource(R.string.photo_camera))
            }
            OutlinedButton(onClick = onPickPhoto) {
                Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  " + stringResource(R.string.photo_gallery))
            }
        }
    }

    // Resolved from the live list, so a photo removed while the dialog is open
    // closes it instead of writing a caption to a row that no longer exists.
    val target = captioning?.let { path -> photos.firstOrNull { it.path == path } }
    if (target != null) {
        CaptionDialog(
            initial = target.caption,
            onDismiss = { captioning = null },
            onConfirm = { text ->
                captioning = null
                onCaption(target, text)
            }
        )
    } else if (captioning != null) {
        captioning = null
    }
}

/** Asks for the one sentence that makes a photo mean something later. */
@Composable
private fun CaptionDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.photo_caption_edit)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.photo_caption)) },
                placeholder = { Text(stringResource(R.string.photo_caption_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun PhotoThumbnail(path: String, caption: String, onClick: () -> Unit) {
    val bitmap = remember(path) {
        runCatching {
            android.graphics.BitmapFactory.decodeFile(
                path,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
            )
        }.getOrNull()
    }

    Box(
        modifier = Modifier
            .size(110.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = caption.ifBlank {
                    stringResource(R.string.photo_description)
                },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
