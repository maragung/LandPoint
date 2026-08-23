package com.landpoint.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The widest the bar is allowed to grow.
 *
 * A scale bar is read as a ruler, so it has to be long enough to be worth laying
 * against something, and short enough not to be the widest thing on a phone screen.
 */
private val SCALE_BAR_MAX_WIDTH = 96.dp

private const val FEET_PER_METRE = 3.28084
private const val FEET_PER_MILE = 5280.0

/**
 * A ruler for the map, drawn in Compose over the map view.
 *
 * The previous engine shipped a scale bar as a map overlay; MapLibre has none, so the
 * app draws its own. That turns out to be the better arrangement anyway: an overlay
 * had to be told a bottom inset by every screen that put something along the bottom
 * edge — the summary sheet, the corner tools — and each of those numbers was a
 * guess that went stale whenever the layout changed. A Compose overlay is placed by
 * the layout that owns the space, so there is nothing to keep in step.
 *
 * Nothing is drawn until the map has reported a scale, which keeps a bar that would
 * claim the wrong distance off the screen entirely during the first frames.
 *
 * @param imperial follows the user's unit preference, so the ruler is in the same
 *   units as every distance the app prints elsewhere. A bar in metres beside an area
 *   in acres is the kind of mismatch that makes a reader distrust both.
 */
@Composable
fun MapScaleBar(
    controller: LandMapController,
    modifier: Modifier = Modifier,
    imperial: Boolean = false
) {
    val metresPerPixel = controller.metresPerPixel
    if (metresPerPixel <= 0.0) return

    val density = LocalDensity.current
    val widest = with(density) { SCALE_BAR_MAX_WIDTH.toPx() }
    val step = scaleStep(metresPerPixel * widest, imperial) ?: return
    val barWidth: Dp = with(density) { (step.metres / metresPerPixel).toFloat().toDp() }

    Surface(
        modifier = modifier
            // One label reads better to a screen reader than a bar it cannot see,
            // and the tick marks below carry no meaning of their own.
            .clearAndSetSemantics { },
        color = MaterialTheme.colorScheme.surface.copy(alpha = SCALE_BAR_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(6.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = step.label, style = MaterialTheme.typography.labelSmall)
            val ink = MaterialTheme.colorScheme.onSurface
            Canvas(
                modifier = Modifier
                    .width(barWidth)
                    .height(SCALE_BAR_TICK_HEIGHT)
            ) {
                val thickness = SCALE_BAR_THICKNESS.toPx()
                val baseline = size.height - thickness / 2f
                val inset = thickness / 2f
                drawLine(
                    color = ink,
                    start = Offset(0f, baseline),
                    end = Offset(size.width, baseline),
                    strokeWidth = thickness
                )
                // Uprights at both ends, so the bar reads as a measured span rather
                // than as an underline belonging to the text above it.
                drawLine(ink, Offset(inset, 0f), Offset(inset, baseline), thickness)
                drawLine(
                    color = ink,
                    start = Offset(size.width - inset, 0f),
                    end = Offset(size.width - inset, baseline),
                    strokeWidth = thickness
                )
            }
        }
    }
}

private const val SCALE_BAR_ALPHA = 0.92f
private val SCALE_BAR_TICK_HEIGHT = 6.dp
private val SCALE_BAR_THICKNESS = 1.5.dp

/** A round distance and the label that names it. */
internal data class ScaleStep(val metres: Double, val label: String)

/**
 * The largest round distance that still fits in [maxMetres].
 *
 * Round means something a reader can halve and quarter in their head — 1, 2 or 5
 * times a power of ten — because the whole use of a scale bar is estimating a
 * distance that is not the bar's own length. A bar labelled "137 m" is technically
 * more of the screen used and practically useless.
 *
 * Imperial does not follow the same rule, because feet do not become miles at a
 * power of ten. Below a thousand feet the ladder is the round-feet one; above it the
 * ladder is in whole miles, which is why there is a table rather than a formula.
 *
 * @return null when the map is too zoomed in for even the smallest step to fit,
 *   which is the one case where drawing nothing is right.
 */
internal fun scaleStep(maxMetres: Double, imperial: Boolean): ScaleStep? {
    if (maxMetres <= 0.0 || maxMetres.isNaN()) return null
    return if (imperial) imperialStep(maxMetres) else metricStep(maxMetres)
}

private fun metricStep(maxMetres: Double): ScaleStep? {
    val metres = roundDown(maxMetres) ?: return null
    val label = if (metres >= 1000.0) {
        "${(metres / 1000.0).roundToInt()} km"
    } else {
        "${metres.roundToInt()} m"
    }
    return ScaleStep(metres, label)
}

private fun imperialStep(maxMetres: Double): ScaleStep? {
    val maxFeet = maxMetres * FEET_PER_METRE
    if (maxFeet < FEET_PER_MILE) {
        val feet = roundDown(maxFeet.coerceAtMost(FEET_PER_MILE - 1.0)) ?: return null
        return ScaleStep(feet / FEET_PER_METRE, "${feet.roundToInt()} ft")
    }
    val miles = roundDown(maxFeet / FEET_PER_MILE) ?: return null
    return ScaleStep(miles * FEET_PER_MILE / FEET_PER_METRE, "${miles.roundToInt()} mi")
}

/** The largest of 1, 2 or 5 times a power of ten that is not greater than [ceiling]. */
private fun roundDown(ceiling: Double): Double? {
    if (ceiling < 1.0) return null
    val magnitude = 10.0.pow(floor(log10(ceiling)))
    return when {
        ceiling >= 5.0 * magnitude -> 5.0 * magnitude
        ceiling >= 2.0 * magnitude -> 2.0 * magnitude
        else -> magnitude
    }
}
