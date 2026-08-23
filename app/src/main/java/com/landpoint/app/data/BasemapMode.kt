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
 * @param needsNetwork false only for [IMPORTED]; the map stops reaching for a
 *   radio when there is nothing to fetch on it.
 * @param maxZoom the deepest zoom that still has tiles behind it, or null to
 *   leave the cap to the tile source. Without it a user zooms past the last tile
 *   into a blank grey field and assumes the app broke. Note that a *vector* source
 *   keeps drawing past its own last tile level by overzooming the one below, so
 *   this is the deepest zoom worth requesting, not the deepest worth allowing.
 * @param allowsOfflineDownload whether this source's terms permit storing its
 *   tiles for later. Not a technical limit — every one of these could be
 *   downloaded — but a licence one, and the app enforces it rather than leaving it
 *   to the user to know. Where it is false, [offlineRefusalRes] says why on screen;
 *   a greyed-out button with no reason reads as a bug.
 */
enum class BasemapMode(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val summaryRes: Int,
    @StringRes val attributionRes: Int,
    val tintForNight: Boolean,
    val needsNetwork: Boolean,
    val maxZoom: Double?,
    val allowsOfflineDownload: Boolean,
    @StringRes val offlineRefusalRes: Int? = null
) {
    STREET(
        key = "street",
        labelRes = R.string.basemap_street,
        summaryRes = R.string.basemap_street_summary,
        attributionRes = R.string.map_attribution_osm,
        tintForNight = true,
        needsNetwork = true,
        maxZoom = 19.0,
        // OpenFreeMap: no key, no quota, commercial use allowed, and it publishes
        // the whole planet as a downloadable archive — so a bounded area saved for
        // a field with no signal is squarely within what it is offered for.
        // Attribution is the one condition, and it is on every screen.
        allowsOfflineDownload = true
    ),
    SATELLITE(
        key = "satellite",
        labelRes = R.string.basemap_satellite,
        summaryRes = R.string.basemap_satellite_summary,
        attributionRes = R.string.map_attribution_esri,
        tintForNight = false,
        needsNetwork = true,
        maxZoom = 19.0,
        // Esri's terms do not permit caching the imagery. A user who needs aerial
        // photographs offline has to bring an archive they are licensed to hold,
        // which is what IMPORTED is for.
        allowsOfflineDownload = false,
        offlineRefusalRes = R.string.offline_refused_satellite
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
        maxZoom = 17.0,
        // OpenTopoMap is volunteer-run and its tile usage policy forbids bulk
        // downloading in as many words.
        allowsOfflineDownload = false,
        offlineRefusalRes = R.string.offline_refused_terrain
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
        maxZoom = null,
        // Already on the device. Nothing to download, and nothing to refuse.
        allowsOfflineDownload = false,
        offlineRefusalRes = R.string.offline_refused_imported
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
