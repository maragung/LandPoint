package com.landpoint.app.data

import androidx.annotation.StringRes
import com.landpoint.app.R

/**
 * What the map draws underneath the boundaries.
 *
 * A drawn outline over a plain street map answers "where is this plot"; it does
 * not answer "is this the corner by the mango tree, or the one by the ditch".
 * Aerial imagery does, which is why it is here: on photographs a user recognises
 * their own land — the roof, the treeline, the bend in the paddy — and can check
 * a recorded corner against something they can see rather than against a
 * coordinate they have to trust.
 *
 * Four modes, and each one names its source on screen because each licence says
 * to:
 *
 *  - [STREET] OpenStreetMap's standard tiles, the app's default. Names, roads,
 *    buildings; the lightest on data.
 *  - [SATELLITE] Esri's World Imagery. The one that shows the ground itself.
 *    Recent enough to recognise a plot, old enough that a new fence may be
 *    missing — see [SATELLITE]'s summary string, which says so to the user.
 *  - [TERRAIN] OpenTopoMap: contours and relief, for sloping or terraced land
 *    where the shape of the ground matters as much as its outline.
 *  - [IMPORTED] the vector map the user imported themselves. The only mode that
 *    touches no network at all, which in a field with no signal is the only one
 *    that draws anything.
 *
 * @param key what gets persisted, so a stored preference survives these
 *   constants being reordered or renamed.
 * @param tintForNight whether the night recolouring may be applied. False for
 *   photographs and for relief shading: inverting a photograph makes vegetation
 *   magenta, and inverting hillshade turns every valley into a ridge.
 * @param needsNetwork false only for [IMPORTED]; used to switch osmdroid's data
 *   connection off so it stops reaching for a radio there is nothing to fetch on.
 * @param maxZoom the deepest zoom that still has tiles behind it, or null to
 *   leave the cap to the tile source. Without it a user zooms past the last tile
 *   into a blank grey field and assumes the app broke.
 */
enum class BasemapMode(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val summaryRes: Int,
    @StringRes val attributionRes: Int,
    val tintForNight: Boolean,
    val needsNetwork: Boolean,
    val maxZoom: Double?
) {
    STREET(
        key = "street",
        labelRes = R.string.basemap_street,
        summaryRes = R.string.basemap_street_summary,
        attributionRes = R.string.map_attribution_osm,
        tintForNight = true,
        needsNetwork = true,
        maxZoom = 19.0
    ),
    SATELLITE(
        key = "satellite",
        labelRes = R.string.basemap_satellite,
        summaryRes = R.string.basemap_satellite_summary,
        attributionRes = R.string.map_attribution_esri,
        tintForNight = false,
        needsNetwork = true,
        maxZoom = 19.0
    ),
    TERRAIN(
        key = "terrain",
        labelRes = R.string.basemap_terrain,
        summaryRes = R.string.basemap_terrain_summary,
        attributionRes = R.string.map_attribution_topo,
        tintForNight = false,
        needsNetwork = true,
        // OpenTopoMap's last zoom level. Two steps shallower than the street
        // tiles, which is what the cap exists to stop a user walking off.
        maxZoom = 17.0
    ),
    IMPORTED(
        key = "imported",
        labelRes = R.string.basemap_imported,
        summaryRes = R.string.basemap_imported_summary,
        attributionRes = R.string.map_attribution_osm,
        tintForNight = true,
        needsNetwork = false,
        // No cap: a vector map is rendered on the device, so there is no such
        // thing as a zoom with no tile behind it.
        maxZoom = null
    );

    companion object {

        /** Null for an unknown or absent key, which [resolve] then decides. */
        fun fromKey(key: String?): BasemapMode? = entries.firstOrNull { it.key == key }

        /**
         * The mode actually drawn, from what the user chose and what is available.
         *
         * Two cases are not the user's choice to make. A chosen [IMPORTED] with no
         * vector map imported would draw nothing at all, so it falls back to the
         * street tiles. And a user who has never chosen gets [IMPORTED] when they
         * have a vector map — that is what the app did before this setting
         * existed, and someone who went to the trouble of importing a map did so
         * to be able to work without signal.
         *
         * An explicit choice is otherwise honoured even against an imported map:
         * asking for satellite while holding an offline map is a perfectly
         * sensible thing to do with a bar of signal.
         */
        fun resolve(chosen: BasemapMode?, hasVectorMap: Boolean): BasemapMode = when {
            chosen == IMPORTED && !hasVectorMap -> STREET
            chosen != null -> chosen
            hasVectorMap -> IMPORTED
            else -> STREET
        }
    }
}
