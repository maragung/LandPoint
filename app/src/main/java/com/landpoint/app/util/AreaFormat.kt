package com.landpoint.app.util

import com.landpoint.app.data.SettingsRepository

/**
 * Areas are always stored in square metres. This turns one into the number the
 * user picked in settings; the unit suffix itself comes from
 * `AreaUnit.valueRes` so it can be translated.
 *
 * Follows the app's language rather than the device's, so the separator matches
 * the words printed next to it. Exports that are meant to be parsed again write
 * the raw double instead of coming through here.
 */
object AreaFormat {

    fun value(sqm: Double, unit: SettingsRepository.AreaUnit): String =
        "%.${unit.decimals}f".format(Localization.numberLocale(), sqm / unit.sqmPerUnit)
}
