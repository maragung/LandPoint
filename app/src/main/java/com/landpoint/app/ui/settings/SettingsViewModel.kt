package com.landpoint.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landpoint.app.PendingDeletes
import com.landpoint.app.R
import com.landpoint.app.data.CopyFailed
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.OfflineArchive
import com.landpoint.app.data.OfflineMapStore
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.data.UnsupportedType
import com.landpoint.app.data.export.BackupManager
import com.landpoint.app.data.export.DuplicateStrategy
import com.landpoint.app.data.export.ImportExportManager
import com.landpoint.app.data.export.PdfExporter
import com.landpoint.app.ui.container
import com.landpoint.app.util.AppStrings
import com.landpoint.app.util.Localization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val language: SettingsRepository.Language = SettingsRepository.Language.SYSTEM,
    val darkMode: SettingsRepository.DarkMode = SettingsRepository.DarkMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val coordFormat: SettingsRepository.CoordFormat = SettingsRepository.CoordFormat.DECIMAL,
    val units: SettingsRepository.Units = SettingsRepository.Units.METRIC,
    val areaUnit: SettingsRepository.AreaUnit = SettingsRepository.AreaUnit.SQM,
    val privacy: SettingsRepository.Privacy = SettingsRepository.Privacy(),
    val duplicateStrategy: DuplicateStrategy = DuplicateStrategy.SKIP,
    /** Set while a password-protected archive is waiting to be unlocked. */
    val restorePrompt: RestorePrompt? = null,
    val landCount: Int = 0,
    val offlineArchives: List<OfflineArchive> = emptyList(),
    val cachedTileBytes: Long = 0,
    val isBusy: Boolean = false,
    val message: String? = null
)

/**
 * A restore that stopped because the file is locked. [wrong] tells the dialog to
 * say the last attempt failed, rather than asking as if for the first time.
 */
data class RestorePrompt(val uri: Uri, val wrong: Boolean = false)

/** The four in-memory flows, grouped to stay inside combine()'s five-flow limit. */
private data class TaskState(
    val busy: Boolean,
    val message: String?,
    val strategy: DuplicateStrategy,
    val restorePrompt: RestorePrompt?
)

