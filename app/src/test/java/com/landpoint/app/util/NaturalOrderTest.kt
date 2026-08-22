package com.landpoint.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering behind the parcel-number sort.
 *
 * Parcel numbers come off certificates in whatever form the registry writes them,
 * so the cases worth pinning down are the ones where plain text comparison is
 * wrong: `A/10` after `A/2`, one prefix split in two by its capitals, and a number
 * long enough to overflow anything it might be parsed into.
 */
class NaturalOrderTest {

    private fun sorted(vararg values: String) =
        values.toList().sortedWith(Comparator { a, b -> NaturalOrder.compare(a, b) })

    @Test
    fun `numbers inside text are compared as numbers`() {
        assertTrue(NaturalOrder.compare("Blok A/2", "Blok A/10") < 0)
        assertEquals(
            listOf("A/1", "A/2", "A/10", "A/20", "A/100"),
            sorted("A/100", "A/2", "A/20", "A/1", "A/10")
        )
    }

    @Test
    fun `capitals do not split one prefix into two`() {
        assertEquals(0, NaturalOrder.compare("shm 4", "SHM 4"))
        assertTrue(NaturalOrder.compare("shm 4", "SHM 12") < 0)
    }

    @Test
    fun `the less specific number comes first`() {
        // Same as far as it goes, then one of them carries on.
        assertTrue(NaturalOrder.compare("Blok A", "Blok A/1") < 0)
    }

    @Test
    fun `a number too long to parse is still compared as a quantity`() {
        val twentyFiveNines = "9".repeat(25)
        val twentySixDigits = "1" + "0".repeat(25)
        assertTrue(NaturalOrder.compare(twentyFiveNines, twentySixDigits) < 0)
    }

    @Test
    fun `leading zeros do not move a number away from its own size`() {
        // 007 and 7 are one quantity written two ways, so they belong next to each
        // other — and both below 8, which is the part that would break if the
        // zeros were compared as text.
        assertTrue(NaturalOrder.compare("A007", "A8") < 0)
        assertTrue(NaturalOrder.compare("A7", "A8") < 0)
        assertEquals(listOf("A007", "A7", "A8"), sorted("A8", "A7", "A007"))
    }

    @Test
    fun `no two different numbers are ever called equal`() {
        // A comparator that calls them equal lets a list reorder itself between
        // two records that nobody touched.
        assertTrue(NaturalOrder.compare("A007", "A7") != 0)
    }

    @Test
    fun `the comparison is a mirror of itself`() {
        val values = listOf("", "7", "007", "A", "a", "A/1", "A/10", "Blok A", "SHM 12/2019")
        for (left in values) {
            for (right in values) {
                assertEquals(
                    "$left vs $right",
                    -Integer.signum(NaturalOrder.compare(right, left)),
                    Integer.signum(NaturalOrder.compare(left, right))
                )
            }
        }
    }

    @Test
    fun `empty text is below anything and equal to itself`() {
        assertEquals(0, NaturalOrder.compare("", ""))
        assertTrue(NaturalOrder.compare("", "A") < 0)
    }
}
