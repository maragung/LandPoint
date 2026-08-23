package com.landpoint.app.util

/**
 * A whole number, grouped for reading.
 *
 * Tile counts run to six figures and are read against a limit of the same size, so
 * "120,000" and "12,000" have to be tellable apart at a glance. Grouped in the
 * user's own convention — the same one [AreaFormat] measures in — because the
 * separator differs by language and a number punctuated the wrong way reads as a
 * different number entirely.
 */
fun formatCount(value: Long): String = "%,d".format(Localization.numberLocale(), value)
