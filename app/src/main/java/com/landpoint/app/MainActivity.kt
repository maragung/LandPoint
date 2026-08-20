package com.landpoint.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.ui.LandPointNavHost
import com.landpoint.app.ui.theme.LandPointTheme
import com.landpoint.app.util.Localization

class MainActivity : ComponentActivity() {

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

            // Keep the non-composable side (PDF labels, share text, snackbars) in step.
            Localization.setActive(language)

            // Swapping the context under the composition re-resolves every
            // stringResource without recreating the Activity.
            val base = LocalContext.current
            val localized = remember(language, base) { Localization.wrap(base, language) }

            CompositionLocalProvider(LocalContext provides localized) {
                LandPointTheme(darkMode = darkMode, dynamicColor = dynamicColor) {
                    LandPointNavHost()
                }
            }
        }
    }
}
