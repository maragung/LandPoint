package com.landpoint.app.util

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import com.landpoint.app.data.SettingsRepository

/**
 * In-app language override.
 *
 * The app deliberately does *not* recreate the Activity to change language.
 * Instead it hands Compose a [Context] whose `getResources()` resolves in the
 * chosen locale while every other call still delegates to the real Activity —
 * so `startActivity`, the share sheet and the file pickers keep working.
 *
 * [active] mirrors the persisted setting for the handful of places that build
 * user-facing text outside composition (PDF export, share text, snackbars).
 */
object Localization {

    @Volatile
    private var active: SettingsRepository.Language = SettingsRepository.Language.SYSTEM

    fun setActive(language: SettingsRepository.Language) {
        active = language
    }

    /**
     * Locale that user-facing numbers should follow.
     *
     * The in-app choice, not the device's: a phone set to Indonesian with the
     * app set to English was rendering English labels beside comma decimals.
     * Machine-read output (CSV, GPX, KML) never comes through here — it writes
     * raw `Double.toString`, which is always dot-separated.
     */
    fun numberLocale(): java.util.Locale =
        active.locale ?: java.util.Locale.getDefault()

    fun wrap(
        context: Context,
        language: SettingsRepository.Language = active
    ): Context {
        val locale = language.locale ?: return context
        val config = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
        return LocalizedContext(context, context.createConfigurationContext(config).resources)
    }
}

private class LocalizedContext(
    base: Context,
    private val localized: Resources
) : ContextWrapper(base) {
    override fun getResources(): Resources = localized
    override fun getAssets(): AssetManager = localized.assets
}
