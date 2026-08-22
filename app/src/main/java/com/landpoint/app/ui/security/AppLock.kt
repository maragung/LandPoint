package com.landpoint.app.ui.security

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.landpoint.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the person holding the phone has been confirmed since the app was last
 * brought to the front.
 *
 * Deliberately outside the composition and outside any ViewModel: a rotation must
 * not count as re-entering the app, and the Activity being recreated must not
 * throw away an unlock the user just performed. It is also never persisted — an
 * unlock lasts for one visit, not until the next reboot.
 */
object AppLockState {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun unlock() {
        _unlocked.value = true
    }

    fun lock() {
        _unlocked.value = false
    }
}

/**
 * Can this phone actually confirm who is holding it?
 *
 * Asked before the setting is offered, because a switch that promises a lock the
 * device cannot enforce is worse than no switch: the user believes their records
 * are behind a lock that never appears.
 */
fun deviceCanAuthenticate(context: Context): Boolean {
    val allowed = BiometricManager.from(context)
        .canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS
    if (allowed) return true
    // Below API 30 the authenticator constants cannot ask about the screen lock,
    // so a phone with only a PIN answers "no biometrics" — the keyguard can still
    // confirm it, and that is what the prompt will fall back to.
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
}

/**
 * Shows [content] only once the user has been confirmed, while [enabled] is on.
 *
 * The prompt appears by itself on each fresh visit; after a cancel or a failure
 * the way back in is an explicit button, so a mistyped PIN does not trap the user
 * in a dialog that keeps reopening.
 */
@Composable
fun AppLockGate(enabled: Boolean, content: @Composable () -> Unit) {
    val unlocked by AppLockState.unlocked.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findFragmentActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    var promptShowing by remember { mutableStateOf(false) }

    // Registered whether or not the gate is currently closed: leaving the app is
    // what re-arms the lock, and by then the locked branch is long gone.
    DisposableEffect(lifecycleOwner, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            // Not ON_PAUSE: the prompt itself pauses us, as does a notification
            // shade pull, and re-locking there would fight the user.
            if (event == Lifecycle.Event.ON_STOP && enabled && !promptShowing) {
                AppLockState.lock()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A context with no FragmentActivity behind it cannot host a prompt. Refusing
    // to draw would lock the user out of their own records with no way through,
    // so the gate opens rather than becoming a wall.
    if (!enabled || unlocked || activity == null) {
        content()
        return
    }

    var failure by remember { mutableStateOf<String?>(null) }
    val title = stringResource(R.string.lock_prompt_title)
    val subtitle = stringResource(R.string.lock_prompt_subtitle)

    val authenticate: () -> Unit = {
        if (!promptShowing) {
            promptShowing = true
            failure = null
            promptFor(activity, title, subtitle) { ok, error ->
                promptShowing = false
                if (ok) AppLockState.unlock() else failure = error
            }
        }
    }

    // Fires again on every re-lock, because the locked branch is composed afresh
    // each time it closes.
    LaunchedEffect(Unit) { authenticate() }

    LockScreen(failure = failure, onUnlock = authenticate)
}

/** The wall itself: no land data, no map, nothing to read over a shoulder. */
@Composable
private fun LockScreen(failure: String?, onUnlock: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                failure ?: stringResource(R.string.lock_message),
                style = MaterialTheme.typography.bodyMedium,
                color = if (failure != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onUnlock) { Text(stringResource(R.string.lock_unlock)) }
        }
    }
}

/**
 * Fingerprint, face, or the screen lock — whichever the phone has. No negative
 * button is set, and none may be: the framework supplies the cancel affordance
 * itself once a device credential is allowed.
 */
private fun promptFor(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    onResult: (Boolean, String?) -> Unit
) {
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onResult(true, null)
        }

        override fun onAuthenticationError(code: Int, message: CharSequence) {
            // The framework's wording is already localised and says more than we
            // could ("Too many attempts", "No fingerprint enrolled").
            onResult(false, message.toString())
        }
    }
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setAllowedAuthenticators(authenticators())
            } else {
                // API 28-29 cannot combine the two in one authenticator set.
                @Suppress("DEPRECATION")
                setDeviceCredentialAllowed(true)
            }
        }
        .build()

    runCatching {
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            .authenticate(info)
    }.onFailure { onResult(false, it.message) }
}

private fun authenticators(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // WEAK rather than STRONG: this gate guards a view of the user's own
        // records, not a cryptographic key, and STRONG needlessly excludes the
        // face and iris sensors on plenty of phones.
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_WEAK
    }

/**
 * The Activity is not [LocalContext] here — the theme wraps it to switch language
 * — so unwrap until it turns up.
 */
private fun Context.findFragmentActivity(): FragmentActivity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is FragmentActivity) return context
        context = context.baseContext
    }
    return null
}
