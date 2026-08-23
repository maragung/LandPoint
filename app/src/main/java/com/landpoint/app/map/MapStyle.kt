package com.landpoint.app.map

/**
 * A style document, ready to hand to MapLibre.
 *
 * [key] exists so a change of style can be noticed without comparing two forty-kilobyte
 * strings on every recomposition. It identifies the document, not the file: the same
 * provider in light and dark themes, or pointed at two different archives, are
 * different keys.
 *
 * The zoom limits ride along because they belong to the source rather than to the
 * screen. A user at street-level zoom who switches to the relief map has to be
 * brought back to a depth that source publishes, or they are left looking at the
 * blank grey of tiles that were never made.
 */
data class MapStyle(
    val key: String,
    val json: String,
    val minZoom: Double,
    val maxZoom: Double
)
