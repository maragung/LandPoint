package com.landpoint.app.ui.preview

import com.landpoint.app.data.model.CornerPoint

/**
 * Holds the unsaved draft for the preview screen.
 *
 * Preview is a separate navigation destination from the editor, and navigation
 * arguments cannot carry photo files — so the draft travels here instead. The
 * editor writes before navigating; preview only reads. Cleared on save.
 */
object PreviewDraftStore {

    data class Draft(
        val name: String = "",
        val corners: List<CornerPoint> = emptyList(),
        val photoCounts: Map<String, Int> = emptyMap()
    )

    @Volatile
    var draft: Draft = Draft()
        private set

    fun set(name: String, corners: List<CornerPoint>, photoCounts: Map<String, Int>) {
        draft = Draft(name, corners, photoCounts)
    }

    fun clear() {
        draft = Draft()
    }
}
