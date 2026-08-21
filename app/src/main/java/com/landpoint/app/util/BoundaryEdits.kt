package com.landpoint.app.util

/**
 * Edits to a boundary that is already drawn, as opposed to one being captured.
 *
 * Capture has its own rules — [PolygonMath.isMeaningfulStep] for GPS and
 * [CornerDraft.acceptTap] for the map — and both are about deciding whether a
 * *new* reading is worth keeping. What is here is the other half: rearranging,
 * correcting and deleting corners that were recorded earlier, plus reading a
 * corner typed in by hand off a certificate or a surveyor's sheet.
 *
 * Every operation returns a new list and every one is index-safe: an index the
 * list does not have gives the list back unchanged, rather than throwing. The
 * callers are button handlers on a list that a walk recording can rewrite
 * underneath them, so an out-of-range index is a race to absorb, not a bug to
 * crash on.
 */
object BoundaryEdits {

    fun removeAt(points: List<GeoPoint>, index: Int): List<GeoPoint> =
        if (index !in points.indices) points
        else points.filterIndexed { i, _ -> i != index }

    fun replaceAt(points: List<GeoPoint>, index: Int, point: GeoPoint): List<GeoPoint> =
        if (index !in points.indices) points
        else points.mapIndexed { i, existing -> if (i == index) point else existing }

    /** Inserts before [index]. An index of `points.size` appends. */
    fun insertAt(points: List<GeoPoint>, index: Int, point: GeoPoint): List<GeoPoint> =
        if (index !in 0..points.size) points
        else points.toMutableList().apply { add(index, point) }

    /**
     * Swaps two corners, which is what the move-up and move-down buttons do.
     *
     * Order is not cosmetic here: the corners are a ring, so `A B C D` and
     * `A C B D` enclose different shapes and report different areas. A corner
     * typed in out of sequence has to be movable or the boundary is wrong.
     */
    fun swap(points: List<GeoPoint>, i: Int, j: Int): List<GeoPoint> {
        if (i !in points.indices || j !in points.indices || i == j) return points
        return points.toMutableList().apply {
            val held = this[i]
            this[i] = this[j]
            this[j] = held
        }
    }

    /**
     * True when [candidate] is far enough from every other corner to be a corner
     * of its own.
     *
     * [ignoreIndex] is the corner being edited: a corner nudged by a few
     * centimetres, or saved again unchanged, must not be refused for sitting on
     * top of itself. The separation rule itself is [CornerDraft.acceptTap]'s —
     * a typed corner and a tapped one deserve the same threshold.
     */
    fun isDistinct(
        points: List<GeoPoint>,
        candidate: GeoPoint,
        ignoreIndex: Int? = null
    ): Boolean = CornerDraft.acceptTap(
        existing = points.filterIndexed { i, _ -> i != ignoreIndex },
        candidate = candidate
    )

    /**
     * Reads a corner typed into two text fields, or null when either field is
     * not a coordinate.
     *
     * The ranges are the same ones [com.landpoint.app.ui.edit.LandEditViewModel]
     * enforces on the land's own coordinates, so a boundary cannot hold a corner
     * the form would have rejected. Parsed with [String.toDoubleOrNull] rather
     * than a locale-aware format: these fields are filled from written records,
     * which use a dot.
     */
    fun parseLatLon(lat: String, lon: String): GeoPoint? {
        val latitude = lat.trim().toDoubleOrNull() ?: return null
        val longitude = lon.trim().toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0) return null
        if (longitude !in -180.0..180.0) return null
        return GeoPoint(latitude = latitude, longitude = longitude)
    }
}
