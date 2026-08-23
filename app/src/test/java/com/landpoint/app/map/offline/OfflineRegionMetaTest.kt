package com.landpoint.app.map.offline

import com.landpoint.app.data.BasemapMode
import com.landpoint.app.map.GeoBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blob MapLibre carries alongside each saved region.
 *
 * It is the only place a region's name exists, so the decoder has to be forgiving in
 * exactly two directions and unforgiving everywhere else. Forgiving: a record written
 * by an older build must still be readable, and a record from a newer one — carrying
 * fields this version has never heard of — must not make every saved region vanish
 * after a downgrade. Unforgiving: bytes that are not this app's record at all have to
 * come back as null, because the screen shows such a region under a placeholder name
 * so the user can still delete it.
 */
class OfflineRegionMetaTest {

    private val bounds = GeoBounds(-6.95, 107.58, -6.90, 107.63)

    private fun meta(name: String = "Sawah utara") = OfflineRegionMeta.of(
        name = name,
        createdAt = 1_724_400_000_000L,
        bounds = bounds,
        minZoom = 10,
        maxZoom = 14,
        styleKey = BasemapMode.STREET.key
    )

    @Test
    fun `a record survives the round trip through bytes`() {
        val original = meta()

        val decoded = OfflineRegionMeta.decode(original.encode())

        assertEquals(original, decoded)
    }

    @Test
    fun `every field a user can see comes back intact`() {
        val decoded = requireNotNull(OfflineRegionMeta.decode(meta().encode()))

        assertEquals("Sawah utara", decoded.name)
        assertEquals(1_724_400_000_000L, decoded.createdAt)
        assertEquals(10, decoded.minZoom)
        assertEquals(14, decoded.maxZoom)
        assertEquals("street", decoded.styleKey)
        assertEquals(OfflineRegionMeta.CURRENT_SCHEMA, decoded.schema)
    }

    @Test
    fun `the rectangle is stored as four numbers and read back as one box`() {
        val decoded = requireNotNull(OfflineRegionMeta.decode(meta().encode()))

        // Flat fields rather than a nested object, so a later field added beside them
        // cannot change how an existing record parses.
        assertEquals(bounds, decoded.bounds)
        assertEquals(bounds.south, decoded.south, 0.0)
        assertEquals(bounds.west, decoded.west, 0.0)
        assertEquals(bounds.north, decoded.north, 0.0)
        assertEquals(bounds.east, decoded.east, 0.0)
    }

    @Test
    fun `a name with a comma, a quote or an emoji is not mangled`() {
        // Names are typed by the user, and JSON is the format — so the awkward
        // characters are the ones a hand-rolled encoder would break on.
        val awkward = "Tanah \"Pak Budi\", blok 3 — 🌾"

        val decoded = requireNotNull(OfflineRegionMeta.decode(meta(awkward).encode()))

        assertEquals(awkward, decoded.name)
    }

    @Test
    fun `the record names itself in the bytes, so it can be recognised later`() {
        val text = meta().encode().toString(Charsets.UTF_8)

        assertTrue(text.contains("\"name\""))
        assertTrue(text.contains("\"created_at\""))
        assertTrue(text.contains("\"min_zoom\""))
        assertTrue(text.contains("\"max_zoom\""))
        // Written even though it equals its default, because a record with no schema
        // number is a record no future version can date.
        assertTrue(text.contains("\"schema\""))
    }

    @Test
    fun `a record from a newer build is read rather than discarded`() {
        val fromTheFuture = """
            {"name":"Kebun timur","created_at":1724400000000,
             "south":-6.95,"west":107.58,"north":-6.9,"east":107.63,
             "min_zoom":10,"max_zoom":14,"style":"street","schema":2,
             "tiles_downloaded":900,"pinned":true}
        """.trimIndent().toByteArray()

        val decoded = OfflineRegionMeta.decode(fromTheFuture)

        // Unknown fields ignored: refusing them would make every saved region vanish
        // after a downgrade, taking its name with it.
        assertNotNull(decoded)
        assertEquals("Kebun timur", decoded!!.name)
        assertEquals(2, decoded.schema)
    }

    @Test
    fun `a record with no schema number is read as the first version`() {
        val early = """
            {"name":"Lama","created_at":1,"south":-1.0,"west":2.0,"north":1.0,"east":3.0,
             "min_zoom":0,"max_zoom":12,"style":"street"}
        """.trimIndent().toByteArray()

        val decoded = requireNotNull(OfflineRegionMeta.decode(early))

        assertEquals(1, decoded.schema)
        assertEquals("Lama", decoded.name)
    }

    @Test
    fun `bytes this app did not write come back as nothing`() {
        // Each of these is a real possibility: MapLibre lets any bytes at all be
        // stored here, and a database merged from another install may hold them.
        assertNull(OfflineRegionMeta.decode(null))
        assertNull(OfflineRegionMeta.decode(ByteArray(0)))
        assertNull(OfflineRegionMeta.decode("not json at all".toByteArray()))
        assertNull(OfflineRegionMeta.decode("{}".toByteArray()))
        // Well-formed JSON, but missing the fields that make it a region record.
        assertNull(OfflineRegionMeta.decode("""{"name":"only a name"}""".toByteArray()))
    }

    @Test
    fun `a record whose numbers arrived as text is refused rather than guessed at`() {
        val wrongTypes = """
            {"name":"Aneh","created_at":"yesterday","south":-1.0,"west":2.0,
             "north":1.0,"east":3.0,"min_zoom":0,"max_zoom":12,"style":"street"}
        """.trimIndent().toByteArray()

        assertNull(OfflineRegionMeta.decode(wrongTypes))
    }
}
