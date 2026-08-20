package com.landpoint.app.ui.lands

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landpoint.app.PendingDeletes
import com.landpoint.app.R
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.data.export.ExportResult
import com.landpoint.app.data.export.ImportExportManager
import com.landpoint.app.data.export.PdfExporter
import com.landpoint.app.data.model.Land
import com.landpoint.app.location.LocationProvider
import com.landpoint.app.ui.container
import com.landpoint.app.util.AppStrings
import com.landpoint.app.util.GeoUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortOrder(@StringRes val labelRes: Int) {
    DATE_DESC(R.string.sort_date_desc),
    DATE_ASC(R.string.sort_date_asc),
    NAME_ASC(R.string.sort_name_asc),
    NAME_DESC(R.string.sort_name_desc),
    DISTANCE(R.string.sort_distance),
    AREA_DESC(R.string.sort_area_desc),
    AREA_ASC(R.string.sort_area_asc)
}

/**
 * The four interaction flows, bundled so [LandListViewModel.uiState] stays
 * within `combine`'s five-flow limit.
 */
private data class Interaction(
    val selectedIds: Set<String>,
    val isBusy: Boolean,
    val message: String?,
    val pendingDelete: Set<String>
)

/**
 * How a chosen order arranges the list.
 *
 * A file-level function rather than a method: it reads nothing but the lands
 * handed to it, and the area rules below are the easiest thing here to get
 * quietly wrong, so they are worth testing without standing a ViewModel up.
 */
internal fun SortOrder.comparator(): Comparator<Land> = when (this) {
    SortOrder.DATE_DESC -> compareByDescending { it.createdAt }
    SortOrder.DATE_ASC -> compareBy { it.createdAt }
    SortOrder.NAME_ASC -> compareBy { it.name.lowercase() }
    SortOrder.NAME_DESC -> compareByDescending { it.name.lowercase() }
    // Lands with no distance (location unknown) sort last rather than first.
    SortOrder.DISTANCE -> compareBy { it.distanceMeters ?: Double.MAX_VALUE }
    // Single-point lands have no area at all. They sort last in *both*
    // directions — an unmeasured land is not "the smallest one", and putting
    // it at the top of an ascending list would bury every real measurement.
    // Hence the sentinel differs per direction rather than being shared.
    SortOrder.AREA_DESC -> compareByDescending { it.areaSqm ?: Double.NEGATIVE_INFINITY }
    SortOrder.AREA_ASC -> compareBy { it.areaSqm ?: Double.POSITIVE_INFINITY }
}

data class LandListUiState(
    val lands: List<Land> = emptyList(),
    val query: String = "",
    val sortOrder: SortOrder = SortOrder.DATE_DESC,
    val hasCurrentLocation: Boolean = false,
    val isLoading: Boolean = true,
    val imperial: Boolean = false,
    val dms: Boolean = false,
    val areaUnit: SettingsRepository.AreaUnit = SettingsRepository.AreaUnit.SQM,
    /**
     * Ids the user ticked for a bulk action. Ids rather than lands, so a record
     * edited or deleted underneath the selection cannot leave a stale copy here.
     */
    val selectedIds: Set<String> = emptySet(),
    val isBusy: Boolean = false,
    val message: String? = null,
    /**
     * True while a delete is waiting out its undo window, so the screen knows to
     * offer Undo rather than a plain "deleted" message.
     */
    val canUndoDelete: Boolean = false
) {
    val isEmpty: Boolean get() = !isLoading && lands.isEmpty()

    val isSelecting: Boolean get() = selectedIds.isNotEmpty()

    /** Selected lands in the order shown, so an export reads like the list. */
    val selectedLands: List<Land> get() = lands.filter { it.id in selectedIds }

    val selectedCount: Int get() = selectedLands.size

    val allShownSelected: Boolean
        get() = lands.isNotEmpty() && lands.all { it.id in selectedIds }

    /** Lands with a measured boundary — the only ones that contribute an area. */
    val polygonCount: Int get() = lands.count { it.isPolygon && it.areaSqm != null }

    /**
     * Total area of everything shown, so a search result totals what it lists
     * rather than the whole database.
     */
    val totalAreaSqm: Double get() = lands.sumOf { it.areaSqm ?: 0.0 }
}

