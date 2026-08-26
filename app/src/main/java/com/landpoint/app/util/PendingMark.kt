package com.landpoint.app.util

/**
 * Where a mark came from, which is the only thing that separates two marks sitting
 * on the same coordinates.
 *
 * Kept on the mark because the confirm bar has different things to say about each:
 * a mark averaged from the receiver can quote an accuracy and a sample count, one
 * tapped on imagery cannot, and one typed from a certificate should not be
 * second-guessed by the app at all.
 */
enum class MarkSource {

    /** Placed on the map — by the crosshair, or by dragging a mark already placed. */
    MAP,

    /** Moved to the averaged fix the receiver is reporting. */
    GPS,

    /** Coordinates typed in, usually off a certificate or a neighbour's document. */
    TYPED,

    /** Measured out from the previous corner as a distance and a bearing. */
    BEARING
}

/**
 * What committing a mark would do to the boundary, worked out while it still hangs
 * there.
 *
 * This is the whole point of the provisional mark. The same three answers used to be
 * discovered *after* the corner had been added — the insert announced by a snackbar
 * that had already begun to fade, the refusal announced by another one — so a user
 * whose corner became number 3 instead of number 7 found out from a message about a
 * renumbering that had already happened. Now the answer is on screen above the
 * button, before the button is pressed.
 */
sealed interface Placement {

    /**
     * Goes on the end of the ring and becomes corner [number], counted the way the
     * map labels them.
     */
    data class Append(val number: Int) : Placement

    /**
     * Slots into the ring at list index [at], which makes it corner `at + 1` and
     * pushes every corner after it up by one.
     *
     * A mark dropped on a side almost always means "I missed this corner out",
     * which is why it is inserted rather than appended: appending would draw a
     * spike from the last corner across the plot and back.
     */
    data class Insert(val at: Int) : Placement

    /**
     * Refused: closer to corner [nearCorner] than two corners can be, so committing
     * it would give the polygon an edge of no length.
     *
     * The corner is named because "too close" on its own sends the user hunting for
     * which of eight corners they are standing on.
     */
    data class TooClose(val nearCorner: Int) : Placement
}

/**
 * A corner the user can see on the map but has not agreed to yet.
 *
 * Placing a corner means judging a spot against a tree, a ditch or a roof line, and
 * a fingertip covers several metres of ground at the zoom that judgement is made at.
 * Committing the tap immediately — which is what this app did until now — meant the
 * first thing the user saw of their corner was already a corner, correctable only by
 * dragging it or deleting it. So a tap now hangs a mark, the mark can be dragged and
 * nudged to the metre, and one button turns it into a corner.
 *
 * Deliberately not part of the draft boundary: a hanging mark contributes nothing to
 * the area, nothing to the perimeter and nothing to the outline, so nothing
 * downstream has to learn to ignore it.
 */
