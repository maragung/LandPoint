package com.landpoint.app.map

/**
 * Where one map style's imagery comes from, and in what shape it arrives.
 *
 * The point of naming these separately from [MapProvider] is that the *engine*
 * only ever needs to know one thing about a source — how to turn it into a
 * MapLibre style JSON — while the rest of the app cares about labels, licences and
 * zoom limits. Adding a fifth source means adding a case here and a row in
 * [MapProviders]; nothing in the UI has to change.
 */
sealed interface TileSpec {

    /**
     * Vector tiles drawn from a style JSON that ships inside the APK.
     *
     * Bundled rather than fetched, for two reasons. The map draws on the first
     * frame of a cold start with no network round trip, and — more importantly —
     * the same style file can be repointed at a local archive, which is what makes
     * an imported offline map look like the online one instead of like a different
     * app.
     *
     * @param lightAsset asset path of the daylight style.
     * @param darkAsset asset path of the night style. A separate file, not a filter:
     *   there is no way to recolour a vector style after the fact, because the
     *   colours are per-layer decisions about ink on paper, not a transform of a
     *   finished image.
     * @param vectorSourceId the id of the vector source inside those files. Needed
     *   because [MapStyleFactory] repoints exactly that one source when the tiles
     *   are to come from a local archive rather than the network.
     * @param sourceMaxZoom the deepest zoom the source actually publishes, which for
     *   a vector source is far shallower than the zoom a user can see — MapLibre
     *   scales the deepest geometry it has up to whatever the camera asks for. Needed
     *   separately from the view limit because it is the number that decides how many
     *   tiles a download really costs: quoting a village saved "to zoom 19" against
     *   the view limit would overstate it by orders of magnitude.
     */
    data class VectorStyle(
        val lightAsset: String,
        val darkAsset: String,
        val vectorSourceId: String,
        val sourceMaxZoom: Double
    ) : TileSpec {

        /** The style file for the theme in force. */
        fun assetFor(night: Boolean): String = if (night) darkAsset else lightAsset
    }

    /**
     * Ready-made raster images on an XYZ grid, for which the style JSON is
     * generated rather than stored.
     *
     * A generated style needs nothing at all off the network beyond the tiles
     * themselves — its letter shapes resolve to the fonts bundled in the APK and it
     * declares no icon atlas, because a raster layer draws no symbols of its own.
     * That is why satellite and terrain come up instantly even on a phone that has
     * never been online.
     *
     * @param templates one or more URL templates. MapLibre understands `{z}`,
     *   `{x}` and `{y}`, and spreads requests across however many are given.
     * @param attribution shown by MapLibre's own attribution plumbing as well as
     *   by the app's; a licence condition either way.
     */
    data class RasterXyz(
        val templates: List<String>,
        val tileSize: Int,
        val attribution: String
    ) : TileSpec

    /**
     * A PMTiles archive sitting on this device.
     *
     * @param path absolute path. MapLibre's PMTiles reader takes a `pmtiles://`
     *   URL and will not accept a relative one.
     * @param vector whether the archive holds vector tiles or raster images. Read
     *   from the archive's own header at import time rather than guessed from the
     *   file name, because the two need completely different styles and a wrong
     *   guess draws a blank map with no error.
     */
    data class LocalArchive(
        val path: String,
        val vector: Boolean
    ) : TileSpec
}
