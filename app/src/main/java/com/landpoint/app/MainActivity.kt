package com.landpoint.app

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
 * How long the splash may be held while the settings that decide the first screen
 * are read off disk. Generous enough to cover a cold DataStore read on a slow
 * phone, short enough that a read which never answers costs a blink rather than a
 * stare at the logo.
 */
private const val STARTUP_HOLD_MS = 700L

/**
 * A [FragmentActivity] rather than a plain ComponentActivity because the app lock
 * uses BiometricPrompt, which hosts itself in a fragment. FragmentActivity is a
 * ComponentActivity, so Compose and the ViewModel wiring are unaffected.
 */
class MainActivity : FragmentActivity() {

    /**
     * True once the lock and introduction settings have arrived — the two answers
     * that decide what the first screen is. Written from the composition, read
     * from the splash screen's pre-draw check; both are the main thread.
     */
    private var startupReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate, because this is what puts Theme.LandPoint in
        // place of the splash theme the launcher started, and the window is built
        // from the theme in force.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Hold the splash across the frames DataStore needs, so the launcher icon
        // gives way to the lock, the introduction or the records — never to an
        // empty coloured window in between. Time-limited on purpose: a read that
        // never answers must not leave the app staring at its own logo.
        val holdUntil = SystemClock.uptimeMillis() + STARTUP_HOLD_MS
        splash.setKeepOnScreenCondition {
            !startupReady && SystemClock.uptimeMillis() < holdUntil
        }

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

            // Both answers in hand: there is something real to draw, so the
            // splash can go. Composition and layout still run while it is held —
            // only the draw is postponed — which is what lets this effect be the
            // thing that releases it.
            val settled = privacy != null && onboarded != null
            LaunchedEffect(settled) { if (settled) startupReady = true }

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