data class PendingMark(
    val point: GeoPoint,
    val source: MarkSource,
    /**
     * The list index this mark was measured out from, or null when it was placed
     * without reference to a particular corner.
     *
     * Only the bearing path sets it, and it exists because a bearing carries a
     * sequence the coordinates alone have lost: "50 m east of corner 2" belongs
     * between corners 2 and 3, however far from either side the arithmetic put
     * it. Without this the mark would be appended to the end of the ring and the
     * side it describes would be drawn as a spike across the parcel.
     */
    val after: Int? = null
) {

    /**
     * What committing this mark would do to [existing], with [edgeToleranceM]
     * read off the zoom the map is at.
     *
     * The confirm bar reads this on every recomposition, which is why it is a
     * function of the draft rather than something stored on the mark: undoing a
     * corner while a mark hangs there changes the answer, and a stored one would
     * go on promising "corner 5" after corner 4 had gone.
     */
    fun placementIn(existing: List<GeoPoint>, edgeToleranceM: Double): Placement =
        placement(existing, point, edgeToleranceM, after)

    /**
     * The same mark moved one step along [bearingDeg] — what an arrow button does.
     *
     * Comes back as a map mark whatever it was before, and that is the honest
     * answer: a fix the user has nudged 40 cm north is no longer what the
     * receiver reported, so it must not go on quoting that reading's accuracy.
     * The mark is returned unchanged, identity and all, when the step is not a
     * usable number.
     */
    fun nudged(bearingDeg: Double, stepM: Double): PendingMark {
        val moved = nudge(point, bearingDeg, stepM)
        return if (moved === point) this else copy(point = moved, source = MarkSource.MAP)
    }

    /** The same mark dragged under a finger to [latitude], [longitude]. */
    fun movedTo(latitude: Double, longitude: Double): PendingMark =
        copy(point = GeoPoint(latitude, longitude), source = MarkSource.MAP)

    companion object {

        /**
         * The nudge steps offered, coarsest first.
         *
         * Five metres to cross a yard, ten centimetres to sit on a peg. Chosen
         * rather than a slider because the user is reading a number off a
         * certificate or looking at a fence post, not exploring a range.
         */
        val STEPS_M: List<Double> = listOf(5.0, 1.0, 0.5, 0.1)

        /** Half a metre: about the width of a boundary stone, and a sane default. */
        const val DEFAULT_STEP_M: Double = 0.5

        /**
         * What [candidate] would become if it were committed to [existing] now.
         *
         * The rules are not new — [CornerDraft.acceptTap] for the separation and
         * [BoundaryEdits.edgeNear] for the side — and that matters more than it
         * looks: this runs *before* the commit, so if the two disagreed the user
         * would be shown one answer and given another.
         *
         * @param edgeToleranceM how close to a side counts as being on it, in
         *   metres on the ground at the zoom the mark was placed at. Zero for a
         *   mark that came from anywhere but a tap, which is exactly right — typed
         *   coordinates are not aiming at a side.
         * @param after the corner index a bearing was measured from, which fixes
         *   where the mark belongs regardless of [edgeToleranceM]. See
         *   [PendingMark.after].
         */
        fun placement(
            existing: List<GeoPoint>,
            candidate: GeoPoint,
            edgeToleranceM: Double,
            after: Int? = null
        ): Placement {
            if (!CornerDraft.acceptTap(existing, candidate)) {
                return Placement.TooClose(nearCorner = nearestCorner(existing, candidate))
            }
            // A stated sequence beats a guessed one: where the mark came from a
            // bearing off a named corner, that corner decides, not how near the
            // arithmetic happened to land to some other side.
            val at = if (after != null && after in existing.indices) after + 1
            else BoundaryEdits.edgeNear(existing, candidate, edgeToleranceM)
            return if (at == null || at >= existing.size) Placement.Append(number = existing.size + 1)
            else Placement.Insert(at = at)
        }

        /**
         * The mark moved [stepM] metres along [bearingDeg].
         *
         * The tool behind the four arrow buttons, which exist because a finger
         * cannot place a corner to the metre and a survey peg is a metre-scale
         * thing. Great-circle, through [GeoUtils.destination], so a mark nudged
         * north and then south lands back where it started rather than drifting.
         *
         * A step that is zero, negative or not a number returns the mark untouched.
         * The alternative is a NaN latitude, which MapLibre accepts and draws
         * nowhere: the mark would vanish off the map with no error to say why.
         */
        fun nudge(point: GeoPoint, bearingDeg: Double, stepM: Double): GeoPoint {
            if (!stepM.isFinite() || stepM <= 0.0 || !bearingDeg.isFinite()) return point
            // No accuracy on the way out, and that is the honest answer: a nudged
            // mark is where the user put it, not where a receiver said it was, so
            // it must not inherit the ± figure of the fix it started from.
            return GeoUtils.destination(point.latitude, point.longitude, bearingDeg, stepM)
        }

        /** The 1-based number of the corner in [existing] nearest to [candidate]. */
        private fun nearestCorner(existing: List<GeoPoint>, candidate: GeoPoint): Int {
            var best = 0
            var bestM = Double.MAX_VALUE
            existing.forEachIndexed { index, corner ->
                val metres = GeoUtils.distance(
                    corner.latitude, corner.longitude,
                    candidate.latitude, candidate.longitude
                )
                if (metres < bestM) {
                    bestM = metres
                    best = index
                }
            }
            return best + 1
        }
    }
}
