package com.landpoint.app.map.offline

import android.content.Context
import android.os.StatFs
import com.landpoint.app.map.GeoBounds
import com.landpoint.app.map.MapProvider
import com.landpoint.app.map.TileSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place a download is decided, started, and stopped.
 *
 * Three responsibilities that have to sit together, because each is only correct in
 * the presence of the others:
 *
 * It refuses. Every request is costed by [OfflineBudget] against the area, the tile
 * count, the number of regions already saved and the space left on the volume, and a
 * request over any of those limits does not start. The tile ceiling is then also set
 * inside MapLibre, so an estimate that turns out to have been optimistic stops the
 * download rather than filling the phone.
 *
 * It serialises. One region downloads at a time and the rest wait their turn, because
 * two concurrent downloads against a donated tile server is twice the load for no
 * benefit — the phone's connection is the bottleneck either way.
 *
 * And it survives. Nothing about a download is stored here that MapLibre does not
 * already hold: the queue is re-derived from the region list, so a process killed
 * mid-download comes back with a half-finished region that can simply be resumed.
 *
 * @param scope an application-lifetime scope. Not a screen's: the queue has to keep
 *   moving to the next region while the user is somewhere else in the app.
 */
class OfflineDownloadCoordinator(
    private val context: Context,
    private val regions: OfflineRegionStore,
    private val styles: OfflineStyleWriter,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val _saved = MutableStateFlow<List<SavedRegion>>(emptyList())

    /** Every saved area, newest first. Refreshed by [refresh] and after every change. */
    val saved: StateFlow<List<SavedRegion>> = _saved.asStateFlow()

    private val _active = MutableStateFlow<ActiveDownload?>(null)

    /** The download in progress, or null when nothing is running. */
    val active: StateFlow<ActiveDownload?> = _active.asStateFlow()

    /**
     * Ids waiting for their turn, oldest request first.
     *
     * In memory only, and deliberately so. A queue persisted across a restart would
     * silently start downloading on the next launch, on whatever connection the phone
     * then had; a region left stopped is visible in the list with a resume button,
     * which keeps the decision the user's.
     */
    private val queued = ArrayDeque<Long>()

    /** Serialises starting and stopping, which both read and then write [queued]. */
    private val gate = Mutex()

    private var watcher: Job? = null

    /** Re-reads MapLibre's region list. Cheap enough for every visit to the screen. */
    suspend fun refresh() {
        _saved.value = regions.list()
    }

    /**
     * Costs a proposed download without starting anything.
     *
     * Separate from [start] because the numbers are wanted while the user is still
     * dragging the rectangle, several times a second, and because a request that will
     * be refused should say so before there is a button to press.
     */
    suspend fun quote(
        provider: MapProvider,
        bounds: GeoBounds,
        minZoom: Int,
        maxZoom: Int
    ): Quote {
        val plan = OfflineBudget.plan(
            bounds = bounds,
            minZoom = minZoom,
            maxZoom = maxZoom,
            tileMaxZoom = provider.tileMaxZoom.toInt(),
            vector = provider.tiles is TileSpec.VectorStyle
        )
        val verdict = when {
            !provider.allowsOfflineDownload -> BudgetVerdict.NothingSelected
            else -> OfflineBudget.verdict(plan, freeBytes(), _saved.value.size)
        }
        return Quote(plan, verdict)
    }

    /**
     * Creates a region and starts it, or says why it did not.
     *
     * The quote is recomputed here rather than taken from the caller. What the user saw
     * a moment ago was true a moment ago; disk space and the region count can both have
     * changed since, and this is the last point at which a refusal is still free.
     */
    suspend fun start(
        provider: MapProvider,
        name: String,
        bounds: GeoBounds,
        minZoom: Int,
        maxZoom: Int
    ): StartResult {
        val quote = quote(provider, bounds, minZoom, maxZoom)
        if (quote.verdict != BudgetVerdict.Allowed) return StartResult.Refused(quote.verdict)

        val styleUrl = styles.downloadStyleUrl(provider)
            ?: return StartResult.Refused(BudgetVerdict.NothingSelected)

        val meta = OfflineRegionMeta.of(
            name = name,
            createdAt = now(),
            bounds = bounds,
            minZoom = minZoom,
            // Stored as what will actually be fetched. A region defined to a zoom its
            // source does not publish is not wrong, but it makes MapLibre's own
            // required-resource count harder to read against the number on screen.
            maxZoom = quote.plan.effectiveMaxZoom,
            styleKey = provider.mode.key
        )

        // Set before the region exists, so the ceiling is in force for its first tile.
        regions.setTileCeiling(OfflineBudget.MAX_TILES * OfflineBudget.MAX_REGIONS)

        val id = regions.create(meta, styleUrl, pixelRatio())
            ?: return StartResult.Failed
        refresh()
        gate.withLock { enqueue(id) }
        return StartResult.Started(id)
    }

    /** Resumes a stopped region, or queues it if something else is downloading. */
    suspend fun resume(id: Long) = gate.withLock { enqueue(id) }

    /**
     * Stops a region without deleting it. What it already has stays usable.
     *
     * Pausing the active download promotes whatever was queued behind it, so a user
     * pausing one area does not stall the others.
     */
    suspend fun pause(id: Long) = gate.withLock {
        queued.remove(id)
        regions.setDownloading(id, false)
        if (_active.value?.id == id) {
            stopWatching()
            startNextLocked()
        }
        refresh()
    }

    /** Stops and removes a region, then reclaims the space it was using. */
    suspend fun delete(id: Long) {
        gate.withLock {
            queued.remove(id)
            if (_active.value?.id == id) stopWatching()
            regions.delete(id)
            startNextLocked()
        }
        refresh()
        // After the list is up to date, because compacting the database takes a while
        // and there is no reason for the user to watch a deleted area linger.
        regions.compact()
    }

    suspend fun rename(id: Long, name: String): Boolean {
        val renamed = regions.rename(id, name)
        if (renamed) refresh()
        return renamed
    }

    /** Re-checks a saved area against the server and fetches only what changed. */
    suspend fun update(id: Long) {
        gate.withLock {
            if (_active.value != null && _active.value?.id != id) {
                enqueue(id)
                return@withLock
            }
            regions.update(id)
            watch(id)
        }
        refresh()
    }

    /** Frees the tiles kept from ordinary browsing, keeping every saved area. */
    suspend fun clearBrowsingCache() {
        regions.clearBrowsingCache()
        regions.compact()
    }

    /** Space left where the offline database lives. */
    fun freeBytes(): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    /**
     * Adds [id] to the queue and starts it if nothing else is running.
     *
     * Caller holds [gate]: this reads and writes both the queue and the active slot.
     */
    private fun enqueue(id: Long) {
        if (_active.value?.id == id || id in queued) return
        queued.addLast(id)
        if (_active.value == null) startNextLocked()
    }

    /**
     * Promotes the next queued region, or clears the active slot when none is waiting.
     *
     * The slot is filled synchronously, before the coroutine that actually starts the
     * download is launched. That ordering is the whole point: [enqueue] decides whether
     * to start something by looking at the slot, and a slot still reading empty while a
     * start is in flight is how two downloads end up running at once.
     *
     * @see enqueue for the locking requirement.
     */
    private fun startNextLocked() {
        val next = queued.removeFirstOrNull()
        if (next == null) {
            _active.value = null
            return
        }
        _active.value = ActiveDownload(next, RegionStatus(downloading = true))
        scope.launch {
            regions.setDownloading(next, true)
            watch(next)
        }
    }

    /**
     * Follows one region's progress, and moves on when it finishes.
     *
     * The completion check is what makes the queue advance without anything having to
     * poll: MapLibre reports a status with `isComplete` set, and that is the signal to
     * stop the region and take the next one.
     */
    private fun watch(id: Long) {
        stopWatching()
        if (_active.value?.id != id) {
            _active.value = ActiveDownload(id, RegionStatus(downloading = true))
        }
        watcher = scope.launch {
            regions.observe(id).collect { status ->
                _active.value = ActiveDownload(id, status)
                if (status.complete || status.tileCeiling != null) {
                    regions.setDownloading(id, false)
                    refresh()
                    gate.withLock { startNextLocked() }
                    return@collect
                }
            }
        }
    }

    private fun stopWatching() {
        watcher?.cancel()
        watcher = null
    }

    /**
     * The screen density the tiles are fetched for.
     *
     * Has to match what the map asks for when it draws, or the raster relief tiles are
     * stored at one ratio and requested at another — which looks exactly like a
     * download that did not work.
     */
    private fun pixelRatio(): Float = context.resources.displayMetrics.density
}

/** A costed proposal, and whether it may go ahead. */
data class Quote(val plan: OfflinePlan, val verdict: BudgetVerdict) {
    val allowed: Boolean get() = verdict == BudgetVerdict.Allowed
}

/** The region currently downloading, with its latest reported progress. */
data class ActiveDownload(val id: Long, val status: RegionStatus)

sealed interface StartResult {
    data class Started(val id: Long) : StartResult
    data class Refused(val reason: BudgetVerdict) : StartResult

    /** MapLibre would not create the region; the message is in the log. */
    data object Failed : StartResult
}
