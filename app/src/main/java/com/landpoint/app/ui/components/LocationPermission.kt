package com.landpoint.app.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.landpoint.app.R
import com.landpoint.app.location.hasLocationPermission

/**
 * Whether location permission is granted, whether the system will still ask, and
 * a trigger for the request.
 *
 * The second of those is why this is not a one-line `checkSelfPermission`. Once
 * the user has refused twice — or once with "don't allow" on Android 11 and up —
 * launching the request does nothing at all: no dialog, an immediate denial, and
 * a button that visibly does nothing. From then on the only way through is the
 * system settings page, so the caller has to be told to offer that instead.
 */
@Composable
fun rememberLocationPermission(onGranted: () -> Unit = {}): LocationPermissionState {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val granted0 = context.hasLocationPermission()
    var granted by remember(granted0) { mutableStateOf(granted0) }
    // Survives rotation on purpose: it is what separates "never asked" from
    // "asked and refused for good", and the platform cannot tell us apart.
    var asked by rememberSaveable { mutableStateOf(false) }
    val latestOnGranted by rememberUpdatedState(onGranted)
    val lifecycleOwner = LocalLifecycleOwner.current

    // Permission granted in the system settings is granted in another app, and we
    // are told nothing about it. Coming back to the front is the only signal.
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                !granted &&
                context.hasLocationPermission()
            ) {
                granted = true
                latestOnGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        asked = true
        granted = result.values.any { it }
        if (granted) latestOnGranted()
    }

    // False both before the first ask and after a permanent refusal, which is why
    // it is only trusted once we know an ask has happened.
    val blocked = !granted && asked && activity != null &&
        !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)

    return remember(granted, blocked) {
        LocationPermissionState(
            isGranted = granted,
            isBlocked = blocked,
            request = {
                launcher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        )
    }
}

data class LocationPermissionState(
    val isGranted: Boolean,
    /** The system will not ask again; only its settings page can grant it now. */
    val isBlocked: Boolean = false,
    val request: () -> Unit
)

/**
 * Opens this app's page in the system settings, where a refused permission can
 * still be switched on.
 *
 * Wrapped because the intent is refused on a few manufacturer builds, and a
 * crash on the way to fixing a permission is a poor trade for a button.
 */
fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Says why the app wants location, and offers the one action that can still help.
 *
 * [blocked] changes both halves: there is no point repeating an ask the system
 * will swallow, and no point hiding the fact that the switch now lives somewhere
 * else.
 */
@Composable
fun LocationPermissionCard(
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
    blocked: Boolean = false
) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.LocationOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                stringResource(R.string.permission_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                stringResource(
                    if (blocked) R.string.permission_blocked_body else R.string.permission_body
                ),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Button(onClick = { if (blocked) openAppSettings(context) else onRequest() }) {
                Text(
                    stringResource(
                        if (blocked) R.string.permission_open_settings
                        else R.string.permission_grant
                    )
                )
            }
        }
    }
}

/**
 * The Activity is not [LocalContext] here — the theme wraps it to switch language
 * — so unwrap until it turns up. Null where there is none, as in a preview.
 */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
