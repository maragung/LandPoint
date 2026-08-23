package com.landpoint.app.map

import com.landpoint.app.data.BasemapMode
import java.io.File

/**
 * One choosable map style, joined to the tiles that draw it.
 *
 * [BasemapMode] is the half the user sees — a name, a summary, an attribution line
 * and a stored preference key. This is the half the engine sees. They are kept
 * apart because they change for different reasons: swapping the street tiles for a
 * different vector host is a [TileSpec] edit and nothing else, while renaming
 * "Satellite" to "Aerial" touches no map code at all.
 *
 * @param minZoom the shallowest zoom worth requesting. Zero everywhere so far, but
 *   an archive covering one province has no world-level tiles and says so.
 */
data class MapProvider(
    val mode: BasemapMode,
    val tiles: TileSpec,
    val minZoom: Double,
    val maxZoom: Double
) {
    /** @see BasemapMode.allowsOfflineDownload */
    val allowsOfflineDownload: Boolean get() = mode.allowsOfflineDownload

    /** Whether tiles have to be fetched to draw this at all. */
    val needsNetwork: Boolean get() = mode.needsNetwork

    /**
     * The deepest zoom that has its own tiles, as opposed to the deepest the camera
     * may reach.
     *
     * The two differ for a vector source and are the same for a raster one, which is
     * the whole reason the street map stays crisp at zoom 19 while the satellite
     * imagery goes soft. Read by [com.landpoint.app.map.offline.OfflineBudget] so a
     * download is costed against tiles that exist.
     */
    val tileMaxZoom: Double
        get() = when (val spec = tiles) {
            is TileSpec.VectorStyle -> spec.sourceMaxZoom
            is TileSpec.RasterXyz -> maxZoom
            is TileSpec.LocalArchive -> maxZoom
        }
}

/**
 * The map sources this app ships with.
 *
 * A registry rather than a `when` scattered through the UI: the map tab, the corner
 * picker, the boundary preview, the record thumbnail and the offline downloader all
 * need the same answer to "what draws [BasemapMode.TERRAIN]", and four copies of it
 * is four chances to disagree.
 */
object MapProviders {

    /**
     * The deepest zoom any style here is asked for.
     *
     * The vector street tiles stop at level 14 and MapLibre overzooms past that,
     * scaling the geometry it already has — which is why a vector map stays sharp
     * at 19 while a raster one turns to porridge. The number below is therefore a
     * *view* limit, not a tile limit.
     */
    const val DEEPEST_ZOOM = 19.0

    /**
     * The bundled street style, in the OpenMapTiles schema, served by OpenFreeMap.
     *
     * Exposed on its own as well as through [of] because it is the style an imported
     * archive borrows: the file is read, one source is repointed at the local
     * archive, and the result draws the offline map with the same ink as the online
     * one. The tile URL itself lives inside the style asset rather than here, so
     * there is exactly one place it can be wrong.
     *
     * OpenFreeMap was chosen over the alternatives because it asks for nothing: no
     * registration, no API key, no request ceiling, and commercial use allowed
     * outright. The project is MIT and publishes weekly full-planet archives, which
     * is also why downloading a bounded area from it sits comfortably inside what it
     * is offered for. The one condition is that it is credited, which
     * `map_attribution_osm` does on every screen that draws it.
     */
    val streetStyle = TileSpec.VectorStyle(
        lightAsset = "styles/street.json",
        darkAsset = "styles/street-dark.json",
        vectorSourceId = "openmaptiles",
        // OpenFreeMap's planet tileset stops here and MapLibre overzooms the rest.
        sourceMaxZoom = 14.0
    )

    private val street = MapProvider(
        mode = BasemapMode.STREET,
        tiles = streetStyle,
        minZoom = 0.0,
        maxZoom = DEEPEST_ZOOM
    )

    /**
     * Esri's World Imagery — the aerial photography, and the reason this app can
     * show the actual ground without an API key.
     *
     * Note the template's `{z}/{y}/{x}`. ArcGIS servers are row-major, unlike the
     * z/x/y of every other source here, and getting it the usual way round returns
     * imagery of somewhere else entirely with no error to say so.
     */
    private val satellite = MapProvider(
        mode = BasemapMode.SATELLITE,
        tiles = TileSpec.RasterXyz(
            templates = listOf(
                "https://server.arcgisonline.com/ArcGIS/rest/services/" +
                    "World_Imagery/MapServer/tile/{z}/{y}/{x}"
            ),
            tileSize = 256,
            attribution = "Imagery © Esri, Maxar, Earthstar Geographics"
        ),
        minZoom = 0.0,
        maxZoom = 19.0
    )

    /**
     * OpenTopoMap: OpenStreetMap data plus SRTM relief, published CC-BY-SA — so the
     * credit on screen is a licence condition rather than a courtesy.
     */
    private val terrain = MapProvider(
        mode = BasemapMode.TERRAIN,
        tiles = TileSpec.RasterXyz(
            templates = listOf(
                "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
                "https://b.tile.opentopomap.org/{z}/{x}/{y}.png",
                "https://c.tile.opentopomap.org/{z}/{x}/{y}.png"
            ),
            tileSize = 256,
            attribution = "© OpenStreetMap contributors, SRTM | © OpenTopoMap (CC-BY-SA)"
        ),
        minZoom = 0.0,
        maxZoom = 17.0
    )

    /** Every style that does not depend on the user having imported anything. */
    val builtIn: List<MapProvider> = listOf(street, satellite, terrain)

    /**
     * The provider to draw with, from what the user chose and what is on the device.
     *
     * The single entry point for the UI, so the two halves of the decision — which
     * mode survives contact with reality, and what draws it — are always made
     * together. It cannot fail: [BasemapMode.resolve] returns
     * [BasemapMode.IMPORTED] only when there is an archive to draw it from, which is
     * the one case [of] has no answer for. The fallback is there so a future mode
     * cannot turn a missing branch into a blank screen.
     */
    fun resolved(chosen: BasemapMode?, archive: ImportedArchiveSpec?): MapProvider =
        of(BasemapMode.resolve(chosen, archive != null), archive) ?: street

    /**
     * The provider that draws [mode], or null when [BasemapMode.IMPORTED] is asked
     * for and there is no archive to draw.
     *
     * Null rather than a silent fallback to the street tiles: the caller already has
     * [BasemapMode.resolve] for that decision, and making it twice in two places is
     * how the two come to disagree.
     */
    fun of(mode: BasemapMode, archive: ImportedArchiveSpec?): MapProvider? = when (mode) {
        BasemapMode.STREET -> street
        BasemapMode.SATELLITE -> satellite
        BasemapMode.TERRAIN -> terrain
        BasemapMode.IMPORTED -> archive?.let { spec ->
            MapProvider(
                mode = BasemapMode.IMPORTED,
                tiles = TileSpec.LocalArchive(spec.file.absolutePath, spec.vector),
                minZoom = spec.minZoom,
                maxZoom = DEEPEST_ZOOM
            )
        }
    }
}

/**
 * An imported archive, described well enough to build a style from.
 *
 * Carries [vector] and [minZoom] rather than re-reading the file, because both come
 * out of the PMTiles header and reading a header on the main thread once per
 * recomposition is how a map screen starts to stutter.
 */
data class ImportedArchiveSpec(
    val file: File,
    val vector: Boolean,
    val minZoom: Double = 0.0
)
