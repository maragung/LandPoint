package com.landpoint.app

import android.content.Context
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.OfflineMapStore
import com.landpoint.app.data.PhotoStamper
import com.landpoint.app.data.PhotoStore
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.data.db.LandDatabase
import com.landpoint.app.data.export.BackupManager
import com.landpoint.app.data.export.ImportExportManager
import com.landpoint.app.data.export.PdfExporter
import com.landpoint.app.location.LocationProvider
import com.landpoint.app.util.AppStrings

/**
 * Hand-rolled dependency container. The graph is small enough that a DI
 * framework would cost more (build time, APK size) than it saves.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val strings: AppStrings by lazy { AppStrings(appContext) }

    val photoStore: PhotoStore by lazy { PhotoStore(appContext) }

    val photoStamper: PhotoStamper by lazy { PhotoStamper(appContext, strings) }

    val offlineMapStore: OfflineMapStore by lazy { OfflineMapStore(appContext) }

    val repository: LandRepository by lazy {
        LandRepository(LandDatabase.get(appContext).landDao(), photoStore)
    }

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }

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
