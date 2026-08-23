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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.res.stringArrayResource
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
import com.landpoint.app.ui.components.rememberLocationPermission
import com.landpoint.app.util.AreaFormat
import com.landpoint.app.util.BoundaryEdits
import com.landpoint.app.util.GeoPoint
import com.landpoint.app.util.GeoUtils
import com.landpoint.app.util.PolygonMath
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

    // A GPS action taken without the permission used to end at a snackbar saying
    // the permission was missing — true, and no help, since nothing on this screen
    // could ask for it. Now the request goes up, and the action the user actually
    // pressed runs the moment it is granted instead of having to be found again.
    var pendingLocationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLocationPermission(
        onGranted = {
            pendingLocationAction?.invoke()
            pendingLocationAction = null
        }
    )
    val blockedMessage = stringResource(R.string.permission_blocked_body)
    val withLocation: (() -> Unit) -> Unit = { action ->
        when {
            permission.isGranted -> action()
            // Asking again would be a button that does nothing: the system has
            // stopped putting the dialog up. Say where the switch lives instead.
            permission.isBlocked -> viewModel.showMessage(blockedMessage)
            else -> {
                pendingLocationAction = action
                permission.request()
            }
        }
    }

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

    // Skipped while the map picker is up: it draws over this screen, taking the
    // Scaffold and its snackbar host with it, and a message shown into a host
    // that is not composed waits there until the picker closes. The picker shows
    // its own.
    LaunchedEffect(state.message, state.isPickerOpen) {
        if (state.isPickerOpen) return@LaunchedEffect
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // Drawn over the form rather than pushed as its own route: the picker edits
    // the boundary this very view model holds, and there is no way in this app
    // to hand a result back across a navigation boundary.
    if (state.isPickerOpen) {
        val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()
        CornerPickerScreen(
            state = state,
            currentLocation = currentLocation,
            onTapCorner = viewModel::addDraftCornerAt,
            onMessageShown = viewModel::consumeMessage,
            onMoveCorner = viewModel::moveDraftCorner,
            onSelectCorner = viewModel::selectDraftCorner,
            onDeleteSelected = viewModel::removeSelectedDraftCorner,
            onUseGps = { withLocation(viewModel::captureDraftCornerFromGps) },
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
                    onCapture = { withLocation(viewModel::captureLocation) },
                    onLookupAddress = viewModel::lookupAddress
                )
            }

            // No title here — the section draws its own, and it is left exactly
            // as it was.
            FormSection {
                BoundarySection(
                    state = state,
                    onAddCorner = { withLocation(viewModel::addBoundaryPoint) },
                    onPickOnMap = viewModel::openCornerPicker,
                    onUndo = viewModel::undoBoundaryPoint,
                    onClear = viewModel::clearBoundary,
                    onStartWalk = { withLocation(viewModel::startWalk) },
                    onStopWalk = viewModel::stopWalk,
                    onAddManual = viewModel::addBoundaryPointManual,
                    onUpdateCorner = viewModel::updateBoundaryPoint,
                    onInsertCorner = viewModel::insertBoundaryPointManual,
                    onAddFromBearing = viewModel::insertBoundaryPointFromBearing,
                    onRemoveCorner = viewModel::removeBoundaryPoint,
                    onMoveCornerUp = viewModel::moveBoundaryPointUp,
                    onMoveCornerDown = viewModel::moveBoundaryPointDown,
                    onLoadNeighbours = viewModel::loadNeighbours,
                    onTakeFromNeighbour = viewModel::appendFromNeighbour
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
    onInsertCorner: (Int, String, String) -> Int?,
    onAddFromBearing: (Int, String, String) -> Int?,
    onRemoveCorner: (Int) -> Unit,
    onMoveCornerUp: (Int) -> Unit,
    onMoveCornerDown: (Int) -> Unit,
    onLoadNeighbours: () -> Unit,
    onTakeFromNeighbour: (String, Set<Int>) -> Unit
) {
    val areaUnit = state.areaUnit
    // While a walk records, every other boundary control edits a shape that is
    // being rewritten underneath it. Freeze them rather than race the track.
    val busy = state.isCapturingCorner || state.isWalking

    var dialogTarget by remember { mutableStateOf<CornerDialogTarget?>(null) }
    var neighbourSheet by remember { mutableStateOf(false) }

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
                dialogTarget = CornerDialogTarget.Append
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

        // The same letters that print coordinates for one corner give every other
        // side as a bearing and a length from the one before it. Needs a corner to
        // measure from, so it appears once there is one.
        if (state.boundary.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    dialogTarget = CornerDialogTarget.Bearing(state.boundary.lastIndex)
                },
                enabled = !busy
            ) {
                Icon(
                    Icons.Outlined.Explore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "  " + stringResource(R.string.boundary_add_bearing),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        // Where two parcels meet, the corners between them are the same pegs in
        // the ground. Measuring them twice puts a sliver of no-man's land or an
        // overlap between the two records, and it is the overlap that turns into
        // an argument years later.
        OutlinedButton(
            onClick = {
                onLoadNeighbours()
                neighbourSheet = true
            },
            enabled = !busy
        ) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "  " + stringResource(R.string.boundary_from_neighbour),
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
                onEdit = { index -> dialogTarget = CornerDialogTarget.Edit(index) },
                // After corner #n means position n+1 in the ring, which is also
                // the number the new corner will carry.
                onInsertAfter = { index -> dialogTarget = CornerDialogTarget.Insert(index + 1) },
                onMeasureFrom = { index -> dialogTarget = CornerDialogTarget.Bearing(index) },
                onRemove = onRemoveCorner,
                onMoveUp = onMoveCornerUp,
                onMoveDown = onMoveCornerDown
            )
        }

        // Above the figures, not below them: the point is that the area under it
        // cannot be trusted, and a warning read afterwards is a warning read too
        // late. Saving is still allowed — the shape is the owner's to describe,
        // and refusing it would strand someone mid-entry with no way out.
        state.selfCrossing?.let { (first, second) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    stringResource(R.string.boundary_self_crossing, first, second),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
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

    if (neighbourSheet) {
        NeighbourSheet(
            state = state,
            onDismiss = { neighbourSheet = false },
            onTake = onTakeFromNeighbour
        )
    }

    when (val target = dialogTarget) {
        null -> Unit

        is CornerDialogTarget.Coordinates -> CornerCoordinateDialog(
            number = when (target) {
                CornerDialogTarget.Append -> null
                is CornerDialogTarget.Edit -> target.index + 1
                is CornerDialogTarget.Insert -> target.index + 1
            },
            inserting = target is CornerDialogTarget.Insert,
            initial = when (target) {
                CornerDialogTarget.Append -> null
                is CornerDialogTarget.Edit -> state.boundary.getOrNull(target.index)
                // Half way along the side it is being slotted into: a corner
                // between two others is nearly always on the line between them,
                // so this is usually a nudge rather than a fresh reading.
                is CornerDialogTarget.Insert ->
                    BoundaryEdits.edgeMidpoint(state.boundary, target.index - 1)?.rounded()
            },
            onDismiss = { dialogTarget = null },
            onConfirm = { lat, lon ->
                val refusal = when (target) {
                    CornerDialogTarget.Append -> onAddManual(lat, lon)
                    is CornerDialogTarget.Edit -> onUpdateCorner(target.index, lat, lon)
                    is CornerDialogTarget.Insert -> onInsertCorner(target.index, lat, lon)
                }
                if (refusal == null) dialogTarget = null
                refusal
            }
        )

        is CornerDialogTarget.Bearing -> {
            val origin = state.boundary.getOrNull(target.fromIndex)
            // Clear, or a walk rewriting the ring, can take the corner away from
            // under an open dialog. The target is dropped rather than left stale,
            // or the dialog would spring back the moment the ring grew to that
            // length again.
            LaunchedEffect(origin == null) { if (origin == null) dialogTarget = null }
            if (origin != null) {
                CornerBearingDialog(
                    fromNumber = target.fromIndex + 1,
                    newNumber = target.fromIndex + 2,
                    origin = origin,
                    onDismiss = { dialogTarget = null },
                    onConfirm = { bearing, distance ->
                        val refusal = onAddFromBearing(target.fromIndex, bearing, distance)
                        if (refusal == null) dialogTarget = null
                        refusal
                    }
                )
            }
        }
    }
}

/** What a corner dialog was opened for. */
private sealed interface CornerDialogTarget {

    /** The three that are typed as a pair of coordinates, and share one dialog. */
    sealed interface Coordinates : CornerDialogTarget

    /** Onto the end of the ring. */
    data object Append : Coordinates

    /** Correcting the corner already at [index]. */
    data class Edit(val index: Int) : Coordinates

    /** A new corner landing at [index], between two that already exist. */
    data class Insert(val index: Int) : Coordinates

    /** A new corner stated as a bearing and a length from the one at [fromIndex]. */
    data class Bearing(val fromIndex: Int) : CornerDialogTarget
}

/**
 * Trims a computed midpoint to a length that reads as a coordinate.
 *
 * Averaging two coordinates leaves float noise — `-6.914543999999999` — and that
 * is what the user would be shown as the suggested corner. Seven decimals is
 * about a centimetre, far finer than any of these corners were measured to, so
 * nothing real is lost by not showing the rest.
 */
private fun GeoPoint.rounded(): GeoPoint = GeoPoint(
    latitude = "%.7f".format(java.util.Locale.US, latitude).toDouble(),
    longitude = "%.7f".format(java.util.Locale.US, longitude).toDouble()
)

/**
 * Metres to one decimal, at any length.
 *
 * [GeoUtils.formatDistance] rounds to whole metres and turns into kilometres past
 * a thousand, which is right for "how far away is this land" and wrong for a
 * side: the figure it is being checked against was printed to the centimetre, and
 * a flat "20 m" says nothing about whether the 20.35 went in correctly.
 */
private fun formatSideLength(metres: Double): String =
    "%.1f".format(java.util.Locale.getDefault(), metres)

/**
 * Three digits, the way a bearing is written on a survey letter, so 45° reads as
 * 045 and lines up under 137 in the column above it.
 *
 * Taken modulo 360 after rounding, because 359.7 rounds to a 360 that no compass
 * shows.
 */
private fun formatBearing(degrees: Double): String =
    "%03d".format(degrees.roundToInt() % 360)

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
    onInsertAfter: (Int) -> Unit,
    onMeasureFrom: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<Int?>(null) }
    val cardinals = stringArrayResource(R.array.cardinal_directions)
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
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    val coordinates = stringResource(
                        R.string.boundary_corner_number,
                        index + 1,
                        GeoUtils.formatDecimal(point.latitude, point.longitude)
                    )
                    // The accuracy of the fix it came from, where it came from
                    // one. Beside the coordinates rather than tucked out of sight,
                    // because a corner taken at ±30 m under tree cover is the
                    // reason two boundaries disagree, and nothing else on this
                    // screen would say so.
                    val accuracy = point.accuracyM?.let {
                        stringResource(
                            R.string.boundary_corner_accuracy,
                            GeoUtils.formatAccuracy(it)
                        )
                    }
                    Text(
                        if (accuracy == null) coordinates else "$coordinates  $accuracy",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    // Under the corner it leaves, not gathered into a table of
                    // its own, because that is the order the paper is written in:
                    // from this peg, this far, this way, to the next one.
                    PolygonMath.sideFrom(boundary, index)?.let { side ->
                        Text(
                            stringResource(
                                R.string.boundary_side_next,
                                side.toIndex + 1,
                                formatSideLength(side.lengthM),
                                formatBearing(side.bearingDeg),
                                cardinals[GeoUtils.cardinalIndex(side.bearingDeg)]
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
            canInsert = boundary.size >= 2,
            onDismiss = { sheetFor = null },
            onEdit = {
                sheetFor = null
                onEdit(index)
            },
            onInsertAfter = {
                sheetFor = null
                onInsertAfter(index)
            },
            onMeasureFrom = {
                sheetFor = null
                onMeasureFrom(index)
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
    canInsert: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onInsertAfter: () -> Unit,
    onMeasureFrom: () -> Unit,
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
            // Needs a side to sit in the middle of, so it appears from the second
            // corner onwards. Appending is what the button above the list is for.
            if (canInsert) {
                SheetAction(
                    icon = Icons.Outlined.AddLocationAlt,
                    label = stringResource(R.string.corner_sheet_insert, number + 1),
                    onClick = onInsertAfter
                )
            }
            SheetAction(
                icon = Icons.Outlined.Explore,
                label = stringResource(R.string.corner_sheet_bearing),
                onClick = onMeasureFrom
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
 * Corners taken from a saved land next door, in two steps: which land, then
 * which of its corners are shared.
 *
 * Two steps rather than one list because these are two different questions. The
 * first is answered by name and distance; the second by reading coordinates. One
 * flat list of every corner of every land would ask both at once and answer
 * neither.
 *
 * The corners come across exactly as they were recorded, accuracy figure and all:
 * it is the same peg, so it is the same reading, and copying it is the whole point
 * — a second measurement of the same corner is what leaves a gap between the two
 * parcels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeighbourSheet(
    state: LandEditUiState,
    onDismiss: () -> Unit,
    onTake: (String, Set<Int>) -> Unit
) {
    var chosenId by remember { mutableStateOf<String?>(null) }
    var ticked by remember { mutableStateOf(emptySet<Int>()) }
    val chosen = state.neighbours.find { it.id == chosenId }
    val imperial = state.units == SettingsRepository.Units.IMPERIAL

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                if (chosen == null) stringResource(R.string.neighbour_title)
                else stringResource(R.string.neighbour_pick_corners, chosen.name),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            if (chosen == null) {
                Text(
                    stringResource(R.string.neighbour_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                when {
                    state.isLoadingNeighbours -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }

                    state.neighbours.isEmpty() -> Text(
                        stringResource(R.string.neighbour_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    )

                    // Lazy, not a scrolling Column: someone with fifty saved
                    // parcels would otherwise have all fifty rows composed to
                    // read the four that fit on screen.
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(state.neighbours, key = { it.id }) { neighbour ->
                            NeighbourRow(
                                neighbour = neighbour,
                                imperial = imperial,
                                onClick = {
                                    chosenId = neighbour.id
                                    ticked = emptySet()
                                }
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { ticked = chosen.corners.indices.toSet() }) {
                        Text(stringResource(R.string.neighbour_select_all))
                    }
                    TextButton(
                        onClick = { ticked = emptySet() },
                        enabled = ticked.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.neighbour_clear_selection))
                    }
                }

                // Same reason, more sharply: the neighbour may be a walked
                // track of several hundred readings.
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    itemsIndexed(chosen.corners) { index, point ->
                        NeighbourCornerRow(
                            number = index + 1,
                            point = point,
                            dms = state.dms,
                            checked = index in ticked,
                            onToggle = {
                                ticked = if (index in ticked) ticked - index else ticked + index
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { chosenId = null }) {
                        Text(stringResource(R.string.action_back))
                    }
                    Box(modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            onTake(chosen.id, ticked)
                            onDismiss()
                        },
                        enabled = ticked.isNotEmpty()
                    ) {
                        Text(
                            pluralStringResource(
                                R.plurals.neighbour_add,
                                ticked.size,
                                ticked.size
                            )
                        )
                    }
                }
            }
        }
    }
}

/** One candidate land: what it is called, how far off it is, how big its ring. */
@Composable
private fun NeighbourRow(
    neighbour: NeighbourLand,
    imperial: Boolean,
    onClick: () -> Unit
) {
    val corners = pluralStringResource(
        R.plurals.boundary_corners,
        neighbour.corners.size,
        neighbour.corners.size
    )
    // Distance to its nearest corner, which is what says "next door" — a land
    // fifty metres away is a candidate and one nine kilometres away is not, and
    // the list is long enough that the figure is what makes it usable.
    val away = neighbour.distanceM?.let {
        stringResource(R.string.neighbour_distance, GeoUtils.formatDistance(it, imperial))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            neighbour.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            if (away == null) corners else "$away · $corners",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One of that land's corners, ticked or not. */
@Composable
private fun NeighbourCornerRow(
    number: Int,
    point: GeoPoint,
    dms: Boolean,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // The row carries the click, so the box itself does not need to: two
        // targets for one choice, one of them 20dp wide, is how a tick goes on the
        // wrong corner.
        Checkbox(checked = checked, onCheckedChange = null)
        val coordinates = stringResource(
            R.string.boundary_corner_number,
            number,
            if (dms) GeoUtils.formatDMS(point.latitude, point.longitude)
            else GeoUtils.formatDecimal(point.latitude, point.longitude)
        )
        val accuracy = point.accuracyM?.let {
            stringResource(R.string.boundary_corner_accuracy, GeoUtils.formatAccuracy(it))
        }
        Text(
            if (accuracy == null) coordinates else "$coordinates  $accuracy",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
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
    inserting: Boolean,
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
                when {
                    number == null -> stringResource(R.string.corner_dialog_title_add)
                    inserting -> stringResource(R.string.corner_dialog_title_insert, number)
                    else -> stringResource(R.string.corner_dialog_title_edit, number)
                }
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
 * Types the next corner the way a survey letter states it: a bearing and a
 * length from a corner already recorded.
 *
 * The coordinate it works out is shown as the figures are typed, and that is the
 * point of the dialog rather than a decoration. A bearing read off the wrong line
 * of a letter gives a corner that looks entirely reasonable on its own; the
 * mistake only becomes visible once the outline closes, by which time three more
 * sides have been measured from it.
 */
@Composable
private fun CornerBearingDialog(
    fromNumber: Int,
    newNumber: Int,
    origin: GeoPoint,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Int?
) {
    var bearing by remember { mutableStateOf("") }
    var distance by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }

    val landing = BoundaryEdits.parseBearing(bearing)?.let { azimuth ->
        BoundaryEdits.parseDistance(distance)?.let { metres ->
            GeoUtils.destination(origin.latitude, origin.longitude, azimuth, metres)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.corner_dialog_title_bearing, newNumber, fromNumber))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = bearing,
                    onValueChange = { bearing = it; error = null },
                    label = { Text(stringResource(R.string.corner_dialog_bearing)) },
                    singleLine = true,
                    // Text rather than Decimal: the degree, minute and second
                    // marks a letter prints are not on a numeric keypad, and this
                    // field accepts them.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = distance,
                    onValueChange = { distance = it; error = null },
                    label = { Text(stringResource(R.string.corner_dialog_distance)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.corner_dialog_bearing_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                landing?.let { point ->
                    Text(
                        stringResource(
                            R.string.corner_dialog_bearing_result,
                            GeoUtils.formatDecimal(point.latitude, point.longitude)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
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
            TextButton(onClick = { error = onConfirm(bearing, distance) }) {
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
