package com.landpoint.app.ui.lands

import com.landpoint.app.data.export.LandTestFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two area orders.
 *
 * The rule worth pinning down is what happens to a land that has no area at all:
 * a single dropped pin is not "the smallest plot", so it belongs at the end of
 * the list whichever way the measured ones are pointing. Getting that wrong in
 * ascending order would push every real measurement below a row of blanks.
 */
class SortOrderAreaTest {

    private fun lands() = listOf(
        LandTestFactory.land(name = "medium", areaSqm = 500.0),
        LandTestFactory.land(name = "unmeasured", areaSqm = null),
        LandTestFactory.land(name = "large", areaSqm = 2_500.0),
        LandTestFactory.land(name = "also unmeasured", areaSqm = null),
        LandTestFactory.land(name = "small", areaSqm = 40.0)
    )

    private fun sortedNames(order: SortOrder) =
        lands().sortedWith(order.comparator()).map { it.name }

    @Test
    fun `descending puts the largest first and the unmeasured last`() {
        assertEquals(
            listOf("large", "medium", "small", "unmeasured", "also unmeasured"),
            sortedNames(SortOrder.AREA_DESC)
        )
    }

    @Test
    fun `ascending puts the smallest first and still leaves the unmeasured last`() {
        assertEquals(
            listOf("small", "medium", "large", "unmeasured", "also unmeasured"),
            sortedNames(SortOrder.AREA_ASC)
        )
    }

    @Test
    fun `lands with no area never come before a measured one in either direction`() {
        for (order in listOf(SortOrder.AREA_ASC, SortOrder.AREA_DESC)) {
            val sorted = lands().sortedWith(order.comparator())
            val firstBlank = sorted.indexOfFirst { it.areaSqm == null }
            val lastMeasured = sorted.indexOfLast { it.areaSqm != null }
            assertEquals(
                "$order should keep every measured land above the unmeasured ones",
                true,
                firstBlank > lastMeasured
            )
        }
    }

    @Test
    fun `a zero area is a measurement and sorts as one`() {
        // A boundary that closes on itself measures zero. That is a real answer,
        // not a missing one, so it must not be swept to the bottom with the pins.
        val withZero = lands() + LandTestFactory.land(name = "zero", areaSqm = 0.0)
        val sorted = withZero.sortedWith(SortOrder.AREA_ASC.comparator()).map { it.name }
        assertEquals("zero", sorted.first())
        assertEquals(listOf("unmeasured", "also unmeasured"), sorted.takeLast(2))
    }

    @Test
    fun `sorting an empty list is not an error`() {
        assertEquals(emptyList<String>(), emptyList<com.landpoint.app.data.model.Land>()
            .sortedWith(SortOrder.AREA_DESC.comparator()).map { it.name })
    }
}
