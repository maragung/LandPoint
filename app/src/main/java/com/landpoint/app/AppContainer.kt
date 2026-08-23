package com.landpoint.app

import android.content.Context
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.PhotoStamper
import com.landpoint.app.data.PhotoStore
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.data.db.LandDatabase
import com.landpoint.app.data.export.BackupManager
import com.landpoint.app.data.export.ImportExportManager
import com.landpoint.app.data.export.PdfExporter
import com.landpoint.app.location.DeviceSensors
import com.landpoint.app.location.GnssSignal
import com.landpoint.app.location.LocationProvider
import com.landpoint.app.location.LocationTracker
import com.landpoint.app.map.MapStyleFactory
import com.landpoint.app.map.offline.ArchiveStore
import com.landpoint.app.map.offline.OfflineDownloadCoordinator
import com.landpoint.app.map.offline.OfflineRegionStore
import com.landpoint.app.map.offline.OfflineStyleWriter
import com.landpoint.app.util.AppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency container. The graph is small enough that a DI
 * framework would cost more (build time, APK size) than it saves.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val strings: AppStrings by lazy { AppStrings(appContext) }

    val photoStore: PhotoStore by lazy { PhotoStore(appContext) }

    val photoStamper: PhotoStamper by lazy { PhotoStamper(appContext, strings) }

    /**
     * A scope that lives as long as the process, for work no screen owns.
     *
     * Only the offline download queue uses it, and it needs exactly this: a download
     * has to keep running — and the next one in the queue has to start — while the user
     * is somewhere else in the app or has put the phone in their pocket. A
     * `viewModelScope` would cancel that the moment the offline screen closed.
     *
     * `SupervisorJob` so one failed download cannot take the queue down with it.
     */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /** Maps the user imported themselves. */
    val archiveStore: ArchiveStore by lazy { ArchiveStore(appContext) }

    /** Turns a chosen style into the JSON document MapLibre draws from. */
    val mapStyleFactory: MapStyleFactory by lazy { MapStyleFactory(appContext.assets) }

    private val offlineRegions: OfflineRegionStore by lazy { OfflineRegionStore(appContext) }

    private val offlineStyleWriter: OfflineStyleWriter by lazy {
        OfflineStyleWriter(appContext, appContext.assets)
    }

    /**
     * Shared, like [pendingDeletes] and for the same reason: a download outlives the
     * screen it was started from, and two coordinators would mean two queues each
     * believing it was the only one running.
     */
    val offlineDownloads: OfflineDownloadCoordinator by lazy {
        OfflineDownloadCoordinator(
            context = appContext,
            regions = offlineRegions,
            styles = offlineStyleWriter,
            scope = appScope
        )
    }

    val repository: LandRepository by lazy {
        LandRepository(LandDatabase.get(appContext).landDao(), photoStore)
    }

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }

    /**
     * The joined live reading: fix, satellites, motion, compass.
     *
     * Shared rather than built per ViewModel because the sources underneath are
     * device-wide. Two trackers would mean two `GnssStatus` callbacks and two sets of
     * sensor listeners for one phone, which costs battery and gives two screens
     * slightly different answers to the same question.
     *
     * Holding it here starts nothing: `observe()` is cold, so the radios wake only
     * while a screen is collecting.
     */
    val locationTracker: LocationTracker by lazy {
        LocationTracker(
            provider = locationProvider,
            gnss = GnssSignal(appContext),
            sensors = DeviceSensors(appContext)
        )
    }

    val importExport: ImportExportManager by lazy { ImportExportManager(appContext, repository) }

    val backupManager: BackupManager by lazy {
        BackupManager(appContext, repository, photoStore, importExport)
    }

    val pdfExporter: PdfExporter by lazy { PdfExporter(appContext, strings) }

    /**
     * Shared, not per-ViewModel: a land deleted from its detail screen has to
     * stay undoable after that screen — and its ViewModel — is gone.
     */
    val pendingDeletes: PendingDeletes by lazy { PendingDeletes(repository) }
}
