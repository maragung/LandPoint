package com.landpoint.app.util

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A corner placed on the page, in points from the top-left of its box. */
data class SketchPoint(val x: Float, val y: Float)

/**
 * A boundary laid out ready to draw at a fixed size, north up.
 *
 * [metresPerPoint] is the scale that came out of fitting it, which is what lets
 * the drawing carry a scale bar instead of being a picture of no particular size.
 */
data class Sketch(
    val outline: List<SketchPoint>,
    val metresPerPoint: Double
)

/**
 * Fits a boundary into a box on paper.
 *
 * Separate from the PDF writer, and free of Android, for the usual reason: the
 * arithmetic that decides whether a parcel comes out the right shape is worth
 * testing, and `Canvas` cannot be tested without a device.
 *
 * The projection is flat — metres east and north of the first corner, about the
 * boundary's own mean latitude. Over a parcel that fits on a page the error is
 * far below the width of the line drawn, and a proper projection would only add
 * distortion of its own at this size.
 */
object BoundarySketch {

    private const val EARTH_RADIUS_M = 6371000.0

    /**
     * [points] fitted into [boxWidthPt] by [boxHeightPt], or null when there is
     * no shape to draw.
     *
     * Aspect ratio is preserved and the result centred, so a long thin strip comes
     * out long and thin: a sketch that quietly stretched a parcel to fill its box
     * would be worse than no sketch, because it would look like the parcel.
     */
    fun of(
        points: List<GeoPoint>,
        boxWidthPt: Float,
        boxHeightPt: Float,
        paddingPt: Float = 6f
    ): Sketch? {
        if (points.size < 3) return null
        val usableW = boxWidthPt - paddingPt * 2
        val usableH = boxHeightPt - paddingPt * 2
        if (usableW <= 0f || usableH <= 0f) return null

        val meanLat = points.sumOf { it.latitude } / points.size
        val metresPerDegree = EARTH_RADIUS_M * Math.PI / 180.0
        val metresPerDegreeLon = metresPerDegree * cos(Math.toRadians(meanLat))
        val xs = points.map { (it.longitude - points[0].longitude) * metresPerDegreeLon }
        val ys = points.map { (it.latitude - points[0].latitude) * metresPerDegree }
        if (xs.any { !it.isFinite() } || ys.any { !it.isFinite() }) return null

        val minX = xs.min()
        val maxY = ys.max()
        val spanX = xs.max() - minX
        val spanY = maxY - ys.min()
        // Every corner on the same spot: there is no outline, at any scale.
        if (spanX <= 0.0 && spanY <= 0.0) return null

        // Points per metre, whichever of the two directions runs out of room
        // first. A span of zero cannot be the one that limits it — that direction
        // simply collapses to a line down the middle.
        val scale = minOf(
            if (spanX > 0.0) usableW / spanX else Double.MAX_VALUE,
            if (spanY > 0.0) usableH / spanY else Double.MAX_VALUE
        )
        val offsetX = paddingPt + (usableW - spanX * scale) / 2.0
        val offsetY = paddingPt + (usableH - spanY * scale) / 2.0

        return Sketch(
            outline = points.indices.map { i ->
                SketchPoint(
                    x = (offsetX + (xs[i] - minX) * scale).toFloat(),
                    // Measured down from the northernmost corner, because north is
                    // up on paper and a canvas counts y downwards.
                    y = (offsetY + (maxY - ys[i]) * scale).toFloat()
                )
            },
            metresPerPoint = 1.0 / scale
        )
    }

    /**
     * The longest round number of metres that fits in [maxMetres], for a scale bar.
     *
     * 1, 2 and 5 and their powers of ten — the lengths a ruler and a reader both
     * deal in. A bar labelled 37 m is arithmetic homework; one labelled 20 m can
     * be stepped along the drawing by eye.
     */
    fun niceBarMetres(maxMetres: Double): Double {
        if (!maxMetres.isFinite() || maxMetres <= 0.0) return 0.0
        val magnitude = 10.0.pow(floor(log10(maxMetres)))
        return listOf(5.0, 2.0, 1.0)
            .map { it * magnitude }
            .firstOrNull { it <= maxMetres }
            ?: magnitude
    }
}
