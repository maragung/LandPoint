package com.landpoint.app.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landpoint.app.data.BasemapMode
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.map.MapProvider
import com.landpoint.app.map.MapProviders
import com.landpoint.app.map.MapStyle
import com.landpoint.app.map.MapStyleFactory
import com.landpoint.app.map.offline.ArchiveStore
import com.landpoint.app.ui.container
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The preferences every map obeys, held where all four map screens can reach them.
 *
 * Its own ViewModel rather than a field on each screen's: which basemap to draw and
 * which units to rule in are preferences about maps in general, not about one land,
 * and threading them through the map, picker, preview and detail ViewModels would
 * mean the same lines four times over — and four chances for them to drift.
 *
 * Each screen gets its own instance and they all read the same DataStore keys, so a
 * style picked in the corner picker is the style the map tab shows afterwards.
 *
 * It also absorbs a job that used to be a per-screen chore. Every map ViewModel
 * previously opened its own handle on the offline map file and had to remember to
 * release it in `onCleared`; a style document has nothing to release, so that whole
 * pairing is gone and what a screen gets instead is a string.
 */
class MapPrefsViewModel(
    private val settings: SettingsRepository,
    private val archives: ArchiveStore,
    private val styles: MapStyleFactory
) : ViewModel() {

    /**
     * What is drawn, what units to rule in, and whether there is an imported map to
     * offer — resolved together, because the first and the last depend on each other.
     *
     * Null until the stored preferences and the archive on disk have been looked at.
     * Null rather than a street-map placeholder: guessing means loading one style
     * document, then loading the real one a few milliseconds later, and showing the
     * user a basemap they did not pick on the way to the one they did.
     */
    val state: StateFlow<MapPrefsState?> =
        combine(settings.basemap, settings.offlineMap, settings.units) { chosen, preferred, units ->
            // Reads a PMTiles header off disk, on the flow's own dispatcher rather
            // than during composition — the file is the user's and may be large.
            val archive = archives.spec(preferred)
            MapPrefsState(
                provider = MapProviders.resolved(chosen, archive),
                hasVectorMap = archive != null,
                imperial = units == SettingsRepository.Units.IMPERIAL
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /**
     * The style document for [provider], built or served from the last one built.
     *
     * Synchronous, and called from composition on purpose. It reads a bundled asset
     * rather than the network, the result is cached against the style's identity, and
     * making it a flow would mean a map screen with nothing to draw for a frame every
     * time the user changed basemap.
     */
    fun styleFor(provider: MapProvider, dark: Boolean): MapStyle = styles.styleFor(provider, dark)

    fun select(mode: BasemapMode) {
        viewModelScope.launch { settings.setBasemap(mode) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = container()
                MapPrefsViewModel(
                    settings = container.settings,
                    archives = container.archiveStore,
                    styles = container.mapStyleFactory
                )
            }
        }
    }
}

/**
 * The resolved map preferences.
 *
 * @param hasVectorMap whether an archive was found on the device. Decides if the
 *   imported style can be picked at all, which is why it travels with the provider
 *   instead of being asked for again by each screen.
 */
data class MapPrefsState(
    val provider: MapProvider,
    val hasVectorMap: Boolean,
    val imperial: Boolean
)

/**
 * What a map screen needs in order to draw, and to change what it draws.
 *
 * The chooser's visibility lives here too. Every map screen offers the same chooser
 * behind the same button, and a boolean each would be four copies of one idea.
 */
class MapPrefs internal constructor(
    /**
     * The document to hand [LandMap], or null while the preferences are still being
     * read. [LandMap] takes it as it comes and simply draws nothing until it arrives.
     */
    val style: MapStyle?,
    /**
     * The source behind that document, or null for the same reason. Needed by the
     * offline downloader, which costs an area against the zoom levels a source
     * actually publishes.
     */
    val provider: MapProvider?,
    /** Whether an offline vector map is loaded, which decides if that style can be picked. */
    val hasVectorMap: Boolean,
    /**
     * Whether distances on the map are ruled in feet and miles.
     *
     * Here rather than in each screen's own state because the scale bar is drawn by
     * three of them, and a ruler that disagrees with the measurements printed beside
     * it is worse than no ruler.
     */
    val imperial: Boolean,
    private val sheet: MutableState<Boolean>,
    private val onSelect: (BasemapMode) -> Unit
) {
    /**
     * The style being drawn, falling back to the default while the stored preference
     * is in flight.
     *
     * Non-null because what reads it is text — the attribution line and the selected
     * row in the chooser — and text can be corrected on the next frame. What cannot
     * be corrected cheaply is a style document, which is why [style] is honest about
     * not knowing yet and this is not.
     */
    val mode: BasemapMode get() = provider?.mode ?: BasemapMode.STREET

    val sheetVisible: Boolean get() = sheet.value

    fun showSheet() {
        sheet.value = true
    }

    fun hideSheet() {
        sheet.value = false
    }

    fun select(mode: BasemapMode) = onSelect(mode)
}

/**
 * The map preferences for this screen: the stored choices, resolved against what is
 * on the device, with the basemap turned into a style document.
 *
 * Takes no arguments. It used to be told whether an offline map existed, which meant
 * every caller first had to open one — the reason four ViewModels each held a map file
 * handle. Finding the archive is part of resolving the preference, so it happens here.
 */
@Composable
fun rememberMapPrefs(
    viewModel: MapPrefsViewModel = viewModel(factory = MapPrefsViewModel.Factory)
): MapPrefs {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    val provider = state?.provider
    // Rebuilt only when the source or the theme changes, not on every recomposition:
    // a map screen recomposes whenever a location update lands.
    val style = remember(provider, dark) { provider?.let { viewModel.styleFor(it, dark) } }
    // Kept across rotation: someone who turned the phone sideways to see more of
    // the ground did not mean to close the chooser they had just opened.
    val sheet = rememberSaveable { mutableStateOf(false) }
    return MapPrefs(
        style = style,
        provider = provider,
        hasVectorMap = state?.hasVectorMap == true,
        imperial = state?.imperial == true,
        sheet = sheet,
        onSelect = viewModel::select
    )
}
