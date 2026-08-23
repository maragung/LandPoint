package com.landpoint.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Asks for the notification permission, at most once, when something is about to
 * post one.
 *
 * Called at the moment a boundary walk starts rather than on the way into the app.
 * The walk's notification is the only one LandPoint posts, and it is the only way to
 * finish a walk without unlocking the phone — so there is something concrete to
 * explain, which is exactly when people say yes.
 *
 * The answer is not waited for and nothing depends on it: a refused notification
 * still leaves the walk running, the editor's own Finish button, and the service
 * visible in Android's task manager. Below API 33 there is no permission to ask for
 * and this does nothing at all.
 *
 * @return a function to call when a notification is about to be needed.
 */
@Composable
fun rememberNotificationPermission(): () -> Unit {
    val context = LocalContext.current
    // Saved, so turning the phone does not turn one ask into two.
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { asked = true }

    return remember(context, launcher) {
        {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            if (!granted && !asked) {
                asked = true
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
