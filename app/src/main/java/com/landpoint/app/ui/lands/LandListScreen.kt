package com.landpoint.app.ui.lands

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.landpoint.app.R
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.ui.components.LandCard
import com.landpoint.app.ui.components.LocationPermissionCard
import com.landpoint.app.ui.components.rememberLocationPermission
import com.landpoint.app.util.AreaFormat
import com.landpoint.app.util.ShareUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandListScreen(
    onOpenLand: (String) -> Unit,
    onNewLand: () -> Unit,
    viewModel: LandListViewModel = viewModel(factory = LandListViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLocationPermission(onGranted = { viewModel.refreshLocation() })

    var showSearch by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    val exportCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let(viewModel::exportSelectedCsv) }

    val exportPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> uri?.let(viewModel::exportSelectedPdf) }

    val exportGpx = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri -> uri?.let(viewModel::exportSelectedGpx) }

    val exportKml = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")
    ) { uri -> uri?.let(viewModel::exportSelectedKml) }

    // Opening the field without the caret in it would mean two taps to type one
    // query.
    LaunchedEffect(showSearch) {
        if (showSearch) searchFocus.requestFocus()
    }

    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        // Read once, now: by the time the snackbar is dismissed the window may
        // have closed, and offering Undo then would be a button that does nothing.
        val undoable = state.canUndoDelete
        viewModel.consumeMessage()
        val result = snackbarHost.showSnackbar(
            message = text,
            actionLabel = if (undoable) undoLabel else null
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete()
    }

    // Leaving selection mode is what the back gesture should do first, rather
    // than dropping the user out of the screen with a selection still ticked.
    BackHandler(enabled = state.isSelecting) { viewModel.clearSelection() }

    if (showDeleteDialog && state.isSelecting) {
        val count = state.selectedCount
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(pluralStringResource(R.plurals.delete_selected_dialog_title, count, count))
            },
            text = { Text(stringResource(R.string.delete_selected_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteSelected()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (state.isSelecting) {
                SelectionTopBar(
                    count = state.selectedCount,
                    allShownSelected = state.allShownSelected,
                    onSelectAll = viewModel::selectAllShown,
                    onClear = viewModel::clearSelection,
                    onExport = { showExportMenu = true },
                    onDelete = { showDeleteDialog = true }
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    // Only two actions left here — Map and Settings moved to the
                    // bottom bar, which is what made room to breathe.
                    actions = {
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) viewModel.setQuery("")
                        }) {
                            Icon(
                                if (showSearch) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = stringResource(R.string.action_search)
                            )
                        }

                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = stringResource(R.string.action_sort)
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(order.labelRes)) },
                                        leadingIcon = {
                                            RadioButton(
                                                selected = state.sortOrder == order,
                                                onClick = null
                                            )
                                        },
                                        onClick = {
                                            viewModel.setSortOrder(order)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            // Hidden during selection: the bar above owns the actions then, and
            // a "save land" button next to a delete button invites a misfire.
            if (!state.isSelecting) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (permission.isGranted) onNewLand() else permission.request()
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.list_fab_save_land)) }
                )
            }
        }
    ) { padding ->
        if (showExportMenu) {
            ExportFormatDialog(
                onDismiss = { showExportMenu = false },
                onPick = { launch ->
                    showExportMenu = false
                    launch()
                },
                onCsv = { exportCsv.launch("landpoint-selection.csv") },
                onPdf = { exportPdf.launch("landpoint-selection.pdf") },
                onGpx = { exportGpx.launch("landpoint-selection.gpx") },
                onKml = { exportKml.launch("landpoint-selection.kml") }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            AnimatedVisibility(visible = showSearch) {
                SearchField(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    onClear = { viewModel.setQuery("") },
                    focusRequester = searchFocus
                )
            }

            if (!permission.isGranted) {
                LocationPermissionCard(
                    onRequest = permission.request,
                    modifier = Modifier.padding(16.dp),
                    blocked = permission.isBlocked
                )
            }
            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                state.lands.isEmpty() -> EmptyState(
                    hasQuery = state.query.isNotBlank(),
                    modifier = Modifier.fillMaxSize()
                )

                else -> {
                    val noParcelLabel = stringResource(R.string.group_no_parcel)
                    // From the configuration rather than the default locale, so a
                    // month heading follows the language the app is showing.
                    val locale = LocalConfiguration.current.locales.get(0)
                    // Grouping walks the whole list, so it runs when the list or
                    // the order changes rather than on every recomposition.
                    val rows = remember(state.lands, state.sortOrder, locale, noParcelLabel) {
                        groupedRows(state.lands, state.sortOrder, locale, noParcelLabel)
                    }

                    LazyColumn(
                        // The bottom inset clears the FAB. Without it the last
                        // card sits under the button and cannot be scrolled out
                        // from beneath it — the whole record becomes unreachable.
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 12.dp,
                            end = 16.dp,
                            bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "summary") {
                            SummaryRow(
                                count = state.lands.size,
                                totalAreaSqm = state.totalAreaSqm,
                                areaUnit = state.areaUnit
                            )
                        }

                        itemsIndexed(
                            rows,
                            // Headings are keyed by where they sit rather than by
                            // what they say: two runs can end up reading the same,
                            // and a repeated key is what a lazy list refuses.
                            key = { index, row ->
                                when (row) {
                                    is LandRow.Header -> "header-$index"
                                    is LandRow.Item -> row.land.id
                                }
                            }
                        ) { _, row ->
                            when (row) {
                                is LandRow.Header -> GroupHeader(row)

                                is LandRow.Item -> LandCard(
                                    land = row.land,
                                    dms = state.dms,
                                    imperial = state.imperial,
                                    areaUnit = state.areaUnit,
                                    selecting = state.isSelecting,
                                    selected = row.land.id in state.selectedIds,
                                    onToggleSelect = { viewModel.toggleSelection(row.land.id) },
                                    onClick = { onOpenLand(row.land.id) },
                                    onNavigate = { ShareUtils.navigateTo(context, row.land) },
                                    // Deletes, undos and re-sorts slide instead of
                                    // snapping. Relies on the stable `key` above.
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * How much is in view, and how much land it adds up to.
 *
 * The total follows the search rather than the database: a filtered list that
 * totalled everything would be a number for a set the user cannot see.
 */
@Composable
private fun SummaryRow(
    count: Int,
    totalAreaSqm: Double,
    areaUnit: SettingsRepository.AreaUnit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            pluralStringResource(R.plurals.list_summary, count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (totalAreaSqm > 0.0) {
            Text(
                stringResource(
                    R.string.list_summary_total_area,
                    stringResource(
                        areaUnit.valueRes,
                        AreaFormat.value(totalAreaSqm, areaUnit)
                    )
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * A heading over a run of lands, naming what they share and counting them.
 *
 * Deliberately not pinned to the top of the screen while scrolling: `stickyHeader`
 * is still experimental, and a heading that follows the scroll is worth less than
 * one that cannot break. Marked as a heading for screen readers, which is what
 * lets them jump between runs instead of reading every card.
 */
@Composable
private fun GroupHeader(header: LandRow.Header) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 6.dp)
            .semantics { heading() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            header.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(
            pluralStringResource(R.plurals.list_summary, header.count, header.count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The search box, shown under the app bar while searching. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.list_search_placeholder)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.list_search_clear)
                    )
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focusRequester)
    )
}

/** Replaces the normal bar while lands are ticked, so the actions are the selection's. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    allShownSelected: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_clear_selection)
                )
            }
        },
        title = { Text(pluralStringResource(R.plurals.selection_count, count, count)) },
        actions = {
            if (!allShownSelected) {
                IconButton(onClick = onSelectAll) {
                    Icon(
                        Icons.Default.SelectAll,
                        contentDescription = stringResource(R.string.action_select_all)
                    )
                }
            }
            IconButton(onClick = onExport) {
                Icon(
                    Icons.Outlined.FileDownload,
                    contentDescription = stringResource(R.string.action_export_selected)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete_selected),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

/**
 * Which file the selection becomes. The same four formats the settings screen
 * offers for the whole database, so nothing new has to be learned here.
 */
@Composable
private fun ExportFormatDialog(
    onDismiss: () -> Unit,
    onPick: (() -> Unit) -> Unit,
    onCsv: () -> Unit,
    onPdf: () -> Unit,
    onGpx: () -> Unit,
    onKml: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_selected_dialog_title)) },
        text = {
            Column {
                listOf(
                    R.string.settings_export_csv to onCsv,
                    R.string.settings_export_pdf to onPdf,
                    R.string.settings_export_gpx to onGpx,
                    R.string.settings_export_kml to onKml
                ).forEach { (labelRes, action) ->
                    TextButton(
                        onClick = { onPick(action) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(labelRes), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun EmptyState(hasQuery: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 40.dp)
        ) {
            Icon(
                Icons.Outlined.Map,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
            Text(
                stringResource(
                    if (hasQuery) R.string.list_no_matches_title else R.string.list_empty_title
                ),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(
                    if (hasQuery) R.string.list_no_matches_body else R.string.list_empty_body
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
