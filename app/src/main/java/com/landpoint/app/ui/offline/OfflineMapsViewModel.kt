package com.landpoint.app.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landpoint.app.R
import com.landpoint.app.data.LandRepository
import com.landpoint.app.location.LocationProvider
import com.landpoint.app.map.GeoBounds
import com.landpoint.app.map.MapProvider
import com.landpoint.app.map.offline.ActiveDownload
import com.landpoint.app.map.offline.BudgetVerdict
import com.landpoint.app.map.offline.OfflineDownloadCoordinator
import com.landpoint.app.map.offline.Quote
import com.landpoint.app.map.offline.SavedRegion
import com.landpoint.app.map.offline.StartResult
import com.landpoint.app.ui.container
import com.landpoint.app.util.AppStrings
import com.landpoint.app.util.GeoPoint
import com.landpoint.app.util.formatBytes
import com.landpoint.app.util.formatCount
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How long the frame has to hold still before its cost is worked out. */
private const val QUOTE_SETTLE_MS = 180L

data class OfflineMapsUiState(
    val saved: List<SavedRegion> = emptyList(),
    /** The region downloading right now, carrying its live progress. */
    val active: ActiveDownload? = null,
    /** The cost of what is inside the frame, or null before the first frame settles. */
    val quote: Quote? = null,
    /**
     * Why the download button is off, in words, or null when it is on.
     *
     * Text rather than the verdict itself because two of the six cases need numbers
     * formatted into them, and both the button and the refusal that comes back from
     * an attempted start have to say the same thing.
     */
    val refusal: String? = null,
    val busy: Boolean = false,
    val message: String? = null
)

/**
 * The offline-maps screen's state.
 *
 * Thin on purpose: [OfflineDownloadCoordinator] already owns the region list, the
 * queue and the limits, and it lives in the application container so a download
 * carries on while the user is elsewhere in the app. What is added here is the part
 * that is genuinely about this screen — costing the rectangle the user is dragging,
 * and turning a refusal into a sentence.
 */
