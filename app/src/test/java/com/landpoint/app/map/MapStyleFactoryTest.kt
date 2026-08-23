package com.landpoint.app.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.landpoint.app.data.BasemapMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The style documents MapLibre is actually handed.
 *
 * Robolectric only for the `AssetManager` — the bundled street styles are real
 * files in `assets/`, and a test that stubbed them would prove nothing about the
 * documents that ship. Everything asserted here is a property the map depends on
 * and that fails silently on a device: a raster template with its axes the wrong
 * way round draws imagery of somewhere else, a style with no glyph source draws the
 * corner numbers as nothing at all, and an offline style that keeps one source
 * pointed at a server looks fine until the signal goes.
 */
@RunWith(RobolectricTestRunner::class)
class MapStyleFactoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var factory: MapStyleFactory

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        factory = MapStyleFactory(context.assets)
    }

    private fun provider(mode: BasemapMode): MapProvider =
        requireNotNull(MapProviders.of(mode, null)) { "$mode needs no archive" }

    private fun document(mode: BasemapMode, dark: Boolean = false): JsonObject =
        json.parseToJsonElement(factory.styleJson(provider(mode), dark)).jsonObject

    private fun JsonObject.sources(): JsonObject = getValue("sources").jsonObject

    private fun JsonObject.layers(): JsonArray = getValue("layers").jsonArray

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

    @Test
    fun `a generated raster style is a complete style document`() {
        val style = document(BasemapMode.SATELLITE)

        assertEquals("8", style.text("version"))
        // Bundled letter shapes: the app's own corner-number layer needs them, and an
        // asset URL costs no request on a phone that has never been online.
        assertTrue(style.text("glyphs")!!.startsWith("asset://"))
        // A raster layer draws no symbols, so there is deliberately no icon atlas.
        assertNull(style["sprite"])

        // Background first, so the gaps between tiles arriving one by one do not read
        // as holes in the map.
        val layers = style.layers().map { it.jsonObject }
        assertEquals("background", layers.first().text("type"))
        assertEquals("raster", layers.last().text("type"))
        assertEquals(layers.last().text("source"), style.sources().keys.single())
    }

    @Test
    fun `raster sources carry their attribution and their zoom limits`() {
        val terrain = provider(BasemapMode.TERRAIN)
        val source = document(BasemapMode.TERRAIN).sources().values.single().jsonObject

        assertEquals("raster", source.text("type"))
        assertEquals(terrain.minZoom.toString(), source.text("minzoom"))
        assertEquals(terrain.maxZoom.toString(), source.text("maxzoom"))
        // A licence condition, not a courtesy: OpenTopoMap is CC-BY-SA.
        assertTrue(source.text("attribution")!!.contains("OpenTopoMap"))
        assertEquals("256", source.text("tileSize"))
    }

    @Test
    fun `the imagery template is row-major, as ArcGIS serves it`() {
        val templates = document(BasemapMode.SATELLITE)
            .sources().values.single().jsonObject
            .getValue("tiles").jsonArray
            .map { it.jsonPrimitive.content }

        assertEquals(1, templates.size)
        // z/y/x, not the z/x/y of every other source here. Getting it the usual way
        // round returns imagery of somewhere else entirely, with no error to say so.
        assertTrue(templates.single().endsWith("/tile/{z}/{y}/{x}"))
    }

    @Test
    fun `terrain spreads its requests over every host it was given`() {
        val templates = document(BasemapMode.TERRAIN)
            .sources().values.single().jsonObject
            .getValue("tiles").jsonArray
            .map { it.jsonPrimitive.content }

        assertEquals(3, templates.size)
        assertEquals(3, templates.toSet().size)
    }

    @Test
    fun `photographs are never recoloured for the night theme`() {
        // BasemapMode.SATELLITE opts out of tinting: an inverted or dimmed photograph
        // is not a night map, and the same holds for relief shading.
        assertFalse(BasemapMode.SATELLITE.tintForNight)

        val paint = document(BasemapMode.SATELLITE, dark = true)
            .layers().last().jsonObject
            .getValue("paint").jsonObject

        assertTrue(paint.isEmpty())
    }

    @Test
    fun `a raster style that opted into tinting is dimmed rather than inverted`() {
        // No shipped raster source opts in, so the branch is exercised through a
        // provider built for the purpose — the alternative is an untested branch that
        // the next raster source added would be the first to run.
        val tinted = provider(BasemapMode.SATELLITE).copy(
            mode = BasemapMode.STREET,
            tiles = TileSpec.RasterXyz(
                templates = listOf("https://example.invalid/{z}/{x}/{y}.png"),
                tileSize = 256,
                attribution = "test"
            )
        )
        assertTrue(tinted.mode.tintForNight)

        val paint = json.parseToJsonElement(factory.styleJson(tinted, dark = true))
            .jsonObject.layers().last().jsonObject
            .getValue("paint").jsonObject

        // Brightness capped and saturation pulled back: the glare comes off, the
        // colours stay recognisable.
        assertEquals(0.72, paint.getValue("raster-brightness-max").jsonPrimitive.content.toDouble(), 1e-9)
        assertTrue(paint.getValue("raster-saturation").jsonPrimitive.content.toDouble() < 0.0)
        assertNull(paint["raster-brightness-min"])
    }

    @Test
    fun `the bundled street style is used as it ships`() {
        val style = document(BasemapMode.STREET)

        assertEquals("8", style.text("version"))
        assertTrue(style.sources().containsKey(MapProviders.streetStyle.vectorSourceId))
        assertTrue(style.layers().size > 1)
    }

    @Test
    fun `night uses the hand-drawn dark style, not a filter over the light one`() {
        val day = factory.styleJson(provider(BasemapMode.STREET), dark = false)
        val night = factory.styleJson(provider(BasemapMode.STREET), dark = true)

        // Two documents, because the colours in a vector style are per-layer decisions
        // about ink on paper and there is no transform that turns one into the other.
        assertNotEquals(day, night)
        assertEquals("8", json.parseToJsonElement(night).jsonObject.text("version"))
    }

    @Test
    fun `an imported vector archive borrows the street style and reads from the file`() {
        val archive = File("/data/user/0/com.landpoint.app/files/maps/archives/kabupaten.pmtiles")
        val imported = requireNotNull(
            MapProviders.of(BasemapMode.IMPORTED, ImportedArchiveSpec(archive, vector = true))
        )

        val style = json.parseToJsonElement(factory.styleJson(imported, dark = false)).jsonObject
        val sources = style.sources()
        val vector = sources.getValue(MapProviders.streetStyle.vectorSourceId).jsonObject

        assertEquals("vector", vector.text("type"))
        assertEquals("pmtiles://${archive.absolutePath}", vector.text("url"))

        // The decorative low-zoom backdrop is dropped rather than left pointing at a
        // server: an offline map that keeps reaching for one logs errors forever.
        assertFalse(sources.containsKey("ne2_shaded"))
        assertTrue(style.layers().none { it.jsonObject.text("source") == "ne2_shaded" })
        // Everything else survives, so the offline map is drawn with the same ink as
        // the online one.
        assertTrue(style.layers().size > 1)
    }

    @Test
    fun `an imported raster archive gets a generated raster style over the file`() {
        val archive = File("/data/user/0/com.landpoint.app/files/maps/archives/foto.pmtiles")
        val imported = requireNotNull(
            MapProviders.of(BasemapMode.IMPORTED, ImportedArchiveSpec(archive, vector = false))
        )

        val source = json.parseToJsonElement(factory.styleJson(imported, dark = false))
            .jsonObject.sources().values.single().jsonObject

        assertEquals("raster", source.text("type"))
        // A URL rather than a tile template: MapLibre reads the pyramid out of the
        // archive's own header.
        assertEquals("pmtiles://${archive.absolutePath}", source.text("url"))
        assertNull(source["tiles"])
    }

    @Test
    fun `a style carries the zoom limits of the source that made it`() {
        val terrain = provider(BasemapMode.TERRAIN)
        val style = factory.styleFor(terrain, dark = false)

        assertEquals(terrain.minZoom, style.minZoom, 0.0)
        assertEquals(terrain.maxZoom, style.maxZoom, 0.0)
        // 17, not the 19 the street tiles reach: past it OpenTopoMap has nothing, and
        // a camera left deeper shows the blank grey of tiles that were never made.
        assertEquals(17.0, style.maxZoom, 0.0)
    }

    @Test
    fun `asking twice for the same style rebuilds nothing`() {
        val street = provider(BasemapMode.STREET)

        val first = factory.styleFor(street, dark = false)
        val again = factory.styleFor(street, dark = false)

        assertSame(first, again)
    }

    @Test
    fun `the key tells apart the theme, the source and the archive behind it`() {
        val street = provider(BasemapMode.STREET)
        val day = factory.styleFor(street, dark = false)
        val night = factory.styleFor(street, dark = true)
        val terrain = factory.styleFor(provider(BasemapMode.TERRAIN), dark = false)

        assertFalse(day.key == night.key)
        assertFalse(day.key == terrain.key)

        // Two archives are two documents even though both are BasemapMode.IMPORTED,
        // so switching between imported maps is noticed.
        fun importedKey(name: String) = factory.styleFor(
            requireNotNull(
                MapProviders.of(
                    BasemapMode.IMPORTED,
                    ImportedArchiveSpec(File("/tmp/$name.pmtiles"), vector = true)
                )
            ),
            dark = false
        ).key

        assertFalse(importedKey("utara") == importedKey("selatan"))
    }

    @Test
    fun `a style the theme cannot recolour is the same document in either theme`() {
        val satellite = provider(BasemapMode.SATELLITE)

        // Same key, so a theme change on a photographic basemap does not reload the
        // map for a document that would come out byte-identical.
        assertEquals(
            factory.styleFor(satellite, dark = false).key,
            factory.styleFor(satellite, dark = true).key
        )
    }
}
