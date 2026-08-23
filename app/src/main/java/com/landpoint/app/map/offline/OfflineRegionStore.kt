package com.landpoint.app.map.offline

import android.content.Context
import android.util.Log
import com.landpoint.app.map.GeoBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume

private const val TAG = "OfflineRegionStore"

/**
 * The saved areas, as coroutines instead of callbacks.
 *
 * MapLibre's offline API is a set of callback interfaces that must be touched from
 * the main thread, and whose results arrive there too. Wrapping it once here keeps
 * that in a single file and gives the rest of the app suspending functions and a
 * flow, which is what the ViewModels above it are written in.
 *
 * The important property this preserves is that **MapLibre is the only place region
 * state lives**. Names, progress and tile counts are all read back out of its SQLite
 * database, so a download interrupted by the process being killed is still there,
 * still half-finished, and still resumable when the app comes back — with no state of
 * the app's own to have got out of step in the meantime.
 */
class OfflineRegionStore(private val context: Context) {

    /**
     * Regions handed out by MapLibre, kept by id.
     *
     * Held because pausing, resuming or observing a region needs the same object
     * MapLibre created — an id alone will not do — and because `setObserver` on a
     * second copy of the same region silently replaces the first one's observer.
     */
    private val known = mutableMapOf<Long, OfflineRegion>()

    private suspend fun manager(): OfflineManager = withContext(Dispatchers.Main) {
        OfflineManager.getInstance(context)
    }

