package com.landpoint.app.util

import kotlin.math.roundToInt

/**
 * The colour transform that turns a daylight map into a night one.
 *
 * The tiles themselves are drawn for daylight — a beige ground, green parks, blue
 * water, dark grey lettering — and there is one map to work with, whether it came
 * from the network or from an imported vector file. So a dark map means recolouring
 * what arrives.
 *
 * Plain inversion, which is what osmdroid offers, gets the brightness right and the
 * colours badly wrong: a green field comes out magenta and water comes out orange,
 * which on a map of someone's land is worse than a bright screen. Inverting and then
 * turning the hue through half a circle undoes that second effect while keeping the
 * first, so parks stay green, water stays blue, and the lettering that was near
 * black is now near white.
 *
 * Kept here as plain arithmetic rather than as an Android `ColorMatrix` so the
 * choice can be checked against real tile colours in a unit test — the framework
 * does exactly this multiplication, and getting a sign wrong in it is otherwise
 * something you only see on a phone at night.
 */
object NightTiles {

    /** A third and two thirds: hue turned half a circle, on top of an inversion. */
    private const val KEEP = 1f / 3f
    private const val SWAP = -2f / 3f

    /**
     * Row-major 4×5, the shape `ColorMatrix` takes: three rows of channel weights
     * with a 0–255 translation in the last column, then alpha left alone.
     */
    val MATRIX: FloatArray = floatArrayOf(
        KEEP, SWAP, SWAP, 0f, 255f,
        SWAP, KEEP, SWAP, 0f, 255f,
        SWAP, SWAP, KEEP, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )

    /**
     * What [MATRIX] does to one pixel, the way the framework would do it: weights
     * applied to the channels, the translation added, and the result clamped.
     */
    fun shade(argb: Int): Int {
        val alpha = (argb ushr 24) and 0xFF
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        fun channel(row: Int): Int {
            val at = row * 5
            val value = MATRIX[at] * red +
                    MATRIX[at + 1] * green +
                    MATRIX[at + 2] * blue +
                    MATRIX[at + 3] * alpha +
                    MATRIX[at + 4]
            return value.roundToInt().coerceIn(0, 255)
        }
        return (alpha shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }
}
