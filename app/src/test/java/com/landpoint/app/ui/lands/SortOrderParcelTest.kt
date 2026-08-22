package com.landpoint.app.ui.lands

import com.landpoint.app.data.export.LandTestFactory
import com.landpoint.app.data.model.Land
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The parcel-number order.
 *
 * Two rules matter here. Numbers read the way a person reads them — `A/2` above
 * `A/10` — and a land with no number is not number zero: it is a plot that has
 * not been registered yet, and it belongs at the end where the user can see how
 * many of those are left rather than at the top ahead of every real record.
 */
class SortOrderParcelTest {

    private fun sortedNames(vararg lands: Land) =
        lands.toList().sortedWith(SortOrder.PARCEL_ASC.comparator()).map { it.name }

    @Test
    fun `numbers sort the way they are read, not the way they are spelled`() {
        assertEquals(
            listOf("two", "ten", "hundred"),
            sortedNames(
                LandTestFactory.land(name = "hundred", parcelNumber = "SHM 100"),
                LandTestFactory.land(name = "ten", parcelNumber = "SHM 10"),
                LandTestFactory.land(name = "two", parcelNumber = "SHM 2")
            )
        )
    }

    @Test
    fun `a land with no parcel number sorts last`() {
        assertEquals(
            listOf("registered", "not yet"),
            sortedNames(
                LandTestFactory.land(name = "not yet", parcelNumber = null),
                LandTestFactory.land(name = "registered", parcelNumber = "SHM 1")
            )
        )
    }

    @Test
    fun `a blank parcel number counts as none at all`() {
        // The editor trims to null on save, but a row imported from someone
        // else's CSV can carry spaces, and spaces must not sort above `A`.
        assertEquals(
            listOf("registered", "spaces"),
            sortedNames(
                LandTestFactory.land(name = "spaces", parcelNumber = "   "),
                LandTestFactory.land(name = "registered", parcelNumber = "SHM 1")
            )
        )
    }

    @Test
    fun `unregistered lands are ordered by name among themselves`() {
        assertEquals(
            listOf("apel", "Belimbing", "ceri"),
            sortedNames(
                LandTestFactory.land(name = "ceri"),
                LandTestFactory.land(name = "apel"),
                LandTestFactory.land(name = "Belimbing")
            )
        )
    }

    @Test
    fun `sorting an empty list is not an error`() {
        assertEquals(emptyList<String>(), sortedNames())
    }
}