class LandListViewModel(
    private val repository: LandRepository,
    private val locationProvider: LocationProvider,
    private val importExport: ImportExportManager,
    private val pdfExporter: PdfExporter,
    private val strings: AppStrings,
    private val settings: SettingsRepository,
    private val pendingDeletes: PendingDeletes
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val sortOrder = MutableStateFlow(SortOrder.DATE_DESC)
    private val currentLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<LandListUiState> = combine(
        repository.observeLands(),
        combine(query, sortOrder, currentLocation) { q, sort, location ->
            Triple(q, sort, location)
        },
        combine(settings.units, settings.coordFormat, settings.areaUnit) { units, format, area ->
            Triple(units, format, area)
        },
        combine(
            selectedIds, busy, message, pendingDeletes.ids
        ) { ids, isBusy, msg, pending -> Interaction(ids, isBusy, msg, pending) }
    ) { lands, filters, prefs, interaction ->
        val (q, sort, location) = filters
        val (units, format, areaUnit) = prefs
        val (chosen, isBusy, msg, pending) = interaction
        // A land waiting out its undo window is still in the database. Hiding it
        // here is what makes Undo possible without touching a single photo file.
        val visible = if (pending.isEmpty()) lands else lands.filterNot { it.id in pending }
        val withDistance = if (location != null) {
            visible.map { land ->
                land.copy(
                    distanceMeters = GeoUtils.distance(
                        location.first, location.second, land.latitude, land.longitude
                    )
                )
            }
        } else visible

        val filtered = if (q.isBlank()) withDistance else withDistance.filter { it.matches(q) }

        LandListUiState(
            lands = filtered.sortedWith(sort.comparator()),
            query = q,
            sortOrder = sort,
            hasCurrentLocation = location != null,
            isLoading = false,
            imperial = units == SettingsRepository.Units.IMPERIAL,
            dms = format == SettingsRepository.CoordFormat.DMS,
            areaUnit = areaUnit,
            // A land deleted or filtered out of view must not stay selected and
            // silently reappear in an export.
            selectedIds = chosen intersect filtered.map { it.id }.toSet(),
            isBusy = isBusy,
            message = msg,
            canUndoDelete = pending.isNotEmpty()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LandListUiState()
    )

    init {
        refreshLocation()
        // Announce every pending delete from one place, whichever screen started
        // it. A land deleted from its own detail screen closes that screen, so
        // this list is where the user is standing when the Undo offer has to
        // appear — and it would have nowhere else to appear.
        viewModelScope.launch {
            pendingDeletes.ids.collect { pending ->
                if (pending.isNotEmpty()) {
                    message.value = strings.plural(R.plurals.msg_deleted, pending.size)
                }
            }
        }
    }

    private fun Land.matches(q: String): Boolean {
        val needle = q.trim().lowercase()
        return name.lowercase().contains(needle) ||
                description.lowercase().contains(needle) ||
                notes.lowercase().contains(needle) ||
                address?.lowercase()?.contains(needle) == true ||
                parcelNumber?.lowercase()?.contains(needle) == true
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setSortOrder(order: SortOrder) {
        sortOrder.value = order
        if (order == SortOrder.DISTANCE) refreshLocation()
    }

    fun refreshLocation() {
        viewModelScope.launch {
            locationProvider.getCurrentLocation()?.let {
                currentLocation.value = it.latitude to it.longitude
            }
        }
    }

    fun delete(land: Land) {
        pendingDeletes.schedule(setOf(land.id))
    }

    fun toggleSelection(id: String) {
        selectedIds.update { if (id in it) it - id else it + id }
    }

    /** Ticks everything currently shown — the search result, not the whole database. */
    fun selectAllShown() {
        selectedIds.value = uiState.value.lands.map { it.id }.toSet()
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    fun exportSelectedCsv(uri: Uri) = exportSelected(uri, R.string.msg_csv_failed) {
        importExport.exportCsv(uri, it)
    }

    fun exportSelectedGpx(uri: Uri) = exportSelected(uri, R.string.msg_geo_export_failed) {
        importExport.exportGpx(uri, it)
    }

    fun exportSelectedKml(uri: Uri) = exportSelected(uri, R.string.msg_geo_export_failed) {
        importExport.exportKml(uri, it)
    }

    fun exportSelectedPdf(uri: Uri) = exportSelected(uri, R.string.msg_pdf_failed) { lands ->
        pdfExporter.exportReport(
            uri,
            lands,
            uiState.value.dms,
            uiState.value.areaUnit
        )
    }

    /**
     * Runs [export] over the current selection and reports the outcome.
     *
     * The selection is read once, up front: an export of "what was ticked when
     * the user tapped" is the honest thing to write to a file the user names,
     * even if the list changes while the write is in flight.
     */
    private fun exportSelected(
        uri: Uri,
        @StringRes failureRes: Int,
        export: suspend (List<Land>) -> ExportResult
    ) {
        val lands = uiState.value.selectedLands
        if (lands.isEmpty()) return
        viewModelScope.launch {
            busy.value = true
            message.value = try {
                val result = export(lands)
                if (result.isFailure) strings.get(failureRes, result.error ?: "")
                else strings.plural(R.plurals.msg_exported, result.count)
            } catch (e: Exception) {
                // A chosen file can be on a card pulled mid-write, or a provider
                // that dies; the list screen must not go down with it.
                strings.get(R.string.msg_generic_error, e.message ?: e.javaClass.simpleName)
            } finally {
                busy.value = false
            }
            clearSelection()
        }
    }

    /**
     * Hides every selected land and deletes it once the undo window closes.
     *
     * Nothing is removed from disk yet — see [PendingDeletes] for why the delete
     * has to wait rather than be reversed.
     */
    fun deleteSelected() {
        val ids = uiState.value.selectedIds
        if (ids.isEmpty()) return
        // The message comes from the collector in init, so a delete announces
        // itself the same way wherever it was started from.
        pendingDeletes.schedule(ids)
        clearSelection()
    }

    fun undoDelete() {
        pendingDeletes.undo()
        message.value = strings.get(R.string.msg_delete_undone)
    }

    fun consumeMessage() {
        message.value = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = container()
                LandListViewModel(
                    c.repository,
                    c.locationProvider,
                    c.importExport,
                    c.pdfExporter,
                    c.strings,
                    c.settings,
                    c.pendingDeletes
                )
            }
        }
    }
}
