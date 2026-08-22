package com.landpoint.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landpoint.app.data.BasemapMode
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.ui.container
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The chosen map style, held where all four map screens can reach it.
 *
 * Its own ViewModel rather than a field on each screen's: the style is a
 * preference about maps in general, not about one land, and threading it through
 * the map, picker, preview and detail ViewModels would mean the same three lines
 * four times over — and four chances for them to drift.
 *
 * Each screen gets its own instance and they all read the one DataStore key, so a
 * style picked in the corner picker is the style the map tab shows afterwards.
 */
class BasemapViewModel(private val settings: SettingsRepository) : ViewModel() {

    /**
     * Null until the stored value arrives, and null again if nothing was ever
     * stored — [BasemapChoice] resolves both the same way, because a first-run
     * user and a user whose preference is still being read off disk should both
     * see a working map rather than a blank one.
     */
    val chosen: StateFlow<BasemapMode?> = settings.basemap.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    fun select(mode: BasemapMode) {
        viewModelScope.launch { settings.setBasemap(mode) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { BasemapViewModel(container().settings) }
        }
    }
}

/**
 * What a map screen needs to draw and change its style: the style itself, and the
 * chooser that goes with it.
 *
 * The sheet's visibility lives here too. Every map screen offers the same chooser
 * behind the same button, and a boolean each would be four copies of one idea.
 */
class BasemapChoice internal constructor(
    /** The style to draw, with "not chosen" and "no longer available" resolved. */
    val mode: BasemapMode,
    /** Whether an offline vector map is loaded, which decides if that style can be picked. */
    val hasVectorMap: Boolean,
    private val sheet: MutableState<Boolean>,
    private val onSelect: (BasemapMode) -> Unit
) {
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
 * Reads the stored style and resolves it against what is actually available.
 *
 * @param hasVectorMap whether this screen has an imported vector map open. It is
 *   what makes an unset preference default to the offline map on a device that has
 *   one — a user who went to the trouble of importing a map wants to see it — and
 *   what falls back to street tiles when the map file has since been deleted.
 */
@Composable
fun rememberBasemapChoice(
    hasVectorMap: Boolean,
    viewModel: BasemapViewModel = viewModel(factory = BasemapViewModel.Factory)
): BasemapChoice {
    val chosen by viewModel.chosen.collectAsStateWithLifecycle()
    // Kept across rotation: someone who turned the phone sideways to see more of
    // the ground did not mean to close the chooser they had just opened.
    val sheet = rememberSaveable { mutableStateOf(false) }
    return BasemapChoice(
        mode = BasemapMode.resolve(chosen, hasVectorMap),
        hasVectorMap = hasVectorMap,
        sheet = sheet,
        onSelect = viewModel::select
    )
}