    /**
     * Every saved area, with what MapLibre currently knows about each.
     *
     * Regions whose metadata cannot be read are still returned, with a null [
     * SavedRegion.meta]. They are almost certainly from an older design or a merged
     * database, and the one thing a user needs to be able to do with a region they
     * cannot identify is delete it.
     */
    suspend fun list(): List<SavedRegion> = withContext(Dispatchers.Main) {
        val regions = suspendCancellableCoroutine { continuation ->
            manageOnMain().listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        continuation.resume(offlineRegions?.toList().orEmpty())
                    }

                    override fun onError(error: String) {
                        Log.w(TAG, "listOfflineRegions: $error")
                        continuation.resume(emptyList())
                    }
                }
            )
        }
        regions.forEach { known[it.id] = it }
        regions.map { region ->
            SavedRegion(
                id = region.id,
                meta = OfflineRegionMeta.decode(region.metadata),
                status = statusOf(region)
            )
        }.sortedByDescending { it.meta?.createdAt ?: 0L }
    }

    /**
     * Creates a region and leaves it stopped.
     *
     * Deliberately not started here. Creating and starting are separate steps because
     * the coordinator above enforces one download at a time, and a region that starts
     * the moment it exists cannot be queued.
     *
     * @param styleUrl a URL MapLibre can read to discover which sources to fetch —
     *   see [OfflineStyleWriter], which writes a minimal one to local storage.
     * @return the new region's id, or null if MapLibre refused to create it.
     */
    suspend fun create(
        meta: OfflineRegionMeta,
        styleUrl: String,
        pixelRatio: Float
    ): Long? = withContext(Dispatchers.Main) {
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            meta.bounds.toLatLngBounds(),
            meta.minZoom.toDouble(),
            meta.maxZoom.toDouble(),
            pixelRatio
        )
        // Typed explicitly: the failure path resumes with null, and left to infer
        // from that alone the coroutine's type collapses to Nothing?.
        val region = suspendCancellableCoroutine<OfflineRegion?> { continuation ->
            manageOnMain().createOfflineRegion(
                definition,
                meta.encode(),
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        continuation.resume(offlineRegion)
                    }

                    override fun onError(error: String) {
                        Log.w(TAG, "createOfflineRegion: $error")
                        continuation.resume(null)
                    }
                }
            )
        } ?: return@withContext null

        known[region.id] = region
        region.id
    }

    /** Starts or stops downloading. Resuming a part-finished region continues it. */
    suspend fun setDownloading(id: Long, downloading: Boolean) = withContext(Dispatchers.Main) {
        val region = known[id] ?: return@withContext
        region.setDownloadState(
            if (downloading) OfflineRegion.STATE_ACTIVE else OfflineRegion.STATE_INACTIVE
        )
    }

    /** Renames a region by rewriting its metadata blob. */
    suspend fun rename(id: Long, name: String): Boolean = withContext(Dispatchers.Main) {
        val region = known[id] ?: return@withContext false
        val current = OfflineRegionMeta.decode(region.metadata) ?: return@withContext false
        val updated = current.copy(name = name)
        suspendCancellableCoroutine { continuation ->
            region.updateMetadata(
                updated.encode(),
                object : OfflineRegion.OfflineRegionUpdateMetadataCallback {
                    override fun onUpdate(metadata: ByteArray) = continuation.resume(true)

                    override fun onError(error: String) {
                        Log.w(TAG, "updateMetadata: $error")
                        continuation.resume(false)
                    }
                }
            )
        }
    }

    /**
     * Deletes a region and the tiles only it was holding.
     *
     * Stopped first. Deleting a region that is still downloading is the documented
     * way to leave MapLibre's database with a writer pointed at rows that are going
     * away, and the symptom is a crash inside native code rather than an error.
     */
    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.Main) {
        val region = known[id] ?: return@withContext false
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        val deleted = suspendCancellableCoroutine { continuation ->
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() = continuation.resume(true)

                override fun onError(error: String) {
                    Log.w(TAG, "delete: $error")
                    continuation.resume(false)
                }
            })
        }
        if (deleted) known.remove(id)
        deleted
    }

    /**
     * Re-checks a finished region against its source and re-downloads what changed.
     *
     * MapLibre's own word for this is "invalidate": the tiles stay usable throughout,
     * and only the ones the server says are stale are fetched again. That is what
     * makes updating a saved area cheap enough to offer as a button.
     */
    suspend fun update(id: Long): Boolean = withContext(Dispatchers.Main) {
        val region = known[id] ?: return@withContext false
        val invalidated = suspendCancellableCoroutine { continuation ->
            region.invalidate(object : OfflineRegion.OfflineRegionInvalidateCallback {
                override fun onInvalidate() = continuation.resume(true)

                override fun onError(error: String) {
                    Log.w(TAG, "invalidate: $error")
                    continuation.resume(false)
                }
            })
        }
        if (invalidated) region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        invalidated
    }

    /**
     * Progress for one region, for as long as anyone is collecting.
     *
     * `setDeliverInactiveMessages(true)` is what makes a paused region keep reporting
     * where it got to; without it the progress bar freezes at whatever it last showed
     * and a resumed download appears to start over.
     */
    fun observe(id: Long): Flow<RegionStatus> = callbackFlow {
        val region = known[id]
        if (region == null) {
            close()
            return@callbackFlow
        }

        region.setDeliverInactiveMessages(true)
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                trySend(status.toRegionStatus())
            }

            override fun onError(error: OfflineRegionError) {
                // Not fatal and not silent. A failed tile is retried by MapLibre
                // itself; what matters is that the download does not appear stuck
                // with no explanation, so the reason is logged and the last known
                // progress stands.
                Log.w(TAG, "region $id: ${error.reason} ${error.message}")
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                Log.w(TAG, "region $id hit the tile ceiling of $limit")
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                trySend(RegionStatus.limitReached(limit))
            }
        })

        awaitClose {
            // Replaced rather than cleared. The region has to stop calling into this
            // now-closed channel, but MapLibre wraps whatever is passed here in a
            // handler-posting delegate that dereferences it unconditionally — so
            // handing it null swaps a leak for a crash on the next status message.
            region.setObserver(SILENT_OBSERVER)
            region.setDeliverInactiveMessages(false)
        }
    }.flowOn(Dispatchers.Main)

    /** A one-off reading, for a list that is not being watched. */
    suspend fun statusOf(id: Long): RegionStatus = withContext(Dispatchers.Main) {
        val region = known[id] ?: return@withContext RegionStatus()
        statusOf(region)
    }

    private suspend fun statusOf(region: OfflineRegion): RegionStatus =
        suspendCancellableCoroutine { continuation ->
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                // Both parameters are nullable in MapLibre's own signature, unlike the
                // observer's, so they are taken as written: a null reading is a reading
                // that did not arrive, and is reported as an empty one.
                override fun onStatus(status: OfflineRegionStatus?) {
                    continuation.resume(status?.toRegionStatus() ?: RegionStatus())
                }

                override fun onError(error: String?) {
                    Log.w(TAG, "getStatus: $error")
                    continuation.resume(RegionStatus())
                }
            })
        }

    /**
     * A hard ceiling on tiles across all regions, enforced inside MapLibre.
     *
     * A second line of defence behind [OfflineBudget], and a different kind: the
     * budget refuses a request before it starts, while this stops a download that
     * turned out to need far more than the estimate suggested. Without it an
     * underestimate becomes a full phone rather than a stopped download.
     */
    suspend fun setTileCeiling(tiles: Long) = withContext(Dispatchers.Main) {
        manageOnMain().setOfflineMapboxTileCountLimit(tiles)
    }

    /**
     * Drops the tiles that were merely passed over while browsing, keeping the ones
     * belonging to saved regions.
     *
     * This is the cleanup that is safe to run whenever storage is short. It cannot
     * touch a saved area: MapLibre distinguishes the ambient cache from region tiles
     * precisely so that a cache purge does not destroy something the user asked to
     * keep.
     */
    suspend fun clearBrowsingCache() = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            manageOnMain().clearAmbientCache(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = continuation.resume(Unit)

                override fun onError(message: String) {
                    Log.w(TAG, "clearAmbientCache: $message")
                    continuation.resume(Unit)
                }
            })
        }
    }

    /**
     * Reclaims the space freed by deleting regions.
     *
     * SQLite does not shrink a file when rows are removed, so a user who deletes a
     * 300 MB area and sees no change in their storage settings is looking at a bug
     * until this runs. Slow, and blocks other database work, so it belongs after a
     * delete rather than on any path the user is waiting on.
     */
    suspend fun compact() = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            manageOnMain().packDatabase(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = continuation.resume(Unit)

                override fun onError(message: String) {
                    Log.w(TAG, "packDatabase: $message")
                    continuation.resume(Unit)
                }
            })
        }
    }

    /**
     * Already on the main thread by construction — every caller above is inside a
     * `withContext(Dispatchers.Main)`. Exists so that fact is stated once.
     */
    private fun manageOnMain(): OfflineManager = OfflineManager.getInstance(context)
}

