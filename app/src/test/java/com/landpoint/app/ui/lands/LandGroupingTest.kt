package com.landpoint.app.ui.lands

import com.landpoint.app.data.export.LandTestFactory
import com.landpoint.app.data.model.Land
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The headings the list draws over itself.
 *
 * Grouping takes an already-sorted list and names the runs in it, so every case
 * here sorts first and groups after — heading a list in an order that did not
 * produce those runs is exactly the bug this arrangement avoids. The other thing
 * worth holding on to is that grouping never loses a land: a heading that quietly
 * swallowed a record would look like a heading that was working.
 *
 * Timestamps sit at midday on the fifteenth so the month they fall in does not
 * depend on the timezone the tests happen to run in.
 */
class LandGroupingTest {

    private val noParcel = "No parcel number"

    /** 15 November 2023, 12:00 UTC. */
    private val november = 1_700_049_600_000L

    /** 15 December 2023, 12:00 UTC. */
    private val december = 1_702_641_600_000L

    private fun rows(lands: List<Land>, order: SortOrder) =
        groupedRows(lands.sortedWith(order.comparator()), order, Locale.ENGLISH, noParcel)

    private fun headings(rows: List<LandRow>) =
        rows.filterIsInstance<LandRow.Header>().map { it.label to it.count }

    private fun names(rows: List<LandRow>) =
        rows.filterIsInstance<LandRow.Item>().map { it.land.name }

    @Test
    fun `dates are headed by their month, counted`() {
        val lands = listOf(
            LandTestFactory.land(name = "older", createdAt = november),
            LandTestFactory.land(name = "newer", createdAt = december),
            LandTestFactory.land(name = "newest", createdAt = december + 1_000)
        )
        val grouped = rows(lands, SortOrder.DATE_DESC)
        assertEquals(listOf("December 2023" to 2, "November 2023" to 1), headings(grouped))
        assertEquals(listOf("newest", "newer", "older"), names(grouped))
    }

    @Test
    fun `names are headed by their first letter, and everything else by one symbol`() {
        val lands = listOf(
            LandTestFactory.land(name = "apel"),
            LandTestFactory.land(name = "Anggur"),
            LandTestFactory.land(name = "Belimbing"),
            LandTestFactory.land(name = "3 sudut")
        )
        assertEquals(
            listOf("#" to 1, "A" to 2, "B" to 1),
            headings(rows(lands, SortOrder.NAME_ASC))
        )
    }

    @Test
    fun `a parcel prefix heads its run and a bare number heads nothing`() {
        val lands = listOf(
            LandTestFactory.land(name = "shm two", parcelNumber = "SHM 2"),
            LandTestFactory.land(name = "shm twelve", parcelNumber = "SHM 12"),
            LandTestFactory.land(name = "blok", parcelNumber = "Blok A/2"),
            LandTestFactory.land(name = "bare", parcelNumber = "77"),
            LandTestFactory.land(name = "none", parcelNumber = null)
        )
        val grouped = rows(lands, SortOrder.PARCEL_ASC)
        // `77` names nothing the next land could share, so it is left unheaded
        // rather than given a heading of one.
        assertEquals(
            listOf("Blok A" to 1, "SHM" to 2, noParcel to 1),
            headings(grouped)
        )
        assertEquals(listOf("bare", "blok", "shm two", "shm twelve"), names(grouped).dropLast(1))
        assertTrue(grouped.first() is LandRow.Item)
    }

    @Test
    fun `separators after a prefix are not part of it`() {
        val lands = listOf(
            LandTestFactory.land(name = "slash", parcelNumber = "SHM/12"),
            LandTestFactory.land(name = "dot", parcelNumber = "SHM.3"),
            LandTestFactory.land(name = "space", parcelNumber = "SHM 9")
        )
        // One prefix written three ways is one heading, not three.
        assertEquals(listOf("SHM" to 3), headings(rows(lands, SortOrder.PARCEL_ASC)))
    }

    @Test
    fun `distance and area are not grouped at all`() {
        val lands = listOf(
            LandTestFactory.land(name = "small", areaSqm = 10.0),
            LandTestFactory.land(name = "large", areaSqm = 900.0)
        )
        for (order in listOf(SortOrder.DISTANCE, SortOrder.AREA_ASC, SortOrder.AREA_DESC)) {
            val grouped = rows(lands, order)
            assertEquals("$order should draw no headings", emptyList<Pair<String, Int>>(), headings(grouped))
            assertEquals(lands.size, grouped.size)
        }
    }

    @Test
    fun `every land comes out of grouping, whatever the order`() {
        val lands = listOf(
            LandTestFactory.land(name = "apel", parcelNumber = "SHM 2", createdAt = november),
            LandTestFactory.land(name = "", parcelNumber = "", createdAt = december),
            LandTestFactory.land(name = "9 blok", parcelNumber = "9", createdAt = december),
            LandTestFactory.land(name = "Belimbing", areaSqm = 30.0)
        )
        for (order in SortOrder.entries) {
            val grouped = rows(lands, order)
            assertEquals(
                "$order lost or duplicated a land",
                lands.map { it.name }.sorted(),
                names(grouped).sorted()
            )
            // Every heading counts the run that follows it.
            val counted = grouped.filterIsInstance<LandRow.Header>().sumOf { it.count }
            assertTrue(
                "$order counted $counted of ${lands.size} lands under headings",
                counted <= lands.size
            )
        }
    }

    @Test
    fun `a heading counts the lands that follow it`() {
        val lands = listOf(
            LandTestFactory.land(name = "a", createdAt = december),
            LandTestFactory.land(name = "b", createdAt = december),
            LandTestFactory.land(name = "c", createdAt = november)
        )
        val grouped = rows(lands, SortOrder.DATE_ASC)
        var expected: Int? = null
        var seen = 0
        for (row in grouped + LandRow.Header("end", 0)) {
            when (row) {
                is LandRow.Header -> {
                    expected?.let { assertEquals("run before ${row.label}", it, seen) }
                    expected = row.count
                    seen = 0
                }
                is LandRow.Item -> seen++
            }
        }
    }

    @Test
    fun `an empty list gives no rows`() {
        assertEquals(emptyList<LandRow>(), rows(emptyList(), SortOrder.DATE_DESC))
    }

    @Test
    fun `an unnamed land is left unheaded rather than filed under a blank`() {
        val lands = listOf(LandTestFactory.land(name = "  "), LandTestFactory.land(name = "Apel"))
        val grouped = rows(lands, SortOrder.NAME_ASC)
        assertEquals(listOf("A" to 1), headings(grouped))
        assertTrue(grouped.first() is LandRow.Item)
    }
}
