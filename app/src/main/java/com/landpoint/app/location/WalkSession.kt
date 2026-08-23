package com.landpoint.app.location

import com.landpoint.app.util.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A walk as it stands: what has been kept, how far it went, and whether it is still on. */
data class WalkState(
    val running: Boolean = false,
    val track: List<GeoPoint> = emptyList(),
    val progress: WalkProgress = WalkProgress(points = 0, walkedM = 0.0, areaSqm = null),
    /**
     * True when the walk ended because recording could not start at all, as opposed
     * to ending with too little recorded. The screen has to tell those apart: one is
     * "stand somewhere more open", the other is "Android would not let us start".
     */
    val failed: Boolean = false
)

/**
 * The one boundary walk this process is recording.
 *
 * A walk has to outlive the screen that started it. The user taps "walk the
 * boundary", puts the phone in a pocket and walks a field edge for twenty minutes;
 * the screen locks, Android may stop the activity, and the recording is still
 * expected to be there when the phone comes back out. State kept in a ViewModel
 * survives rotation but is exactly what a screen leaving the foreground can lose,
 * so the track lives here instead — written by [WalkTrackService], which holds the
 * only location subscription, and read by whichever screen is showing.
 *
 * Deliberately not persisted to disk. A walk is minutes long and tied to a
 * foreground service the system very rarely kills; writing every vertex to storage
 * would trade a real cost for an unlikely rescue, and a half-restored track that
 * silently omits the part recorded before the process died is worse evidence of a
 * boundary than no track at all. If the process does die, the walk is over and the
 * screen shows it as over.
 *
 * The filtering is [WalkTrack]'s and happens here, at the single point where a fix
 * becomes a vertex — so the same accuracy and spacing gates apply no matter who is
 * feeding fixes in.
 */
object WalkSession {

    private val _state = MutableStateFlow(WalkState())
    val state: StateFlow<WalkState> = _state.asStateFlow()

    /** True while a walk is being recorded, for anything that only needs the flag. */
    val isRunning: Boolean get() = _state.value.running

    /**
     * Discards any previous walk and starts a new one.
     *
     * Starting fresh rather than appending is the honest choice: two walks joined
     * end to end would draw an edge between wherever the first stopped and wherever
     * the second began, and that edge was never walked.
     */
    @Synchronized
    fun begin() {
        _state.value = WalkState(running = true)
    }

    /**
     * Offers a fix as the next vertex.
     *
     * @return true when it was kept, which is the signal worth acting on — a
     *   notification only needs redrawing when the figures it shows have changed.
     */
    @Synchronized
    fun offer(sample: GeoSample): Boolean {
        val current = _state.value
        if (!current.running) return false
        if (!WalkTrack.accept(current.track.lastOrNull(), sample)) return false

        val track = current.track + GeoPoint(
            latitude = sample.latitude,
            longitude = sample.longitude,
            accuracyM = sample.accuracy?.toDouble()
        )
        _state.value = current.copy(track = track, progress = WalkTrack.progressOf(track))
        return true
    }

    /**
     * Stops recording and hands back what was kept, unclosed.
     *
     * Closing the ring is [WalkTrack.close]'s job and stays with the caller that
     * will show the result, because it is a judgement about the finished shape
     * rather than part of recording it.
     *
     * Idempotent: the service, the screen and the notification's own stop button can
     * all end the same walk, and none of them knows which of the others got there
     * first.
     */
    /**
     * Ends the walk as never having recorded anything, because it could not start.
     *
     * Separate from [end] so the screen can say which happened. An empty track after
     * a walk that did run means the fixes were all too vague to use; an empty track
     * here means there was never a subscription to get fixes from.
     */
    @Synchronized
    fun fail() {
        _state.value = WalkState(running = false, failed = true)
    }

    @Synchronized
    fun end(): List<GeoPoint> {
        val current = _state.value
        if (current.running) _state.value = current.copy(running = false)
        return current.track
    }
}
