package com.landpoint.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.ui.LandPointNavHost
import com.landpoint.app.ui.onboarding.OnboardingScreen
import com.landpoint.app.ui.security.AppLockGate
import com.landpoint.app.ui.theme.LandPointTheme
import com.landpoint.app.util.Localization
import kotlinx.coroutines.launch

/**
 * A [FragmentActivity] rather than a plain ComponentActivity because the app lock
 * uses BiometricPrompt, which hosts itself in a fragment. FragmentActivity is a
 * ComponentActivity, so Compose and the ViewModel wiring are unaffected.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = (application as LandPointApp).container.settings

        setContent {
            val darkMode by settings.darkMode.collectAsStateWithLifecycle(
                initialValue = SettingsRepository.DarkMode.SYSTEM
            )
            val language by settings.language.collectAsStateWithLifecycle(
                initialValue = SettingsRepository.Language.SYSTEM
            )
            val dynamicColor by settings.dynamicColor.collectAsStateWithLifecycle(
                initialValue = false
            )
            // Null until read from disk, and deliberately not defaulted: see below.
            val privacy by settings.privacy.collectAsStateWithLifecycle(initialValue = null)
            // Also null until read, for the opposite reason: defaulting to "not
            // yet seen" would flash the introduction at every existing user on
            // every cold start.
            val onboarded by settings.onboardingDone.collectAsStateWithLifecycle(
                initialValue = null
            )

            DisposableEffect(privacy?.secureScreen) {
                if (privacy?.secureScreen == true) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                onDispose { }
            }

            // Keep the non-composable side (PDF labels, share text, snackbars) in step.
            Localization.setActive(language)

            // Swapping the context under the composition re-resolves every
            // stringResource without recreating the Activity.
            val base = LocalContext.current
            val localized = remember(language, base) { Localization.wrap(base, language) }

            CompositionLocalProvider(LocalContext provides localized) {
                LandPointTheme(darkMode = darkMode, dynamicColor = dynamicColor) {
                    // Nothing is drawn until the lock setting is known. Defaulting
                    // to "unlocked" for the frame or two DataStore takes would show
                    // the land map to whoever is holding the phone — which is the
                    // one thing the lock exists to prevent.
                    privacy?.let { flags ->
                        AppLockGate(enabled = flags.appLock) {
                            // Inside the gate, not before it: an introduction is
                            // no reason to show what the lock is there to keep
                            // shut, and on a first run there is nothing behind it
                            // to unlock anyway.
                            when (onboarded) {
                                null -> Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    content = {}
                                )

                                false -> OnboardingScreen(
                                    onFinish = {
                                        lifecycleScope.launch {
                                            settings.setOnboardingDone(true)
                                        }
                                    }
                                )

                                else -> LandPointNavHost()
                            }
                        }
                    } ?: Surface(modifier = Modifier.fillMaxSize(), content = {})
                }
            }
        }
    }
}
