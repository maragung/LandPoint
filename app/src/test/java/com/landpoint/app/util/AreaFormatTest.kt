package com.landpoint.app.util

import com.landpoint.app.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Area is a display string, so it follows the language the user picked in the
 * app rather than the device locale — a phone set to Indonesian with the app in
 * English was printing English labels beside comma decimals.
 *
 * Machine-read exports never come through here; CSV writes the raw double.
 */
class AreaFormatTest {

    @After
    fun tearDown() {
        Localization.setActive(SettingsRepository.Language.SYSTEM)
        Locale.setDefault(Locale.US)
    }

    @Test
    fun `english uses a dot separator regardless of the device locale`() {
        Locale.setDefault(Locale.GERMANY)   // a comma-decimal device
        Localization.setActive(SettingsRepository.Language.ENGLISH)

        // Hectares carry 3 decimals, so the separator is visible.
        assertEquals("0.250", AreaFormat.value(2500.0, SettingsRepository.AreaUnit.HECTARE))
    }

    @Test
    fun `indonesian uses a comma separator regardless of the device locale`() {
        Locale.setDefault(Locale.US)        // a dot-decimal device
        Localization.setActive(SettingsRepository.Language.INDONESIAN)

        assertEquals("0,250", AreaFormat.value(2500.0, SettingsRepository.AreaUnit.HECTARE))
    }

    @Test
    fun `following the system means following the device`() {
        Locale.setDefault(Locale.GERMANY)
        Localization.setActive(SettingsRepository.Language.SYSTEM)

        assertEquals("0,250", AreaFormat.value(2500.0, SettingsRepository.AreaUnit.HECTARE))
    }

    @Test
    fun `square metres are whole numbers`() {
        Localization.setActive(SettingsRepository.Language.ENGLISH)

        assertEquals("2500", AreaFormat.value(2500.4, SettingsRepository.AreaUnit.SQM))
    }

    @Test
    fun `hectares convert from square metres`() {
        Localization.setActive(SettingsRepository.Language.ENGLISH)

        // 1 ha == 10,000 m²
        assertEquals("1.000", AreaFormat.value(10_000.0, SettingsRepository.AreaUnit.HECTARE))
    }

    /** A local unit still in daily use for garden plots in West Java. */
    @Test
    fun `tumbak converts from square metres`() {
        Localization.setActive(SettingsRepository.Language.ENGLISH)

        assertEquals("10.0", AreaFormat.value(140.0, SettingsRepository.AreaUnit.TUMBAK))
    }

    /**
     * Ubin is 14.0625 m², not 14 — it is a 3.75 m square. Rounding it to tumbak
     * would put a 500-ubin bahu out by about 30 m².
     */
    @Test
    fun `ubin is not the same size as tumbak`() {
        Localization.setActive(SettingsRepository.Language.ENGLISH)

        assertEquals("1.0", AreaFormat.value(14.0625, SettingsRepository.AreaUnit.UBIN))
        assertNotEquals(
            AreaFormat.value(1000.0, SettingsRepository.AreaUnit.UBIN),
            AreaFormat.value(1000.0, SettingsRepository.AreaUnit.TUMBAK)
        )
    }

    @Test
    fun `the other local units convert from square metres`() {
        Localization.setActive(SettingsRepository.Language.ENGLISH)

        assertEquals("1.00", AreaFormat.value(100.0, SettingsRepository.AreaUnit.ARE))
        assertEquals("1.00", AreaFormat.value(400.0, SettingsRepository.AreaUnit.RANTE))
        // 1 bahu is 7096.5 m². The "500 ubin" definition uses a historical ubin
        // of 14.193 m², not the 14.0625 m² ubin this app ships, so the two are
        // deliberately not derived from each other.
        assertEquals("1.000", AreaFormat.value(7096.5, SettingsRepository.AreaUnit.BAHU))
        assertEquals("1.000", AreaFormat.value(4046.8564224, SettingsRepository.AreaUnit.ACRE))
    }

    @Test
    fun `a zero area formats rather than failing`() {
        Localization.setActive(SettingsRepository.Language.ENGLISH)

        assertTrue(AreaFormat.value(0.0, SettingsRepository.AreaUnit.SQM).startsWith("0"))
    }

    /**
     * The stored preference is the unit's key, so someone upgrading from the
     * three-unit version must not silently have their choice reset to m².
     */
    @Test
    fun `a preference written by an older version still resolves`() {
        assertEquals(SettingsRepository.AreaUnit.SQM, SettingsRepository.AreaUnit.fromKey("sqm"))
        assertEquals(
            SettingsRepository.AreaUnit.HECTARE,
            SettingsRepository.AreaUnit.fromKey("hectare")
        )
        assertEquals(
            SettingsRepository.AreaUnit.TUMBAK,
            SettingsRepository.AreaUnit.fromKey("tumbak")
        )
    }

    /** A key from a future version, or none at all, must not crash the app. */
    @Test
    fun `an unknown or missing preference falls back to square metres`() {
        assertEquals(SettingsRepository.AreaUnit.SQM, SettingsRepository.AreaUnit.fromKey(null))
        assertEquals(
            SettingsRepository.AreaUnit.SQM,
            SettingsRepository.AreaUnit.fromKey("not-a-unit")
        )
    }

    /** Round-trips every unit, so a new one cannot be added without a key. */
    @Test
    fun `every unit has a unique key that round-trips`() {
        val keys = SettingsRepository.AreaUnit.entries.map { it.key }
        assertEquals("Keys must be unique", keys.size, keys.toSet().size)
        SettingsRepository.AreaUnit.entries.forEach {
            assertEquals(it, SettingsRepository.AreaUnit.fromKey(it.key))
        }
    }
}
