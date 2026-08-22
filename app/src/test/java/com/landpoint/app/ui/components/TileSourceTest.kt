package com.landpoint.app.ui.components

import com.landpoint.app.data.BasemapMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import org.robolectric.RobolectricTestRunner

/**
 * The tile servers behind each style, and the two things about them that cannot
 * be checked by looking at the map.
 *
 * A wrong tile path does not fail — it returns a perfectly good photograph of
 * somewhere else, which on unfamiliar ground is indistinguishable from being lost.
 * And a missing courtesy policy does not fail either; it just quietly abuses
 * somebody's donated bandwidth until they block the app.
 */
@RunWith(RobolectricTestRunner::class)
class TileSourceTest {

    private fun urlFor(mode: BasemapMode, zoom: Int, x: Int, y: Int): String {
        val source = tileSourceFor(mode, null) as OnlineTileSourceBase
        return source.getTileURLString(MapTileIndex.getTileIndex(zoom, x, y))
    }

    @Test
    fun `aerial tiles are asked for row-major, the way arcgis serves them`() {
        // zoom / y / x — deliberately not the z/x/y of every other source here.
        // Swap the last two and the app shows imagery of another part of the world
        // without a single error.
        assertTrue(urlFor(BasemapMode.SATELLITE, 17, 105, 66).endsWith("/17/66/105"))
    }

    @Test
    fun `terrain tiles are asked for the usual way round`() {
        assertTrue(urlFor(BasemapMode.TERRAIN, 14, 3, 2).endsWith("/14/3/2.png"))
    }

    @Test
    fun `the online styles are polite about it`() {
        BasemapMode.entries.filter { it.needsNetwork }.forEach { mode ->
            val policy = (tileSourceFor(mode, null) as OnlineTileSourceBase).tileSourcePolicy
            assertTrue("$mode fetches too much at once", policy.maxConcurrent <= 2)
            // No bulk downloading and no fetching tiles nobody asked to see: the
            // terms these free servers are offered under.
            assertFalse("$mode allows bulk download", policy.acceptsBulkDownload())
            assertFalse("$mode prefetches", policy.acceptsPreventive())
        }
    }

    @Test
    fun `an imported map that is not there falls back to the street tiles`() {
        // The style is stored; the .map file it names can be deleted afterwards.
        assertEquals(TileSourceFactory.MAPNIK, tileSourceFor(BasemapMode.IMPORTED, null))
    }

    @Test
    fun `every style names a source and stops where its tiles stop`() {
        BasemapMode.entries.forEach { mode ->
            val source = tileSourceFor(mode, null)
            assertTrue("$mode has no copyright notice", source.copyrightNotice.isNotBlank())
            assertTrue("$mode claims an impossible zoom", source.maximumZoomLevel in 15..22)
        }
    }
}
