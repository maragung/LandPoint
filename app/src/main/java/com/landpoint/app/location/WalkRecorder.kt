package com.landpoint.app.location

import android.content.Context
import com.landpoint.app.util.GeoPoint
import kotlinx.coroutines.flow.StateFlow

/**
 * Starting and stopping a boundary walk, without the caller holding a [Context].
 *
 * The real implementation runs a foreground service; a ViewModel that referred to it
 * directly would need an Android context to start one, and could not be tested off a
 * device. This is the seam: the ViewModel asks for a walk and reads its state, and
 * what actually holds the GPS open stays behind here.
 */
interface WalkRecorder {

    /** The walk in progress, or the last one, exactly as [WalkSession] holds it. */
    val state: StateFlow<WalkState>

    /**
     * Begins recording.
     *
     * @return false when the platform refused, in which case nothing is being
     *   recorded and the caller must say so rather than showing a walk in progress.
     */
    fun start(): Boolean

    /** Ends recording and returns the track as recorded, unclosed. */
    fun stop(): List<GeoPoint>
}

/** The real recorder: a foreground service, so a locked screen keeps recording. */
class ServiceWalkRecorder(private val context: Context) : WalkRecorder {

    override val state: StateFlow<WalkState> get() = WalkSession.state

    override fun start(): Boolean = WalkTrackService.start(context)

    override fun stop(): List<GeoPoint> = WalkTrackService.stop(context)
}
