package com.landpoint.app.util

/**
 * Comparison that reads the numbers inside text as numbers.
 *
 * Parcel numbers are written by hand and mix the two: `Blok A/2` belongs above
 * `Blok A/10`, which a plain string comparison gets backwards because it stops at
 * the `1` and never sees that one number is ten times the other. Letters are
 * compared without regard to case, so `shm 4` and `SHM 12` stay next to each
 * other as the one prefix they are rather than drifting apart.
 */
object NaturalOrder {

    /** Alternating runs of digits and non-digits — the units of comparison. */
    private val chunks = Regex("\\d+|\\D+")

    fun compare(left: String, right: String): Int {
        val a = chunks.findAll(left).map { it.value }.toList()
        val b = chunks.findAll(right).map { it.value }.toList()
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i]
            val y = b[i]
            // Tested against the digit range rather than with `isDigit()`, which
            // also accepts digits from other scripts that the pattern above put
            // in a non-digit run.
            val bothNumbers = x[0] in '0'..'9' && y[0] in '0'..'9'
            val verdict =
                if (bothNumbers) compareNumbers(x, y) else x.compareTo(y, ignoreCase = true)
            if (verdict != 0) return verdict
        }
        // Equal as far as the shorter one goes, so the shorter one is the less
        // specific of the two: `Blok A` above `Blok A/1`.
        return a.size - b.size
    }

    /**
     * Digit runs compared as quantities, without parsing them: a parcel number
     * copied off a certificate can carry more digits than a `Long` holds, and no
     * list screen should fall over because someone's registry writes long numbers.
     */
    private fun compareNumbers(left: String, right: String): Int {
        val a = left.trimStart('0')
        val b = right.trimStart('0')
        if (a.length != b.length) return a.length - b.length
        val digits = a.compareTo(b)
        if (digits != 0) return digits
        // `007` and `7` are one quantity written two ways. Ordering them by how
        // they are written keeps the comparison consistent — calling them equal
        // would let the list shuffle between two lands that never changed.
        return right.length - left.length
    }
}