class OfflineMapsViewModel(
    private val downloads: OfflineDownloadCoordinator,
    private val strings: AppStrings,
    repository: LandRepository,
    private val locationProvider: LocationProvider
) : ViewModel() {

    private val quoted = MutableStateFlow<Quote?>(null)
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val here = MutableStateFlow<GeoPoint?>(null)

    /**
     * The pending cost calculation.
     *
     * Held so it can be cancelled: the frame is re-measured on every camera frame
     * while the map is being panned, and costing each one would work out the same
     * answer dozens of times for a rectangle the user has not finished choosing.
     */
    private var quoteJob: Job? = null

    val uiState: StateFlow<OfflineMapsUiState> = combine(
        downloads.saved,
        downloads.active,
        quoted,
        busy,
        message
    ) { saved, active, quote, working, note ->
        OfflineMapsUiState(
            saved = saved,
            active = active,
            quote = quote,
            refusal = quote?.verdict?.let { explain(it) },
            busy = working,
            message = note
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OfflineMapsUiState()
    )

    /**
     * Where to open the map, so the first thing on screen is ground the user knows.
     *
     * Their own plots first, and where the phone is only if there are none. Opening at
     * the whole world instead would put a frame around an ocean and make every visit
     * start with the same long pan. Deliberately the plots' pins rather than their
     * boundaries: a download area is a district, and decoding hundreds of boundary
     * points to place a camera that will be moved anyway is work for nothing.
     */
    val anchor: StateFlow<List<GeoPoint>> = combine(
        repository.observeLands(),
        here
    ) { lands, fix ->
        lands.map { GeoPoint(it.latitude, it.longitude) }.ifEmpty { listOfNotNull(fix) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    init {
        refresh()
        askWhereWeAre()
    }

    /**
     * One fix, for the camera only.
     *
     * Nothing on this screen tracks the user, so nothing here subscribes to updates:
     * a single position is all that is needed to decide where the map opens, and it is
     * only used when there are no saved plots to open at.
     */
    private fun askWhereWeAre() {
        viewModelScope.launch {
            locationProvider.getCurrentLocation()?.let { fix ->
                here.value = GeoPoint(
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracyM = fix.accuracy?.toDouble()
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { downloads.refresh() }
    }

    /**
     * Costs the rectangle currently framed, shortly after it stops moving.
     *
     * Deliberately does not clear the previous answer first. A number that blanks and
     * returns on every pan is harder to read than one that lags by a fifth of a
     * second, and the estimate is only ever advisory — [start] costs the request
     * again, for real, at the moment it matters.
     */
    fun propose(provider: MapProvider, bounds: GeoBounds, minZoom: Int, maxZoom: Int) {
        quoteJob?.cancel()
        quoteJob = viewModelScope.launch {
            delay(QUOTE_SETTLE_MS)
            quoted.value = downloads.quote(provider, bounds, minZoom, maxZoom)
        }
    }

    fun start(
        provider: MapProvider,
        name: String,
        bounds: GeoBounds,
        minZoom: Int,
        maxZoom: Int
    ) = act {
        when (val result = downloads.start(provider, name, bounds, minZoom, maxZoom)) {
            is StartResult.Started -> strings.get(R.string.msg_offline_area_started, name)
            is StartResult.Refused -> explain(result.reason)
            StartResult.Failed -> strings.get(R.string.msg_offline_area_failed)
        }
    }

    fun pause(id: Long) = act {
        downloads.pause(id)
        strings.get(R.string.msg_offline_area_paused)
    }

    fun resume(id: Long) = act {
        downloads.resume(id)
        null
    }

    fun update(id: Long) = act {
        downloads.update(id)
        null
    }

    fun rename(id: Long, name: String) = act {
        if (downloads.rename(id, name)) {
            strings.get(R.string.msg_offline_area_renamed, name)
        } else {
            strings.get(R.string.msg_offline_area_failed)
        }
    }

    fun delete(id: Long, name: String) = act {
        downloads.delete(id)
        strings.get(R.string.msg_offline_area_deleted, name)
    }

    fun clearBrowsingCache() = act {
        downloads.clearBrowsingCache()
        strings.get(R.string.msg_offline_cache_cleared)
    }

    fun consumeMessage() {
        message.value = null
    }

    /**
     * Runs one action, reporting whatever it has to say.
     *
     * Every action here reaches MapLibre's own database and can fail on it, so the
     * failure is caught and shown rather than left to reach the default handler: a
     * rename that silently did nothing looks like a bug in the text field.
     */
    private fun act(block: suspend () -> String?) {
        viewModelScope.launch {
            busy.value = true
            message.value = runCatching { block() }.getOrElse {
                strings.get(
                    R.string.msg_generic_error,
                    it.message ?: it::class.simpleName.orEmpty()
                )
            }
            busy.value = false
        }
    }

    /** The refusal in words, or null when there is nothing to refuse. */
    private fun explain(verdict: BudgetVerdict): String? = when (verdict) {
        BudgetVerdict.Allowed -> null
        BudgetVerdict.NothingSelected -> strings.get(R.string.offline_verdict_nothing)
        is BudgetVerdict.AreaTooLarge -> strings.get(
            R.string.offline_verdict_area,
            formatCount(verdict.requested.toLong()),
            formatCount(verdict.limit.toLong())
        )
        is BudgetVerdict.TooManyTiles -> strings.get(
            R.string.offline_verdict_tiles,
            formatCount(verdict.requested),
            formatCount(verdict.limit)
        )
        is BudgetVerdict.TooManyRegions ->
            strings.get(R.string.offline_verdict_regions, verdict.limit)
        is BudgetVerdict.NotEnoughSpace -> strings.get(
            R.string.offline_verdict_space,
            formatBytes(verdict.needed),
            formatBytes(verdict.free)
        )
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = container()
                OfflineMapsViewModel(
                    c.offlineDownloads,
                    c.strings,
                    c.repository,
                    c.locationProvider
                )
            }
        }
    }
}
