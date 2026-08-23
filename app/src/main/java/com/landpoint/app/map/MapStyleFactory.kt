package com.landpoint.app.map

import android.content.res.AssetManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The low-zoom shaded relief backdrop in the bundled street styles.
 *
 * Named here because it is dropped when tiles come from a local archive: an
 * offline map that quietly keeps reaching for a server to draw a decorative
 * layer is an offline map that logs errors forever.
 */
private const val BACKDROP_SOURCE_ID = "ne2_shaded"

/** The one source and layer id in a generated raster style. */
private const val RASTER_ID = "basemap"

/**
 * Where MapLibre finds the letter shapes it draws text with.
 *
 * `asset://` resolves inside the APK, so this costs no network request and works
 * with the radio switched off. Every style this factory returns declares it —
 * including the raster ones, whose own layers have no text at all — because the app
 * adds a symbol layer of its own for the numbered boundary corners, and a style with
 * no glyph source draws that layer's icons with the numbers silently missing.
 *
 * The bundled stacks are named `NotoSans-Regular` and friends rather than the fonts'
 * own "Noto Sans Regular": MapLibre percent-encodes whatever it substitutes for
 * `{fontstack}`, so a space in the name arrives at the asset reader as `%20` and the
 * directory is not found. Space-free names sidestep the question entirely. The name
 * is only a key — it has to match `text-font` in the style documents and the
 * directory under `assets/fonts`, and nothing else reads it.
 */
private const val GLYPHS = "asset://fonts/{fontstack}/{range}.pbf"

/**
 * Builds the style JSON MapLibre is handed for a given [MapProvider].
 *
 * MapLibre draws from a style document, not from a tile URL, so this is the one
 * place that knows what a "map style" actually is. Two things follow from doing it
 * here rather than pointing the map at a hosted style URL.
 *
 * The app never waits on the network to find out how to draw. A hosted style has to
 * be fetched before the first tile can even be requested, which on a weak connection
 * is a blank screen for as long as that takes; every style here is either bundled in
 * the APK or generated in memory.
 *
 * And an imported archive can be dressed in the same style as the online map, by
 * taking the bundled street style and repointing one source at a local file. That is
 * what stops an offline map from looking like a different, worse application.
 *
 * Every returned document is self-contained: fonts and icons resolve to `asset://`
 * URLs inside the APK, so labels draw with the radio switched off.
 */
