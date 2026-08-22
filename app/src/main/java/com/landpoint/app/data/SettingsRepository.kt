package com.landpoint.app.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.landpoint.app.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DARK_MODE = stringPreferencesKey("dark_mode")        // system | light | dark
        val MEASUREMENT_UNITS = stringPreferencesKey("units")     // metric | imperial
        val COORD_FORMAT = stringPreferencesKey("coord_format")   // decimal | dms
        val LANGUAGE = stringPreferencesKey("language")           // system | en | in
        val AREA_UNIT = stringPreferencesKey("area_unit")         // AreaUnit.key
        val BASEMAP = stringPreferencesKey("basemap")             // BasemapMode.key
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val SECURE_SCREEN = booleanPreferencesKey("secure_screen")
        val STRIP_PHOTO_LOCATION = booleanPreferencesKey("strip_photo_location")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    /**
     * Indonesian resources live in `values-in` and the locale tag is "in", not
     * "id" — Java's legacy code for the language, which is what Android's
     * resource resolver matches on.
     */
    enum class Language(val tag: String?, @StringRes val labelRes: Int) {
        SYSTEM(null, R.string.language_system),
        ENGLISH("en", R.string.language_english),
        INDONESIAN("in", R.string.language_indonesian);

        val locale: Locale? get() = tag?.let { Locale(it) }
    }

    enum class DarkMode(@StringRes val labelRes: Int) {
        SYSTEM(R.string.dark_mode_system),
        LIGHT(R.string.dark_mode_light),
        DARK(R.string.dark_mode_dark)
    }

    enum class Units(@StringRes val labelRes: Int) {
        METRIC(R.string.units_metric),
        IMPERIAL(R.string.units_imperial)
    }

    enum class CoordFormat(@StringRes val labelRes: Int) {
        DECIMAL(R.string.coord_format_decimal),
        DMS(R.string.coord_format_dms)
    }

    /**
     * Customary land units still in daily use in Indonesia, alongside the metric
     * ones. [sqmPerUnit] converts a stored area — always square metres — into the
     * chosen unit.
     *
     * [key] is what gets persisted, so the stored value survives reordering or
     * renaming of the constants. The first three keys match what earlier versions
     * wrote, so an existing preference keeps working.
     */
    enum class AreaUnit(
        val key: String,
        @StringRes val labelRes: Int,
        @StringRes val valueRes: Int,
        val sqmPerUnit: Double,
        val decimals: Int
    ) {
        SQM("sqm", R.string.area_unit_sqm, R.string.value_area_sqm, 1.0, 0),
        ARE("are", R.string.area_unit_are, R.string.value_area_are, 100.0, 2),
        HECTARE("hectare", R.string.area_unit_hectare, R.string.value_area_hectare, 10_000.0, 3),
        /** Tumbak, known as bata in much of West Java. */
        TUMBAK("tumbak", R.string.area_unit_tumbak, R.string.value_area_tumbak, 14.0, 1),
        /** Ubin/ru, the Central and East Java equivalent — 14.0625 m², not 14. */
        UBIN("ubin", R.string.area_unit_ubin, R.string.value_area_ubin, 14.0625, 1),
        /** Rante, used on plantation land in Sumatra — a 20 m square. */
        RANTE("rante", R.string.area_unit_rante, R.string.value_area_rante, 400.0, 2),
        /**
         * Bahu (bau), the colonial-era Javanese unit, standardised at 7096.5 m².
         * Often quoted as "500 ubin", but that uses a historical ubin of
         * 14.193 m² — not the 14.0625 m² [UBIN] above, so do not derive one from
         * the other.
         */
        BAHU("bahu", R.string.area_unit_bahu, R.string.value_area_bahu, 7096.5, 3),
        ACRE("acre", R.string.area_unit_acre, R.string.value_area_acre, 4046.8564224, 3);

        companion object {
            /** Unknown or missing keys fall back to square metres. */
            fun fromKey(key: String?): AreaUnit = entries.firstOrNull { it.key == key } ?: SQM
        }
    }

    val language: Flow<Language> = context.settingsDataStore.data.map {
        when (it[Keys.LANGUAGE]) {
            "en" -> Language.ENGLISH
            "in" -> Language.INDONESIAN
            else -> Language.SYSTEM
        }
    }

    val darkMode: Flow<DarkMode> = context.settingsDataStore.data.map {
        when (it[Keys.DARK_MODE]) {
            "light" -> DarkMode.LIGHT
            "dark" -> DarkMode.DARK
            else -> DarkMode.SYSTEM
        }
    }

    val units: Flow<Units> = context.settingsDataStore.data.map {
        if (it[Keys.MEASUREMENT_UNITS] == "imperial") Units.IMPERIAL else Units.METRIC
    }

    val coordFormat: Flow<CoordFormat> = context.settingsDataStore.data.map {
        if (it[Keys.COORD_FORMAT] == "dms") CoordFormat.DMS else CoordFormat.DECIMAL
    }

    val areaUnit: Flow<AreaUnit> = context.settingsDataStore.data.map {
        AreaUnit.fromKey(it[Keys.AREA_UNIT])
    }

    /**
     * What the maps draw underneath — street, satellite, terrain, or the map the
     * user imported. See [BasemapMode].
     *
     * Null means never chosen, and is passed on as null rather than defaulted
     * here: what "unchosen" should draw depends on whether an offline map has
     * been imported, which this repository has no way of knowing.
     * [BasemapMode.resolve] is where that decision lives.
     */
    val basemap: Flow<BasemapMode?> = context.settingsDataStore.data.map {
        BasemapMode.fromKey(it[Keys.BASEMAP])
    }

    /**
     * Off by default: the green palette is the app's identity, so wallpaper
     * colours are something a user opts into rather than the other way round.
     */
    val dynamicColor: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.DYNAMIC_COLOR] ?: false
    }

    /**
     * The privacy switches, delivered as one value.
     *
     * They travel together because `combine` accepts five flows and the settings
     * screen already spends all five; one flow for the group spares that screen
     * another layer of nesting every time a switch is added here.
     */
    data class Privacy(
        val appLock: Boolean = false,
        val secureScreen: Boolean = false,
        val stripPhotoLocation: Boolean = true
    )

    /**
     * [Privacy.stripPhotoLocation] is the one that defaults to on. A camera
     * capture already carries its coordinates burned into the picture where the
     * recipient can read them, so the EXIF copy tells a human nothing new while
     * handing a precise fix to every app the photo passes through.
     *
     * The other two default to off: a lock the user did not ask for is a lock
     * they will be surprised by, and screenshots are how people share a plot
     * with family.
     */
    val privacy: Flow<Privacy> = context.settingsDataStore.data.map {
        Privacy(
            appLock = it[Keys.APP_LOCK] ?: false,
            secureScreen = it[Keys.SECURE_SCREEN] ?: false,
            stripPhotoLocation = it[Keys.STRIP_PHOTO_LOCATION] ?: true
        )
    }

    /**
     * Whether the introduction has been through once.
     *
     * Absent means a first run, which is what shows it — so this flow must never
     * be given a default of true anywhere it is collected, or the one screen that
     * explains where the records are kept is the one nobody sees.
     */
    val onboardingDone: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.ONBOARDING_DONE] ?: false
    }

    suspend fun setOnboardingDone(done: Boolean) = context.settingsDataStore.edit {
        it[Keys.ONBOARDING_DONE] = done
    }

    suspend fun setLanguage(language: Language) = context.settingsDataStore.edit {
        it[Keys.LANGUAGE] = language.tag ?: "system"
    }

    suspend fun setDarkMode(mode: DarkMode) = context.settingsDataStore.edit {
        it[Keys.DARK_MODE] = when (mode) {
            DarkMode.SYSTEM -> "system"
            DarkMode.LIGHT -> "light"
            DarkMode.DARK -> "dark"
        }
    }

    suspend fun setUnits(units: Units) = context.settingsDataStore.edit {
        it[Keys.MEASUREMENT_UNITS] = if (units == Units.IMPERIAL) "imperial" else "metric"
    }

    suspend fun setCoordFormat(format: CoordFormat) = context.settingsDataStore.edit {
        it[Keys.COORD_FORMAT] = if (format == CoordFormat.DMS) "dms" else "decimal"
    }

    suspend fun setAreaUnit(unit: AreaUnit) = context.settingsDataStore.edit {
        it[Keys.AREA_UNIT] = unit.key
    }

    suspend fun setBasemap(mode: BasemapMode) = context.settingsDataStore.edit {
        it[Keys.BASEMAP] = mode.key
    }

    suspend fun setDynamicColor(enabled: Boolean) = context.settingsDataStore.edit {
        it[Keys.DYNAMIC_COLOR] = enabled
    }

    suspend fun setAppLock(enabled: Boolean) = context.settingsDataStore.edit {
        it[Keys.APP_LOCK] = enabled
    }

    suspend fun setSecureScreen(enabled: Boolean) = context.settingsDataStore.edit {
        it[Keys.SECURE_SCREEN] = enabled
    }

    suspend fun setStripPhotoLocation(enabled: Boolean) = context.settingsDataStore.edit {
        it[Keys.STRIP_PHOTO_LOCATION] = enabled
    }
}
