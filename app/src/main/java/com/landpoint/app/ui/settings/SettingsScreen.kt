package com.landpoint.app.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.landpoint.app.BuildConfig
import com.landpoint.app.R
import com.landpoint.app.data.MapKind
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.data.export.BackupManager
import com.landpoint.app.data.export.DuplicateStrategy
import com.landpoint.app.ui.security.AppLockState
import com.landpoint.app.ui.security.deviceCanAuthenticate

/**
 * [onBack] is null when this is reached as a bottom-bar tab: there is nothing
 * below it to go back to, so no back arrow is drawn. It stays a parameter so the
 * screen can still be pushed on top of another one later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val noBrowserMessage = stringResource(R.string.msg_no_browser_app)

    val exportJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportJson) }

    // Two launchers because the picker's mime type is fixed when it is created,
    // and a password-protected archive is not a zip.
    val backupArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { viewModel.backupArchive(it) } }

    var pendingPassphrase by remember { mutableStateOf<String?>(null) }
    val backupArchiveLocked = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { viewModel.backupArchive(it, pendingPassphrase) }
        pendingPassphrase = null
    }
    var askBackupPassphrase by remember { mutableStateOf(false) }

    val restoreArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::restoreArchive) }

    val exportCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let(viewModel::exportCsv) }

    val exportPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> uri?.let(viewModel::exportPdfReport) }

    val exportGpx = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri -> uri?.let(viewModel::exportGpx) }

    val exportKml = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")
    ) { uri -> uri?.let(viewModel::exportKml) }

    val importJson = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importJson) }

    val importCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importCsv) }

    val importOfflineMap = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importOfflineMap) }

    // Asked once: enrolling a fingerprint or setting a PIN happens in system
    // settings, and returning from there brings this screen back with it.
    val context = LocalContext.current
    val canLock = remember(context) { deviceCanAuthenticate(context) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            SettingsSection(stringResource(R.string.settings_section_language)) {
                OptionGroup(
                    options = SettingsRepository.Language.entries,
                    selected = state.language,
                    label = { stringResource(it.labelRes) },
                    onSelect = viewModel::setLanguage
                )
            }

            SettingsSection(stringResource(R.string.settings_section_appearance)) {
                OptionGroup(
                    options = SettingsRepository.DarkMode.entries,
                    selected = state.darkMode,
                    label = { stringResource(it.labelRes) },
                    onSelect = viewModel::setDarkMode
                )
                // Only offered where the platform can actually supply the palette;
                // below Android 12 the switch would be a control that does nothing.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(
                        label = stringResource(R.string.settings_dynamic_color),
                        hint = stringResource(R.string.settings_dynamic_color_hint),
                        checked = state.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor
                    )
                }
            }

            SettingsSection(stringResource(R.string.settings_section_coordinate_format)) {
                OptionGroup(
                    options = SettingsRepository.CoordFormat.entries,
                    selected = state.coordFormat,
                    label = { stringResource(it.labelRes) },
                    onSelect = viewModel::setCoordFormat
                )
            }

            SettingsSection(stringResource(R.string.settings_section_units)) {
                OptionGroup(
                    options = SettingsRepository.Units.entries,
                    selected = state.units,
                    label = { stringResource(it.labelRes) },
                    onSelect = viewModel::setUnits
                )
            }

            SettingsSection(stringResource(R.string.settings_section_area_unit)) {
                OptionGroup(
                    options = SettingsRepository.AreaUnit.entries,
                    selected = state.areaUnit,
                    label = { stringResource(it.labelRes) },
                    onSelect = viewModel::setAreaUnit
                )
            }

            SettingsSection(stringResource(R.string.settings_section_privacy)) {
                Text(
                    stringResource(R.string.settings_privacy_no_cloud),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SwitchRow(
                    label = stringResource(R.string.settings_strip_photo_location),
                    hint = stringResource(R.string.settings_strip_photo_location_hint),
                    checked = state.privacy.stripPhotoLocation,
                    onCheckedChange = viewModel::setStripPhotoLocation
                )
                SwitchRow(
                    label = stringResource(R.string.settings_app_lock),
                    hint = stringResource(
                        if (canLock) R.string.settings_app_lock_hint
                        else R.string.settings_app_lock_unavailable
                    ),
                    // A lock left on from a phone that has since had its screen
                    // lock removed reads as off, because that is what it now is.
                    checked = state.privacy.appLock && canLock,
                    enabled = canLock,
                    onCheckedChange = { on ->
                        // Whoever switches this on is present by definition;
                        // making them authenticate on the spot would be theatre.
                        if (on) AppLockState.unlock()
                        viewModel.setAppLock(on)
                    }
                )
                SwitchRow(
                    label = stringResource(R.string.settings_secure_screen),
                    hint = stringResource(R.string.settings_secure_screen_hint),
                    checked = state.privacy.secureScreen,
                    onCheckedChange = viewModel::setSecureScreen
                )
            }

            SettingsSection(stringResource(R.string.settings_section_backup)) {
                Text(
                    pluralStringResource(
                        R.plurals.settings_stored_count,
                        state.landCount,
                        state.landCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.settings_backup_archive_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ActionRow(
                    stringResource(R.string.settings_backup_archive),
                    enabled = !state.isBusy
                ) {
                    askBackupPassphrase = true
                }
                ActionRow(
                    stringResource(R.string.settings_backup_json),
                    enabled = !state.isBusy
                ) {
                    exportJson.launch("landpoint-backup.json")
                }
                ActionRow(
                    stringResource(R.string.settings_export_csv),
                    enabled = !state.isBusy
                ) {
                    exportCsv.launch("landpoint-locations.csv")
                }
                ActionRow(
                    stringResource(R.string.settings_export_pdf),
                    enabled = !state.isBusy
                ) {
                    exportPdf.launch("landpoint-report.pdf")
                }
                ActionRow(
                    stringResource(R.string.settings_export_gpx),
                    enabled = !state.isBusy
                ) {
                    exportGpx.launch("landpoint-locations.gpx")
                }
                ActionRow(
                    stringResource(R.string.settings_export_kml),
                    enabled = !state.isBusy
                ) {
                    exportKml.launch("landpoint-locations.kml")
                }
            }

            SettingsSection(stringResource(R.string.settings_section_restore)) {
                Text(
                    stringResource(R.string.settings_duplicate_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OptionGroup(
                    options = DuplicateStrategy.entries,
                    selected = state.duplicateStrategy,
                    label = { stringResource(it.labelRes) },
                    onSelect = viewModel::setDuplicateStrategy
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ActionRow(
                    stringResource(R.string.settings_restore_archive),
                    enabled = !state.isBusy
                ) {
                    restoreArchive.launch(arrayOf("application/zip", "*/*"))
                }
                ActionRow(
                    stringResource(R.string.settings_restore_json),
                    enabled = !state.isBusy
                ) {
                    importJson.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
                ActionRow(
                    stringResource(R.string.settings_import_csv),
                    enabled = !state.isBusy
                ) {
                    importCsv.launch(
                        arrayOf(
                            "text/csv",
                            "text/comma-separated-values",
                            "text/plain",
                            "*/*"
                        )
                    )
                }
            }

            SettingsSection(stringResource(R.string.settings_section_offline)) {
                Text(
                    stringResource(R.string.settings_offline_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(
                        R.string.settings_offline_cached,
                        formatBytes(state.cachedTileBytes)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                if (state.offlineArchives.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_offline_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.offlineArchives.forEach { archive ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(archive.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(
                                        when (archive.kind) {
                                            MapKind.VECTOR -> R.string.settings_offline_kind_vector
                                            MapKind.RASTER_ARCHIVE ->
                                                R.string.settings_offline_kind_raster
                                        }
                                    ) + " · " + formatBytes(archive.bytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { viewModel.deleteOfflineMap(archive.name) },
                                enabled = !state.isBusy
                            ) { Text(stringResource(R.string.action_remove)) }
                        }
                    }
                }
                ActionRow(
                    stringResource(R.string.settings_offline_add),
                    enabled = !state.isBusy
                ) {
                    // "*/*" because .mbtiles and .map have no registered MIME
                    // type, so a narrower filter would grey the file out.
                    importOfflineMap.launch(arrayOf("*/*"))
                }
                Text(
                    stringResource(R.string.settings_offline_get_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ActionRow(
                    stringResource(R.string.settings_offline_get),
                    enabled = true
                ) {
                    // Hands the download to the browser rather than doing it
                    // in-app: the maps are hosted on a donation-funded server,
                    // and an app that fetches them automatically turns every
                    // install into traffic its operators never agreed to.
                    //
                    // A stripped-down device may have no browser at all, and an
                    // uncaught ActivityNotFoundException here would take the
                    // settings screen down with it.
                    runCatching {
                        uriHandler.openUri("https://www.openandromaps.org/en/downloads")
                    }.onFailure { viewModel.showMessage(noBrowserMessage) }
                }
            }

            SettingsSection(stringResource(R.string.settings_section_about)) {
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.settings_about_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.legal_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ActionRow(
                    label = stringResource(R.string.settings_show_intro),
                    enabled = true,
                    onClick = viewModel::replayOnboarding
                )
            }
        }
    }

    if (askBackupPassphrase) {
        BackupPassphraseDialog(
            onDismiss = { askBackupPassphrase = false },
            onWithout = {
                askBackupPassphrase = false
                backupArchive.launch(BackupManager.suggestFileName())
            },
            onWith = { passphrase ->
                askBackupPassphrase = false
                pendingPassphrase = passphrase
                backupArchiveLocked.launch(BackupManager.suggestFileName(encrypted = true))
            }
        )
    }

    // Raised by the ViewModel when the chosen file turns out to be locked, so it
    // survives the screen being rebuilt while the restore is still pending.
    state.restorePrompt?.let { prompt ->
        RestorePassphraseDialog(
            wrong = prompt.wrong,
            onDismiss = viewModel::dismissRestorePrompt,
            onSubmit = { passphrase -> viewModel.restoreArchive(prompt.uri, passphrase) }
        )
    }
}

/**
 * Offered before the file picker, because the answer decides the extension and
 * there is no going back to it afterwards.
 */
@Composable
private fun BackupPassphraseDialog(
    onDismiss: () -> Unit,
    onWithout: () -> Unit,
    onWith: (String) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    val tooShort = passphrase.isNotEmpty() && passphrase.length < MIN_PASSPHRASE
    val mismatch = confirmation.isNotEmpty() && confirmation != passphrase
    val ready = passphrase.length >= MIN_PASSPHRASE && confirmation == passphrase

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.backup_password_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                // Said plainly and before the fact: there is no recovery path,
                // and finding that out later means finding it out too late.
                Text(
                    stringResource(R.string.backup_password_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                PassphraseField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = stringResource(R.string.backup_password_field),
                    revealed = revealed,
                    onToggleReveal = { revealed = !revealed },
                    isError = tooShort
                )
                PassphraseField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = stringResource(R.string.backup_password_confirm),
                    revealed = revealed,
                    onToggleReveal = { revealed = !revealed },
                    isError = mismatch
                )
                if (tooShort || mismatch) {
                    Text(
                        stringResource(
                            if (mismatch) R.string.backup_password_mismatch
                            else R.string.backup_password_too_short
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onWith(passphrase) }, enabled = ready) {
                Text(stringResource(R.string.backup_password_use))
            }
        },
        dismissButton = {
            TextButton(onClick = onWithout) {
                Text(stringResource(R.string.backup_password_skip))
            }
        }
    )
}

/** Asked when the chosen file is locked. Cancelling leaves the database untouched. */
@Composable
private fun RestorePassphraseDialog(
    wrong: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        if (wrong) R.string.restore_password_wrong
                        else R.string.restore_password_body
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (wrong) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                PassphraseField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = stringResource(R.string.backup_password_field),
                    revealed = revealed,
                    onToggleReveal = { revealed = !revealed },
                    isError = wrong
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(passphrase) },
                enabled = passphrase.isNotEmpty()
            ) {
                Text(stringResource(R.string.restore_password_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Hidden by default, with a way to look: a password typed blind onto a phone
 * keyboard is a password that gets mistyped, and the only copy of the archive
 * would then be locked by something the user never meant to type.
 */
@Composable
private fun PassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    isError: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation =
            if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onToggleReveal) {
                Icon(
                    if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(
                        if (revealed) R.string.password_hide else R.string.password_show
                    )
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/** Short enough to be typed on a phone, long enough not to be guessed at once. */
private const val MIN_PASSPHRASE = 6

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(colors = CardDefaults.cardColors()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun <T> OptionGroup(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(modifier = Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = option == selected,
                    onClick = { onSelect(option) }
                )
                Text(label(option), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * A setting that is simply on or off, with room for the sentence that explains
 * what turning it on does.
 *
 * The whole row toggles rather than only the switch, and the switch itself is
 * left unclickable so a screen reader is offered one control instead of two that
 * do the same thing.
 */
@Composable
private fun SwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ActionRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label)
    }
}

/**
 * Locale.US on purpose — this is a size, and the app's own decimal-comma rules
 * do not apply to it. Binary units, because that is what a file manager shows.
 */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 MB"
    bytes < 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 ->
        String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
