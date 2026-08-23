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

        // Then one raster layer per source, in the source order, and nothing else: a
        // layer naming a source the document does not declare draws nothing at all.
        val drawn = layers.drop(1)
        assertTrue(drawn.all { it.text("type") == "raster" })
        assertEquals(style.sources().keys.toList(), drawn.map { it.text("source") })
    }

    @Test
    fun `imagery is drawn as two tiers, so a missing deep tile uncovers a shallower one`() {
        // Esri has zoom 18 everywhere sampled across Indonesia, 19 in most places but
        // not all, and nothing readable says which is which for a given field. Both
        // tiers are therefore requested: where the deep tile exists it covers the base,
        // and where it does not the request 404s, draws nothing, and the base shows
        // through enlarged.
        val satellite = provider(BasemapMode.SATELLITE)
        val spec = satellite.tiles as TileSpec.RasterXyz
        val detail = requireNotNull(spec.detail) { "the deep tier is the fix" }
        val style = document(BasemapMode.SATELLITE)
        val sources = style.sources()

        assertEquals(listOf("basemap", "basemap-detail"), sources.keys.toList())

        val base = sources.getValue("basemap").jsonObject
        assertEquals(spec.sourceMaxZoom.toString(), base.text("maxzoom"))
        assertEquals("18.0", base.text("maxzoom"))
        assertTrue(base.text("attribution")!!.contains("Esri"))

        val deep = sources.getValue("basemap-detail").jsonObject
        assertEquals(detail.minZoom.toString(), deep.text("minzoom"))
        assertEquals(detail.maxZoom.toString(), deep.text("maxzoom"))
        // One credit for one provider: MapLibre gathers attributions per source, and
        // the same line twice reads as two different companies.
        assertNull(deep["attribution"])

        // Order is the whole mechanism — the deep tier has to be drawn over the base.
        val drawn = style.layers().map { it.jsonObject }.drop(1)
        assertEquals(listOf("basemap", "basemap-detail"), drawn.map { it.text("source") })
    }

    @Test
    fun `a missing imagery tile has to fail rather than arrive as a picture saying so`() {
        // Without blankTile=false the ArcGIS endpoint answers 200 and a 2.5 kB JPEG
        // reading "Map data not yet available" for ground it has no photograph of —
        // byte-identical wherever it happens, and indistinguishable from imagery to
        // MapLibre, which duly drew it over a user's land. This one parameter is the
        // only thing standing between that and a 404, so it is asserted rather than
        // trusted to survive the next edit of a URL.
        val templates = document(BasemapMode.SATELLITE).sources().values
            .flatMap { it.jsonObject.getValue("tiles").jsonArray }
            .map { it.jsonPrimitive.content }

        assertEquals(2, templates.size)
        assertTrue(templates.all { it.contains("blankTile=false") })
    }

    @Test
    fun `the camera may go deeper than the imagery that draws it`() {
        // The two numbers used to be one, and merging them is what asked Esri for
        // tiles it does not have. They are allowed to differ in exactly one direction.
        val satellite = provider(BasemapMode.SATELLITE)
        val spec = satellite.tiles as TileSpec.RasterXyz

        assertTrue(satellite.maxZoom > spec.sourceMaxZoom)
        assertEquals(MapProviders.DEEPEST_ZOOM, satellite.maxZoom, 0.0)

        MapProviders.builtIn.forEach {
            assertTrue("$it may not claim tiles past its own camera", it.tileMaxZoom <= it.maxZoom)
        }
    }

    @Test
    fun `raster sources carry their attribution and their zoom limits`() {
        val terrain = provider(BasemapMode.TERRAIN)
        val spec = terrain.tiles as TileSpec.RasterXyz
        // One tier and no deep one: enlarging a contour drawn from 30 m elevation
        // samples would invent a shape rather than blur a real one.
        val source = document(BasemapMode.TERRAIN).sources().values.single().jsonObject
        assertNull(spec.detail)

        assertEquals("raster", source.text("type"))
        assertEquals(terrain.minZoom.toString(), source.text("minzoom"))
        // The source's last published zoom, which is not in general the camera's
        // limit — here they happen to agree, and for imagery they must not.
        assertEquals(spec.sourceMaxZoom.toString(), source.text("maxzoom"))
        assertEquals(terrain.maxZoom.toString(), source.text("maxzoom"))
        // A licence condition, not a courtesy: OpenTopoMap is CC-BY-SA.
        assertTrue(source.text("attribution")!!.contains("OpenTopoMap"))
        assertEquals("256", source.text("tileSize"))
    }

    @Test
    fun `the imagery template is row-major, as ArcGIS serves it`() {
        val templates = document(BasemapMode.SATELLITE).sources().values
            .flatMap { it.jsonObject.getValue("tiles").jsonArray }
            .map { it.jsonPrimitive.content }

        // z/y/x, not the z/x/y of every other source here. Getting it the usual way
        // round returns imagery of somewhere else entirely, with no error to say so.
        assertTrue(templates.isNotEmpty())
        assertTrue(templates.all { it.contains("/tile/{z}/{y}/{x}") })
        assertTrue(templates.none { it.contains("/tile/{z}/{x}/{y}") })
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

        // Every tier, not just the top one: two tiers of the same photograph dimmed
        // differently would show their seam where the deeper one stops.
        val paints = document(BasemapMode.SATELLITE, dark = true)
            .layers().map { it.jsonObject }
            .filter { it.text("type") == "raster" }
            .map { it.getValue("paint").jsonObject }

        assertEquals(2, paints.size)
        assertTrue(paints.all { it.isEmpty() })
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
                attribution = "test",
                sourceMaxZoom = 18.0
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
        // 17, not the 20 every other style reaches: past it OpenTopoMap has nothing,
        // and unlike a photograph a stretched contour line is a shape nobody surveyed.
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
