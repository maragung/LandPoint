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
     * Never deeper than [maxZoom], and usually shallower — the street map stays crisp
     * at the view limit because MapLibre redraws its geometry there, and the imagery
     * goes soft there because all it can do is enlarge the deepest photograph. For
     * imagery this is a ceiling rather than a promise: aerial coverage varies by place,
     * so it is the deepest tile the source could serve *anywhere*, not the deepest it
     * holds over a particular field. Read by
     * [com.landpoint.app.map.offline.OfflineBudget] so a download is costed against
     * tiles that can exist at all.
     */
    val tileMaxZoom: Double
        get() = when (val spec = tiles) {
            is TileSpec.VectorStyle -> spec.sourceMaxZoom
            is TileSpec.RasterXyz -> spec.detail?.maxZoom ?: spec.sourceMaxZoom
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
     * The deepest zoom the camera may reach, on every style whose source does not
     * insist otherwise.
     *
     * A *view* limit, not a tile limit, and deliberately deeper than any source's
     * deepest tile. The vector street tiles stop at level 14 and MapLibre redraws
     * their geometry at whatever zoom is asked for, so a street map is as sharp here
     * as at 14. Imagery is not: past its last photograph all that can be done is to
     * enlarge it, which is still the right answer for someone standing on a corner
     * they are trying to place — a blurred picture of the correct ground beats a
     * refusal to go closer.
     *
     * The figure is set by what has to be readable rather than by what the tiles
     * hold. MapLibre's world is 512 px per tile, so the scale bar's widest span at
     * 96 dp is `40075017 × cos(lat) / (512 × 2^zoom) × 96` metres: 50 m at zoom 17,
     * 20 m at 18, 10 m at 19, 5 m at 20 and 2 m at 21. A user placing a boundary peg
     * needs to see the metre they are arguing about, and until 1.5.2 the camera
     * stopped one rung short of it.
     */
    const val DEEPEST_ZOOM = 21.0

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
     * Esri's tile endpoint, with the one parameter that makes a missing tile behave
     * like a missing tile.
     *
     * `blankTile=false` turns a 2.5 kB placeholder image reading "Map data not yet
     * available" into an honest 404. Without it the server answers 200 with that
     * picture for ground it has no photograph of, and nothing downstream — not this
     * app, not MapLibre — can tell it from imagery, so it gets drawn over the land at
     * every zoom past the last real tile.
     *
     * Note the `{z}/{y}/{x}`. ArcGIS servers are row-major, unlike the z/x/y of every
     * other source here, and getting it the usual way round returns imagery of
     * somewhere else entirely with no error to say so.
     */
    private const val ESRI_IMAGERY =
        "https://server.arcgisonline.com/ArcGIS/rest/services/" +
            "World_Imagery/MapServer/tile/{z}/{y}/{x}?blankTile=false"

    /**
     * Esri's World Imagery — the aerial photography, and the reason this app can
     * show the actual ground without an API key.
     *
     * Two tiers of the one service, because its coverage is not uniform and nothing
     * readable says where it thins out. Sampled across Indonesia: zoom 18 was there at
     * every point tried, including rural Kalimantan and Papua; zoom 19 at most of them
     * but not all; zoom 20 at none. So 18 is the tier that can be relied on, 19 is
     * asked for as well and simply fails where it is absent, and the camera is allowed
     * past both — at [DEEPEST_ZOOM] what the user sees is the deepest photograph that
     * exists, enlarged four times in a town where the deep tier is served and eight
     * times where only the base is. Soft, and honestly so: the boundary marks are
     * still where they are, and the scale bar beside them still says 2 m.
     */
    private val satellite = MapProvider(
        mode = BasemapMode.SATELLITE,
        tiles = TileSpec.RasterXyz(
            templates = listOf(ESRI_IMAGERY),
            tileSize = 256,
            attribution = "Imagery © Esri, Maxar, Earthstar Geographics",
            sourceMaxZoom = 18.0,
            detail = TileSpec.RasterXyz.Detail(
                templates = listOf(ESRI_IMAGERY),
                minZoom = 19.0,
                maxZoom = 19.0
            )
        ),
        minZoom = 0.0,
        maxZoom = DEEPEST_ZOOM
    )

    /**
     * OpenTopoMap: OpenStreetMap data plus SRTM relief, published CC-BY-SA — so the
     * credit on screen is a licence condition rather than a courtesy.
     *
     * Its tiles stop at 17 and its camera no longer does. That was the other way
     * round until 1.5.2, on the reasoning that enlarging a contour drawn from 30 m
     * elevation samples invents a shape nobody surveyed — true, but it also floored
     * this style's scale bar at 50 m, so a user who preferred relief could not see
     * the metre they were placing a peg to. A blurred contour beside a 10 m bar is
     * the lesser fault: the contour was always an approximation, while a camera that
     * refuses to go closer is a wall. So the camera reaches 19 here, three rungs
     * shallower than the styles whose sources can keep up.
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
            attribution = "© OpenStreetMap contributors, SRTM | © OpenTopoMap (CC-BY-SA)",
            // Still 17: OpenTopoMap publishes nothing deeper, and asking for a tile
            // that does not exist is how a map ends up drawing a server's apology.
            sourceMaxZoom = 17.0
        ),
        minZoom = 0.0,
        // Deeper than the tiles, the way imagery already is — see the note above.
        maxZoom = 19.0
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
