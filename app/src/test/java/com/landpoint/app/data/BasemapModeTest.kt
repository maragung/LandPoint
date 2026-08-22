package com.landpoint.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map-style choice, and the two ways it can be wrong on a real phone.
 *
 * A stored style outlives the thing it names: the offline map file it points at can
 * be deleted, and the preference can be missing entirely on a first run. Both have
 * to end in a map that draws something, because a blank screen where the ground
 * should be looks like a broken app, not like a setting that needs changing.
 */
class BasemapModeTest {

    @Test
    fun `every style is found by the key it is stored under`() {
        BasemapMode.entries.forEach { mode ->
            assertEquals(mode, BasemapMode.fromKey(mode.key))
        }
    }

    @Test
    fun `an unknown or missing key is nobody's style`() {
        // A key written by a newer version, or none written yet. Neither is an
        // error; both mean "decide for me".
        assertNull(BasemapMode.fromKey("moon"))
        assertNull(BasemapMode.fromKey(null))
        assertNull(BasemapMode.fromKey(""))
    }

    @Test
    fun `the stored keys are unique and stable`() {
        val keys = BasemapMode.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        // Spelled out rather than derived: these strings sit in a DataStore file
        // on the user's phone, so renaming one silently resets their choice.
        assertEquals(listOf("street", "satellite", "terrain", "imported"), keys)
    }

    @Test
    fun `with nothing chosen, an imported map is what the user went to the trouble of importing`() {
        assertEquals(BasemapMode.IMPORTED, BasemapMode.resolve(null, hasVectorMap = true))
    }

    @Test
    fun `with nothing chosen and no imported map, the street tiles`() {
        assertEquals(BasemapMode.STREET, BasemapMode.resolve(null, hasVectorMap = false))
    }

    @Test
    fun `a chosen style survives an imported map appearing`() {
        // The offline map loads a moment after the screen opens. Someone looking
        // at aerial imagery must not have it swapped out from under them.
        assertEquals(
            BasemapMode.SATELLITE,
            BasemapMode.resolve(BasemapMode.SATELLITE, hasVectorMap = true)
        )
    }

    @Test
    fun `the offline style falls back to street tiles when the map file is gone`() {
        assertEquals(BasemapMode.STREET, BasemapMode.resolve(BasemapMode.IMPORTED, hasVectorMap = false))
        assertEquals(
            BasemapMode.IMPORTED,
            BasemapMode.resolve(BasemapMode.IMPORTED, hasVectorMap = true)
        )
    }

    @Test
    fun `only the offline style works without a connection`() {
        assertFalse(BasemapMode.IMPORTED.needsNetwork)
        BasemapMode.entries.filter { it != BasemapMode.IMPORTED }.forEach {
            assertTrue("$it is fetched over the network", it.needsNetwork)
        }
    }

    @Test
    fun `photographs and relief shading are never inverted for the dark theme`() {
        // Inverting a photograph does not make a night map, it makes an
        // unreadable one: vegetation comes out magenta and shaded valleys read
        // as ridges.
        assertFalse(BasemapMode.SATELLITE.tintForNight)
        assertFalse(BasemapMode.TERRAIN.tintForNight)
        // Drawn maps are a different matter — they are line art on pale ground.
        assertTrue(BasemapMode.STREET.tintForNight)
        assertTrue(BasemapMode.IMPORTED.tintForNight)
    }

    @Test
    fun `every style fetched from a server says how deep its tiles go`() {
        // Past the last published zoom a map goes blank grey, which looks like a
        // fault. Only the on-device renderer has no such limit.
        BasemapMode.entries.filter { it.needsNetwork }.forEach {
            assertNotNull("$it must cap its zoom", it.maxZoom)
        }
        assertNull(BasemapMode.IMPORTED.maxZoom)
        assertTrue(BasemapMode.TERRAIN.maxZoom!! < BasemapMode.SATELLITE.maxZoom!!)
    }
}