/** One saved area, as the UI needs it. */
data class SavedRegion(
    val id: Long,
    /** Null when the metadata was written by something other than this app. */
    val meta: OfflineRegionMeta?,
    val status: RegionStatus
)

/**
 * How far a region has got.
 *
 * @param requiredResources what MapLibre currently believes the total to be. Not a
 *   fixed number: early in a download it is a lower bound that grows as tiles are
 *   discovered, which is what [requiredPrecise] reports.
 */
data class RegionStatus(
    val downloading: Boolean = false,
    val complete: Boolean = false,
    val completedTiles: Long = 0,
    val completedBytes: Long = 0,
    /**
     * Resources finished, which is tiles plus the style, fonts and icons.
     *
     * Counted separately from [completedTiles] because [requiredResources] is a
     * resource count too, and dividing tiles by resources gives a progress bar that
     * never reaches the end.
     */
    val completedResources: Long = 0,
    val requiredResources: Long = 0,
    val requiredPrecise: Boolean = false,
    /** Set when MapLibre stopped the download for exceeding the tile ceiling. */
    val tileCeiling: Long? = null
) {
    /**
     * Fraction done, or null while the total is still a guess.
     *
     * Null rather than a number, because a progress bar computed from a growing total
     * runs backwards — it reaches 80% and then falls to 40% as more tiles are found,
     * which reads as the download failing and restarting. Better to show tiles
     * counted up until the total is firm.
     */
    val fraction: Float?
        get() = when {
            complete -> 1f
            !requiredPrecise || requiredResources <= 0L -> null
            else -> (completedResources.toFloat() / requiredResources).coerceIn(0f, 1f)
        }

    companion object {
        fun limitReached(limit: Long) = RegionStatus(downloading = false, tileCeiling = limit)
    }
}

private fun OfflineRegionStatus.toRegionStatus() = RegionStatus(
    downloading = downloadState == OfflineRegion.STATE_ACTIVE,
    complete = isComplete,
    completedTiles = completedTileCount,
    completedBytes = completedResourceSize,
    completedResources = completedResourceCount,
    requiredResources = requiredResourceCount,
    requiredPrecise = isRequiredResourceCountPrecise
)

/**
 * An observer that does nothing, installed when nobody is watching a region.
 *
 * A single shared instance, holding no reference to anything: it exists so that
 * detaching an observer never means handing MapLibre a null it will dereference.
 */
private val SILENT_OBSERVER = object : OfflineRegion.OfflineRegionObserver {
    override fun onStatusChanged(status: OfflineRegionStatus) = Unit
    override fun onError(error: OfflineRegionError) = Unit
    override fun mapboxTileCountLimitExceeded(limit: Long) = Unit
}

/** MapLibre's rectangle, from the app's. */
internal fun GeoBounds.toLatLngBounds(): LatLngBounds = LatLngBounds.Builder()
    .include(LatLng(north, east))
    .include(LatLng(south, west))
    .build()