/** Offline map state, read off the filesystem rather than from a Flow. */
private data class OfflineState(
    val archives: List<OfflineArchive> = emptyList(),
    val cachedTileBytes: Long = 0
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val repository: LandRepository,
    private val importExport: ImportExportManager,
    private val backupManager: BackupManager,
    private val pdfExporter: PdfExporter,
    private val offlineMapStore: OfflineMapStore,
    private val strings: AppStrings,
    private val pendingDeletes: PendingDeletes
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val duplicateStrategy = MutableStateFlow(DuplicateStrategy.SKIP)
    private val restorePrompt = MutableStateFlow<RestorePrompt?>(null)
    private val offline = MutableStateFlow(OfflineState())

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            settings.language, settings.darkMode, settings.dynamicColor
        ) { lang, dark, dynamic -> Triple(lang, dark, dynamic) },
        combine(settings.coordFormat, settings.privacy) { format, privacy -> format to privacy },
        combine(settings.units, settings.areaUnit) { units, area -> units to area },
        // Paired to stay inside combine()'s five-flow limit.
        combine(repository.observeCount(), offline) { count, maps -> count to maps },
        combine(busy, message, duplicateStrategy, restorePrompt) { b, m, d, prompt ->
            TaskState(b, m, d, prompt)
        }
    ) { appearance, formatAndPrivacy, unitsAndArea, countAndMaps, task ->
        val (language, dark, dynamic) = appearance
        val (format, privacy) = formatAndPrivacy
        val (units, areaUnit) = unitsAndArea
        val (count, maps) = countAndMaps
        SettingsUiState(
            language = language,
            darkMode = dark,
            dynamicColor = dynamic,
            coordFormat = format,
            units = units,
            areaUnit = areaUnit,
            privacy = privacy,
            duplicateStrategy = task.strategy,
            restorePrompt = task.restorePrompt,
            landCount = count,
            offlineArchives = maps.archives,
            cachedTileBytes = maps.cachedTileBytes,
            isBusy = task.busy,
            message = task.message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    init {
        refreshOfflineMaps()
    }

    fun setLanguage(language: SettingsRepository.Language) {
        // Applied eagerly so any message produced by this same tap is already in
        // the new language; the DataStore write then makes it stick.
        Localization.setActive(language)
        viewModelScope.launch { settings.setLanguage(language) }
    }

    fun setDarkMode(mode: SettingsRepository.DarkMode) {
        viewModelScope.launch { settings.setDarkMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    fun setCoordFormat(format: SettingsRepository.CoordFormat) {
        viewModelScope.launch { settings.setCoordFormat(format) }
    }

    fun setUnits(units: SettingsRepository.Units) {
        viewModelScope.launch { settings.setUnits(units) }
    }

    fun setAreaUnit(unit: SettingsRepository.AreaUnit) {
        viewModelScope.launch { settings.setAreaUnit(unit) }
    }

    fun setAppLock(enabled: Boolean) {
        viewModelScope.launch { settings.setAppLock(enabled) }
    }

    fun setSecureScreen(enabled: Boolean) {
        viewModelScope.launch { settings.setSecureScreen(enabled) }
    }

    fun setStripPhotoLocation(enabled: Boolean) {
        viewModelScope.launch { settings.setStripPhotoLocation(enabled) }
    }

    fun setDuplicateStrategy(strategy: DuplicateStrategy) {
        duplicateStrategy.value = strategy
    }

    fun exportJson(uri: Uri) = runTask {
        val lands = repository.getAllLands()
        val result = importExport.exportJson(uri, lands)
        if (result.isFailure) strings.get(R.string.msg_backup_failed, result.error ?: "")
        else strings.plural(R.plurals.msg_backed_up, result.count)
    }

    /**
     * Full backup: land records plus their photos, in one file the user keeps
     * themselves. Nothing leaves the device — the archive is written to whatever
     * location the system file picker returned.
     */
    fun backupArchive(uri: Uri, passphrase: String? = null) = runTask {
        // Wiped straight after use. The String behind it cannot be — Compose text
        // fields deal in Strings — so this shortens the window rather than
        // closing it, which is still worth doing.
        val secret = passphrase?.takeIf { it.isNotEmpty() }?.toCharArray()
        val result = try {
            backupManager.backup(uri, secret)
        } finally {
            secret?.fill('\u0000')
        }
        when {
            result.isFailure -> strings.get(R.string.msg_backup_failed, result.error ?: "")
            // Say so when photo files had gone missing; a silently smaller
            // archive would look complete until the day it was needed.
            result.missingPhotos > 0 -> strings.get(
                R.string.msg_backup_archive_partial,
                result.lands,
                result.photos,
                result.missingPhotos
            )
            else -> strings.get(R.string.msg_backup_archive_done, result.lands, result.photos)
        }
    }

    fun restoreArchive(uri: Uri, passphrase: String? = null) = runTask {
        val secret = passphrase?.takeIf { it.isNotEmpty() }?.toCharArray()
        val result = try {
            backupManager.restore(uri, duplicateStrategy.value, secret)
        } finally {
            secret?.fill('\u0000')
        }
        val applied = result.result
        if (result.needsPassphrase) {
            // Not an error the user has to read and dismiss: it is a question, so
            // ask it. The dialog carries whether the last answer was wrong.
            restorePrompt.value = RestorePrompt(uri, wrong = result.wrongPassphrase)
            null
        } else if (result.isFailure || applied == null) {
            restorePrompt.value = null
            strings.get(R.string.msg_restore_failed, result.error ?: "")
        } else {
            restorePrompt.value = null
            summarise(applied.imported, applied.skipped, applied.replaced, applied.invalid) +
                if (applied.photos > 0) {
                    strings.plural(R.plurals.msg_restore_photos, applied.photos)
                } else ""
        }
    }

    fun exportCsv(uri: Uri) = runTask {
        val lands = repository.getAllLands()
        val result = importExport.exportCsv(uri, lands)
        if (result.isFailure) strings.get(R.string.msg_csv_failed, result.error ?: "")
        else strings.plural(R.plurals.msg_exported, result.count)
    }

    fun exportGpx(uri: Uri) = runTask {
        val lands = repository.getAllLands()
        val result = importExport.exportGpx(uri, lands)
        if (result.isFailure) strings.get(R.string.msg_geo_export_failed, result.error ?: "")
        else strings.plural(R.plurals.msg_exported, result.count)
    }

    fun exportKml(uri: Uri) = runTask {
        val lands = repository.getAllLands()
        val result = importExport.exportKml(uri, lands)
        if (result.isFailure) strings.get(R.string.msg_geo_export_failed, result.error ?: "")
        else strings.plural(R.plurals.msg_exported, result.count)
    }

    fun exportPdfReport(uri: Uri) = runTask {
        val lands = repository.getAllLands()
        val dms = uiState.value.coordFormat == SettingsRepository.CoordFormat.DMS
        val result = pdfExporter.exportReport(uri, lands, dms, uiState.value.areaUnit)
        if (result.isFailure) strings.get(R.string.msg_pdf_failed, result.error ?: "")
        else strings.plural(R.plurals.msg_report_saved, result.count)
    }

    fun importJson(uri: Uri) = importFrom(uri)

    fun importCsv(uri: Uri) = importFrom(uri)

    /** Re-reads the offline map directory; cheap enough to call on every visit. */
    fun refreshOfflineMaps() {
        viewModelScope.launch {
            offline.value = OfflineState(
                archives = offlineMapStore.archives(),
                cachedTileBytes = offlineMapStore.cachedTileBytes()
            )
        }
    }

    fun importOfflineMap(uri: Uri) = runTask {
        val result = offlineMapStore.importArchive(uri)
        refreshOfflineMaps()
        when (val error = result.error) {
            null -> strings.get(R.string.msg_offline_map_added, result.name ?: "")
            is UnsupportedType -> strings.get(
                R.string.msg_offline_map_unsupported,
                error.extension
            )
            is CopyFailed -> strings.get(
                R.string.msg_offline_map_failed,
                error.reason ?: ""
            )
        }
    }

    fun deleteOfflineMap(name: String) = runTask {
        val deleted = offlineMapStore.deleteArchive(name)
        refreshOfflineMaps()
        if (deleted) strings.get(R.string.msg_offline_map_removed, name)
        else strings.get(R.string.msg_offline_map_failed, name)
    }

    /** Both entry points funnel here — the manager sniffs the content itself. */
    private fun importFrom(uri: Uri) = runTask {
        val result = importExport.import(uri, duplicateStrategy.value)
        if (result.isFailure) {
            strings.get(R.string.msg_import_failed, result.error ?: "")
        } else {
            summarise(result.imported, result.skipped, result.replaced, result.invalid)
        }
    }

    private fun summarise(imported: Int, skipped: Int, replaced: Int, invalid: Int): String =
        buildString {
            append(strings.get(R.string.msg_import_imported, imported))
            if (replaced > 0) append(strings.get(R.string.msg_import_replaced, replaced))
            if (skipped > 0) append(strings.plural(R.plurals.msg_import_skipped, skipped))
            if (invalid > 0) append(strings.plural(R.plurals.msg_import_invalid, invalid))
        }

    /** [block] returns the line to show, or null when there is nothing to say. */
    private fun runTask(block: suspend () -> String?) {
        viewModelScope.launch {
            busy.value = true
            message.value = runCatching {
                // A backup or export reads the database directly, so a delete
                // still inside its undo window would otherwise be written back
                // out into the file as if it had never happened.
                pendingDeletes.flush()
                block()
            }.getOrElse {
                strings.get(
                    R.string.msg_generic_error,
                    it.message ?: it::class.simpleName.orEmpty()
                )
            }
            busy.value = false
        }
    }

    fun dismissRestorePrompt() {
        restorePrompt.value = null
    }

    fun consumeMessage() {
        message.value = null
    }

    /** Lets the screen surface a problem it detected itself, e.g. no browser. */
    fun showMessage(text: String) {
        message.value = text
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = container()
                SettingsViewModel(
                    c.settings,
                    c.repository,
                    c.importExport,
                    c.backupManager,
                    c.pdfExporter,
                    c.offlineMapStore,
                    c.strings,
                    c.pendingDeletes
                )
            }
        }
    }
}