class MapStyleFactory(private val assets: AssetManager) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The last document built, kept so moving between screens does not rebuild it.
     *
     * One entry, because the case worth catching is repetition of the *same* style:
     * four screens draw a map and each builds the style for the one basemap the user
     * has chosen, so navigating from the map to a record and into the corner picker
     * would otherwise read and re-serialise the same forty-kilobyte document three
     * times. Switching basemaps rebuilds, which is correct — that is a new document.
     *
     * A race between two callers costs a redundant rebuild and cannot yield the wrong
     * style: the entry is immutable and is only ever replaced whole.
     */
    @Volatile
    private var cached: MapStyle? = null

    /**
     * The whole document plus the facts the map view needs alongside it.
     *
     * Preferred over [styleJson] at call sites, because the zoom limits and the
     * identity of the style are decided by the same provider that decided its
     * contents, and splitting that across two calls is how they come to disagree.
     *
     * @param dark the theme in force. Only consulted where recolouring is honest: a
     *   vector style has a hand-drawn dark counterpart, and a raster style can be
     *   dimmed, but neither is applied to photographs or relief shading — see
     *   [com.landpoint.app.data.BasemapMode.tintForNight].
     */
    fun styleFor(provider: MapProvider, dark: Boolean): MapStyle {
        val night = dark && provider.mode.tintForNight
        val archive = (provider.tiles as? TileSpec.LocalArchive)?.path
        val key = listOfNotNull(provider.mode.key, if (night) "night" else "day", archive)
            .joinToString("|")
        cached?.let { hit ->
            if (hit.key == key && hit.minZoom == provider.minZoom &&
                hit.maxZoom == provider.maxZoom
            ) {
                return hit
            }
        }
        return MapStyle(
            key = key,
            json = styleJson(provider, dark),
            minZoom = provider.minZoom,
            maxZoom = provider.maxZoom
        ).also { cached = it }
    }

    fun styleJson(provider: MapProvider, dark: Boolean): String {
        val night = dark && provider.mode.tintForNight
        return when (val tiles = provider.tiles) {
            is TileSpec.VectorStyle ->
                readAsset(tiles.assetFor(night))

            is TileSpec.RasterXyz -> rasterStyle(
                night = night,
                source = buildJsonObject {
                    put("type", "raster")
                    putJsonArray("tiles") { tiles.templates.forEach { add(JsonPrimitive(it)) } }
                    put("tileSize", tiles.tileSize)
                    put("minzoom", provider.minZoom)
                    put("maxzoom", provider.maxZoom)
                    put("attribution", tiles.attribution)
                }
            )

            is TileSpec.LocalArchive ->
                if (tiles.vector) {
                    localVectorStyle(tiles, night)
                } else {
                    rasterStyle(
                        night = night,
                        source = buildJsonObject {
                            put("type", "raster")
                            put("url", pmtilesUrl(tiles.path))
                            put("tileSize", 256)
                        }
                    )
                }
        }
    }

    /**
     * The bundled street style, with its vector source pointed at a file on this
     * device instead of at the network.
     *
     * This works because the archives the app accepts carry the same OpenMapTiles
     * schema the bundled style is written against — the layer names the style filters
     * on are the layer names inside the archive. An archive built to some other
     * schema parses fine and draws nothing, which is why the importer checks what it
     * can and says so plainly rather than leaving the user with a blank screen.
     */
    private fun localVectorStyle(tiles: TileSpec.LocalArchive, night: Boolean): String {
        val spec = MapProviders.streetStyle
        val root = json.parseToJsonElement(readAsset(spec.assetFor(night))).jsonObjectOrEmpty()

        val sources = root["sources"].jsonObjectOrEmpty()
        val rewritten = buildJsonObject {
            for ((id, value) in sources) {
                when (id) {
                    spec.vectorSourceId -> putJsonObject(id) {
                        put("type", "vector")
                        put("url", pmtilesUrl(tiles.path))
                    }
                    BACKDROP_SOURCE_ID -> Unit // dropped rather than left pointing at a server
                    else -> put(id, value)
                }
            }
        }

        val layers = (root["layers"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        val kept = buildJsonArray {
            for (layer in layers) {
                val source = (layer.jsonObjectOrEmpty()["source"] as? JsonPrimitive)?.content
                if (source != BACKDROP_SOURCE_ID) add(layer)
            }
        }

        return JsonObject(root + mapOf("sources" to rewritten, "layers" to kept)).toString()
    }

    /**
     * A minimal, complete style around a single raster source.
     *
     * [GLYPHS] is declared but no `sprite` is: the app's corner numbers need letter
     * shapes, and nothing in a raster style needs an icon atlas.
     *
     * The background underneath is not decoration. Raster tiles arrive one at a time
     * over whatever connection there is, and the gaps between them read as holes in
     * the map unless something neutral is already there.
     */
    private fun rasterStyle(source: JsonObject, night: Boolean): String =
        buildJsonObject {
            put("version", 8)
            put("name", "LandPoint Raster")
            put("glyphs", GLYPHS)
            putJsonObject("sources") { put(RASTER_ID, source) }
            putJsonArray("layers") {
                add(
                    buildJsonObject {
                        put("id", "background")
                        put("type", "background")
                        putJsonObject("paint") {
                            put("background-color", if (night) "#12161c" else "#e8e6e1")
                        }
                    }
                )
                add(
                    buildJsonObject {
                        put("id", RASTER_ID)
                        put("type", "raster")
                        put("source", RASTER_ID)
                        putJsonObject("paint") {
                            // Dimming, not inverting. An inverted photograph is not a
                            // night map, it is an unreadable one, so the only thing
                            // done here is to take the glare off — and only for
                            // sources that opted into being recoloured at all.
                            if (night) {
                                put("raster-brightness-max", 0.72)
                                put("raster-saturation", -0.25)
                            }
                        }
                    }
                )
            }
        }.toString()

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }

    /**
     * A missing or wrongly-typed member is treated as absent rather than thrown on.
     * These documents ship inside the APK, so a malformed one is a build mistake that
     * shows up as a map missing a layer — not something to crash a user's phone over.
     */
    private fun JsonElement?.jsonObjectOrEmpty(): JsonObject =
        this as? JsonObject ?: JsonObject(emptyMap())
}

/**
 * MapLibre's own URL scheme for a PMTiles archive. Absolute path only — its reader
 * rejects a relative one, and that failure surfaces as an empty map rather than as
 * an error, so the path is settled at import time in [MapProviders].
 */
internal fun pmtilesUrl(path: String) = "pmtiles://$path"
