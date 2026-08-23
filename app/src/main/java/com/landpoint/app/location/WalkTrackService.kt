package com.landpoint.app.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.landpoint.app.LandPointApp
import com.landpoint.app.MainActivity
import com.landpoint.app.R
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.util.AppStrings
import com.landpoint.app.util.GeoPoint
import com.landpoint.app.util.GeoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Records a boundary walk while the screen is off.
 *
 * This is the app's only continuous location tracking, and the only reason it is a
 * service. Browsing the map subscribes from the screen and stops the moment the
 * screen goes away, which is right: nobody needs the map to follow them in their
 * pocket. Walking a boundary is the opposite — the phone goes in a pocket for the
 * whole lap, and an app that stopped recording when the screen locked would produce
 * a boundary with a straight line across the half of the field it missed.
 *
 * Started only by the walk button and stopped as soon as the walk ends: from the
 * screen's own Finish, from the notification's, or by the stream ending because
 * permission was withdrawn. It holds no state of its own — the track lives in
 * [WalkSession], so a screen that comes and goes reads the same walk.
 */
class WalkTrackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The location subscription. Non-null exactly while a walk is being recorded. */
    private var recording: Job? = null

    /** The unit the user reads distances in, followed live for the notification. */
    private var imperial = false

    private val strings: AppStrings by lazy { container().strings }

    private val notifications: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's own Finish. Ending the session is what the screen is
            // watching for, so it closes the ring and shows the result even though the
            // tap happened outside the app.
            WalkSession.end()
            stopSelf()
            return START_NOT_STICKY
        }

        // The session is begun by the caller before this service is asked to start, so
        // a start with no walk waiting is a stale one — a stop that raced it, or a
        // delivery after the walk was already finished — and has nothing to record.
        if (!WalkSession.isRunning) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (recording != null) return START_NOT_STICKY

        createChannel()
        val promoted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(WalkProgress(points = 0, walkedM = 0.0, areaSqm = null)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        }.isSuccess

        // Android refuses a foreground service started from the background, and from
        // API 34 it also refuses a location-typed one without the location permission.
        // Either way there is no recording to be had, and saying so beats a walk that
        // silently keeps nothing.
        if (!promoted) {
            WalkSession.fail()
            stopSelf()
            return START_NOT_STICKY
        }

        val container = container()

        scope.launch {
            container.settings.units.collect {
                imperial = it == SettingsRepository.Units.IMPERIAL
            }
        }

        recording = scope.launch {
            container.locationProvider
                .observeLocation(INTERVAL_MS, MIN_DISTANCE_M)
                .collect { fix ->
                    val kept = WalkSession.offer(
                        GeoSample(
                            latitude = fix.latitude,
                            longitude = fix.longitude,
                            altitude = fix.altitude,
                            accuracy = fix.accuracy,
                            source = FixSource.of(fix.provider),
                            timestamp = fix.timestamp
                        )
                    )
                    // Redrawn only when a vertex was kept: the figures on it cannot
                    // have changed otherwise, and a notification rewritten once a
                    // second is a notification that flickers.
                    if (kept) show(WalkSession.state.value.progress)
                }

            // Reached only when the stream ends by itself, which it does when the
            // permission is revoked or every provider is switched off. Nothing more
            // will arrive, so the walk is over — with whatever it did record.
            WalkSession.end()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recording = null
        scope.cancel()
        // Belt and braces: whoever stopped the service may have been the platform
        // rather than the app, and a session left marked as running would leave a
        // screen showing a walk that nothing is recording.
        WalkSession.end()
        super.onDestroy()
    }

    private fun container() = (application as LandPointApp).container

    /**
     * The channel this service's notification lives on.
     *
     * Low importance on purpose: it is a status line with a button, not news. The
     * user is outdoors with the phone in a pocket, and a sound every time a fence
     * corner is recorded would be its own reason to turn the feature off.
     */
    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            strings.get(R.string.notif_walk_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = strings.get(R.string.notif_walk_channel_body)
            setShowBadge(false)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun notification(progress: WalkProgress): Notification {
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val finish = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, WalkTrackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle(strings.get(R.string.notif_walk_title))
            .setContentText(
                strings.get(
                    R.string.boundary_walk_recording,
                    GeoUtils.formatDistance(progress.walkedM, imperial)
                )
            )
            .setContentIntent(open)
            .addAction(0, strings.get(R.string.boundary_walk_stop), finish)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Updates the figures on the notification.
     *
     * Wrapped because a notification the user has switched off is refused, and a
     * refusal here must not end a walk that is otherwise going perfectly well. From
     * API 33 the walk runs whether or not the notification can be shown; the system
     * still lists the service in its own task manager.
     */
    private fun show(progress: WalkProgress) {
        runCatching { notifications.notify(NOTIFICATION_ID, notification(progress)) }
    }

    companion object {
        private const val CHANNEL_ID = "walk_track"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2

        private const val ACTION_STOP = "com.landpoint.app.action.STOP_WALK"

        /** Fixes are wanted as often as the receiver has them while a walk is on. */
        private const val INTERVAL_MS = 1_000L

        /**
         * Below [WalkTrack.MIN_STEP_M], so the platform's own filter can never drop a
         * fix that would have become a vertex — it only spares us the ones that were
         * going to be discarded anyway.
         */
        private const val MIN_DISTANCE_M = 2f

        /**
         * Begins a walk and starts recording it.
         *
         * The session is begun here, synchronously, rather than inside the service:
         * `startForegroundService` returns before the service runs, and a screen that
         * started observing in between would see a walk that was not running yet and
         * conclude it had already finished.
         *
         * @return false when Android refused the service, which it does for a start
         *   from the background. The caller has a walk that is not being recorded and
         *   needs to say so.
         */
        fun start(context: Context): Boolean {
            WalkSession.begin()
            val started = runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WalkTrackService::class.java)
                )
            }.isSuccess
            if (!started) WalkSession.fail()
            return started
        }

        /** Ends the walk and stops recording, returning the track as it stands. */
        fun stop(context: Context): List<GeoPoint> {
            // stopService rather than a stop intent: the service may already have gone
            // — the platform can stop it, and the notification's own button does — and
            // starting it again just to ask it to stop would be refused from the
            // background anyway.
            runCatching { context.stopService(Intent(context, WalkTrackService::class.java)) }
            return WalkSession.end()
        }
    }
}
