package com.landpoint.app

import com.landpoint.app.data.LandRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Deletes that have been asked for but not carried out yet, so that Undo has
 * something to undo.
 *
 * [LandRepository.delete] erases the photo files from disk before it removes the
 * row. That is the right order — it cannot leave orphaned images — but it means
 * "delete now, restore if the user objects" is not possible: the row could come
 * back, the photographs could not. So the delete is *deferred* instead. For
 * [WINDOW_MS] the ids sit here, the list filters them out so they look gone, and
 * nothing on disk has been touched. Undo simply cancels the timer.
 *
 * The scope is the application's, not a ViewModel's, on purpose. Deleting a land
 * from its detail screen closes that screen immediately, and a viewModelScope
 * would be cancelled part-way through — after the photos were unlinked but
 * before the row went. Here the work finishes wherever the user navigates.
 *
 * **The failure mode is deliberately one-sided.** If the process is killed
 * inside the window, the delete simply never happens and the land is still
 * there. For an app that holds the only copy of someone's land records, "the
 * delete did not stick" is a far better outcome than "the record is gone".
 */
class PendingDeletes(
    private val repository: LandRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val _ids = MutableStateFlow<Set<String>>(emptySet())

    /** Ids that should be hidden from every list until the window closes. */
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    private var timer: Job? = null

    /**
     * Hides [batch] and deletes it once the undo window closes.
     *
     * Any batch already waiting is committed first rather than merged, so Undo
     * always means "undo the delete I just did" and never silently resurrects an
     * older one.
     */
    fun schedule(batch: Set<String>) {
        if (batch.isEmpty()) return
        commitNow()
        _ids.value = batch
        timer = scope.launch {
            delay(WINDOW_MS)
            commit(batch)
        }
    }

    /** Cancels the pending delete. Nothing on disk was touched, so this is total. */
    fun undo() {
        timer?.cancel()
        timer = null
        _ids.value = emptySet()
    }

    /**
     * Carries out any waiting delete immediately.
     *
     * A backup reads the database directly rather than the filtered list, so it
     * would otherwise write out records the user has already deleted.
     */
    fun commitNow() {
        val batch = _ids.value
        timer?.cancel()
        timer = null
        if (batch.isEmpty()) return
        scope.launch { commit(batch) }
    }

    /**
     * Carries out any waiting delete and waits for it to finish.
     *
     * A backup or export reads the database directly rather than the filtered
     * list, so without this it would write out records the user has already
     * deleted. Returning only once the delete is done is the whole point —
     * firing it off and starting the backup immediately would be a race.
     */
    suspend fun flush() {
        val batch = _ids.value
        timer?.cancel()
        timer = null
        if (batch.isEmpty()) return
        commit(batch)
    }

    private suspend fun commit(batch: Set<String>) {
        try {
            repository.deleteMany(batch.toList())
        } finally {
            // Cleared either way: leaving ids hidden after a failed delete would
            // make records vanish from the list while still being in the file.
            _ids.update { it - batch }
        }
    }

    companion object {
        /**
         * Comfortably longer than a short snackbar (about four seconds), so the
         * Undo button is never taken away before the delete it undoes happens.
         */
        const val WINDOW_MS = 5_000L
    }
}
