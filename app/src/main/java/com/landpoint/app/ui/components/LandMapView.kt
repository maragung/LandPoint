package com.landpoint.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.landpoint.app.data.BasemapMode
import com.landpoint.app.util.GeoPoint as LandGeoPoint
import com.landpoint.app.util.NightTiles
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay

/** Close enough to read a fence line; MAPNIK's tiles stop here too. */
const val PARCEL_ZOOM = 19.0

/** One step of the zoom buttons, and the animation that goes with it. */
private const val ZOOM_ANIMATION_MS = 250L

/** The "you are here" blue, the same one on every map in the app. */
private const val LOCATION_DOT_COLOUR = "#1E88E5"

/** Enough to see the shape as a shape, not a wash of colour over the ground. */
private const val BOUNDARY_FILL_ALPHA = 60

/** Room for a two-digit corner number, and for a fingertip to land on it. */
private const val CORNER_MARKER_DP = 22f

/**
 * How many tiles a style may fetch at once.
 *
 * Two, on donated or goodwill infrastructure. osmdroid's own default is higher,
 * but a boundary is a small area and nobody here is in a hurry — a map that fills
 * in half a second slower costs the user nothing and costs the tile server a
 * great deal less.
 */
private const val TILE_CONCURRENCY = 2

/**
 * Terms every style here is used under: no bulk downloading, no fetching tiles
 * the user has not asked to see, and a real user agent so the operator can tell
 * who is asking.
 */
private val COURTEOUS_TILES = TileSourcePolicy(
    TILE_CONCURRENCY,
    TileSourcePolicy.FLAG_NO_BULK or
        TileSourcePolicy.FLAG_NO_PREVENTIVE or
        TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL
)

/**
 * Esri's World Imagery, the aerial photography behind [BasemapMode.SATELLITE].
 *
 * The same imagery the OpenStreetMap editors show, and the reason this app can
 * offer a real picture of the ground without an API key. Its terms want the
 * source named wherever it is drawn, which is what `map_attribution_esri` does on
 * every screen that shows it.
 *
 * Note the tile path order — zoom, **y**, then x. ArcGIS servers are row-major,
 * unlike the z/x/y of every other source here, and getting it the usual way round
 * silently returns imagery of somewhere else entirely.
 */
private val ESRI_WORLD_IMAGERY: OnlineTileSourceBase = object : OnlineTileSourceBase(
    "EsriWorldImagery",
    0,
    19,
    256,
    "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Imagery © Esri, Maxar, Earthstar Geographics",
    COURTEOUS_TILES
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl +
            MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex)
}

/**
 * OpenTopoMap, the contour and relief style behind [BasemapMode.TERRAIN].
 *
 * OpenStreetMap data plus SRTM elevation, published CC-BY-SA — so the attribution
 * on screen is a licence condition, not a courtesy. Defined here rather than
 * taken from `TileSourceFactory.OpenTopo` so it carries [COURTEOUS_TILES]: the
 * bundled definition has no policy and would let osmdroid hammer a volunteer
 * server harder than this app has any business doing.
 */
private val OPEN_TOPO_MAP: OnlineTileSourceBase = XYTileSource(
    "OpenTopoMap",
    0,
    17,
    256,
    ".png",
    arrayOf(
        "https://a.tile.opentopomap.org/",
        "https://b.tile.opentopomap.org/",
        "https://c.tile.opentopomap.org/"
    ),
    "© OpenStreetMap contributors, SRTM | © OpenTopoMap (CC-BY-SA)",
    COURTEOUS_TILES
)

/**
 * The tiles a [BasemapMode] draws.
 *
 * [vectorSource] is only consulted for [BasemapMode.IMPORTED], and a null there
 * means the user asked for an offline map that is no longer present — the street
 * tiles are a better answer than an empty screen.
 */
fun tileSourceFor(mode: BasemapMode, vectorSource: MapsForgeTileSource?): ITileSource =
    when (mode) {
        BasemapMode.STREET -> TileSourceFactory.MAPNIK
        BasemapMode.SATELLITE -> ESRI_WORLD_IMAGERY
        BasemapMode.TERRAIN -> OPEN_TOPO_MAP
        BasemapMode.IMPORTED -> vectorSource ?: TileSourceFactory.MAPNIK
    }

/**
 * The MapView every map screen starts from.
 *
 * Four screens draw a map — the map tab, the corner picker, the boundary preview
 * and the thumbnail on a record — and each needs the same awkward setup: a
 * mapsforge provider when the chosen style is an imported vector map (MapView's
 * default provider cannot render one, only [MapsForgeTileProvider] calls
 * `renderTile`), the data connection switched off when there is nothing to fetch,
 * and a resume/pause/detach lifecycle that leaks a tile thread if it is forgotten.
 *
 * Only the provider forces a new MapView, and only [BasemapMode.IMPORTED] needs a
 * different one. Every other change of style is applied to the view already on
 * screen, so switching from street to satellite keeps the user exactly where they
 * were looking instead of throwing them back to the start.
 *
 * @param interactive false for a map that is a picture rather than a control:
 *   no pinch zoom. Pair it with a tappable overlay in front, which is what makes
 *   a mini map inside a scrolling form behave — a pannable map there would fight
 *   the scroll for every drag.
 */
@Composable
fun rememberLandMapView(
    vectorSource: MapsForgeTileSource?,
    basemap: BasemapMode = BasemapMode.STREET,
    interactive: Boolean = true,
    initialZoom: Double? = null
): MapView {
    val context = LocalContext.current

    // Null unless the vector map is the thing being drawn. Keyed on this rather
    // than on the source itself so a map file loading in the background — which
    // happens on every visit — cannot pull the view out from under a user who is
    // looking at satellite imagery.
    val providerSource = if (basemap == BasemapMode.IMPORTED) vectorSource else null
    val tileSource = remember(basemap, providerSource) { tileSourceFor(basemap, providerSource) }

    val mapView = remember(providerSource, interactive) {
        val view = if (providerSource != null) {
            MapView(
                context,
                MapsForgeTileProvider(
                    SimpleRegisterReceiver(context),
                    providerSource,
                    SqlTileWriter()
                )
            )
        } else {
            MapView(context)
        }
        view.apply {
            setMultiTouchControls(interactive)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            // Set here as well as in the effect below, so the first frame draws
            // the style that was asked for rather than a flash of the default.
            applyBasemap(tileSource, basemap)
            initialZoom?.let { controller.setZoom(it) }
        }
    }

    LaunchedEffect(mapView, tileSource, basemap) {
        mapView.applyBasemap(tileSource, basemap)
        mapView.invalidate()
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    return mapView
}

/**
 * Points the map at one style's tiles and at nothing else.
 *
 * The zoom clamp at the end is not decoration: the styles do not all reach the
 * same depth, and a user at street-level zoom who switches to the terrain map
 * would otherwise be left staring at the blank grey of tiles that were never
 * published.
 */
private fun MapView.applyBasemap(source: ITileSource, mode: BasemapMode) {
    setTileSource(source)
    // With a local vector map there is nothing to fetch; saying so keeps the map
    // from reaching for a radio the user may have switched off.
    setUseDataConnection(mode.needsNetwork)
    setMaxZoomLevel(mode.maxZoom)
    mode.maxZoom?.let { cap -> if (zoomLevelDouble > cap) controller.setZoom(cap) }
}

/**
 * Recolours the tiles for the theme in force, and puts them back for a light one.
 *
 * Every map in the app calls this, because there is one set of tiles and they are
 * drawn for daylight — see [NightTiles] for what the recolouring does and why
 * osmdroid's own `INVERT_COLORS` was not enough.
 *
 * Photographs and relief shading are left alone whatever the theme: see
 * [BasemapMode.tintForNight]. A satellite image put through an inversion is not a
 * dark map, it is an unreadable one.
 */
fun MapView.applyTileTheme(dark: Boolean, basemap: BasemapMode) {
    val tint = dark && basemap.tintForNight
    overlayManager.tilesOverlay.setColorFilter(if (tint) nightTileFilter else null)
}

/** One filter for the whole app: it holds nothing but the matrix. */
private val nightTileFilter by lazy { ColorMatrixColorFilter(NightTiles.MATRIX) }

/** One zoom step, animated, the same on every map. */
fun MapView.zoomInAStep() {
    controller.zoomIn(ZOOM_ANIMATION_MS)
}

/** @see zoomInAStep */
fun MapView.zoomOutAStep() {
    controller.zoomOut(ZOOM_ANIMATION_MS)
}

/**
 * The scale bar, in the one place it belongs on a map this app draws.
 *
 * Bottom left, because a boundary is a measurement and the first thing anyone does
 * with a drawn plot is judge whether its size looks right. Add it last, after the
 * plots: overlays are drawn in order.
 *
 * @param bottomInsetDp how much of the bottom edge is already spoken for. The bar
 *   is painted onto the map's own canvas, so it knows nothing about the panels
 *   floating over it and has to be told to sit above them — a scale bar hidden
 *   behind a summary card is worse than none, because the map looks like it has one.
 */
fun MapView.addScaleBar(bottomInsetDp: Float = 28f) {
    val density = resources.displayMetrics.density
    overlays.add(
        ScaleBarOverlay(this).apply {
            setAlignBottom(true)
            setScaleBarOffset((density * 12).toInt(), (density * bottomInsetDp).toInt())
        }
    )
}

/**
 * Says in words what the map draws, for a screen reader.
 *
 * osmdroid paints the lot — tiles, plots, numbered corners, the location dot —
 * onto a single canvas, so a service walking the view tree finds one unlabelled
 * box and announces nothing at all. Short of shadowing every overlay with an
 * invisible view there is no way to expose them as separate nodes, so each
 * screen instead states what is on its map, and names the place the same facts
 * can be reached without one.
 *
 * A null [description] takes the map out of the reading order entirely: for a
 * map that is a picture behind a labelled control, the control does the talking
 * and a second stop here would only say the same thing twice.
 */
fun MapView.describeForAccessibility(description: String?) {
    contentDescription = description
    importantForAccessibility =
        if (description == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO
        else View.IMPORTANT_FOR_ACCESSIBILITY_YES
}

/**
 * A boundary as it stands: a filled ring once it closes, a bare line before that.
 *
 * [onClick] null leaves the shape a readout and passes the touch to whatever is
 * underneath — which is what the corner picker needs, so a corner can still be
 * placed inside a shape already drawn. Given a callback, the whole plot becomes
 * the tap target, which on the map tab is a far easier thing to hit than a pin.
 */
fun MapView.drawBoundary(
    points: List<LandGeoPoint>,
    colour: Int,
    onClick: (() -> Unit)? = null
) {
    if (points.size < 2) return
    val ring = points.map { GeoPoint(it.latitude, it.longitude) }

    val overlay = if (points.size >= 3) {
        Polygon(this).apply {
            setPoints(ring)
            fillPaint.color = AndroidColor.argb(
                BOUNDARY_FILL_ALPHA,
                AndroidColor.red(colour),
                AndroidColor.green(colour),
                AndroidColor.blue(colour)
            )
            outlinePaint.color = colour
            outlinePaint.strokeWidth = 4f
            setOnClickListener { _, _, _ ->
                onClick?.invoke()
                onClick != null
            }
        }
    } else {
        Polyline(this).apply {
            setPoints(ring)
            outlinePaint.color = colour
            outlinePaint.strokeWidth = 4f
            setOnClickListener { _, _, _ ->
                onClick?.invoke()
                onClick != null
            }
        }
    }

    overlays.add(overlay)
}

/** Where the phone is, so a drawing has a reference even over blank tiles. */
fun MapView.drawLocationDot(latitude: Double, longitude: Double, title: String? = null) {
    overlays.add(
        Marker(this).apply {
            position = GeoPoint(latitude, longitude)
            this.title = title
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ShapeDrawable(OvalShape()).apply {
                intrinsicWidth = 28
                intrinsicHeight = 28
                paint.color = AndroidColor.parseColor(LOCATION_DOT_COLOUR)
            }
            setOnMarkerClickListener { _, _ -> true }
        }
    )
}

/**
 * A viewport that holds every point given, with room around it, or null when
 * there is nothing to frame.
 *
 * A single point has no extent, so it gets no box — the caller centres on it at
 * a chosen zoom instead, because a zero-sized box would fit to maximum zoom.
 */
fun boundsOf(points: List<LandGeoPoint>): BoundingBox? {
    if (points.size < 2) return null
    val box = BoundingBox.fromGeoPointsSafe(
        points.map { GeoPoint(it.latitude, it.longitude) }
    )
    // Two corners recorded on the same spot, or a walk that never moved, give a
    // box with no extent — and fitting to that asks for maximum zoom over a
    // blank field. Treat it as nothing to frame.
    if (box.latitudeSpan <= 0.0 && box.longitudeSpan <= 0.0) return null
    return box.increaseByScale(1.4f)
}

/**
 * Frames [points], or centres on the single one there is, or does nothing at all
 * when there is nothing to show yet — which the caller can tell from the false it
 * gets back.
 *
 * Posted rather than applied, because a bounding box cannot be fitted to a view
 * that has not been measured yet, and the first call always lands before layout.
 *
 * [animated] for a fit the user asked for by pressing a button, where the movement
 * is what tells them the button did something. Left off for the fit on opening: a
 * screen that flies in from a view of the whole planet is a slow way to say hello.
 */
fun MapView.frame(
    points: List<LandGeoPoint>,
    singlePointZoom: Double = PARCEL_ZOOM,
    animated: Boolean = false
): Boolean {
    boundsOf(points)?.let { box ->
        post { zoomToBoundingBox(box, animated) }
        return true
    }
    val single = points.firstOrNull() ?: return false
    val centre = GeoPoint(single.latitude, single.longitude)
    if (animated) {
        controller.animateTo(centre, singlePointZoom, null)
    } else {
        controller.setZoom(singlePointZoom)
        controller.setCenter(centre)
    }
    return true
}

/**
 * A round, numbered pin for one boundary corner.
 *
 * The number is drawn into the icon rather than left to an info window: the
 * order of the corners *is* the outline, so a user checking why an edge crosses
 * itself needs to read the sequence off the map directly.
 *
 * Sized in dp, unlike the plain dots elsewhere in this file, because a raw pixel
 * size that reads well on one screen becomes an unreadable speck on a dense one
 * once there is text inside it.
 */
fun cornerMarkerIcon(
    context: Context,
    label: String,
    colour: Int,
    highlighted: Boolean = false
): Drawable {
    val density = context.resources.displayMetrics.density
    val size = ((if (highlighted) CORNER_MARKER_DP * 1.4f else CORNER_MARKER_DP) * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val centre = size / 2f
    val radius = centre - density

    canvas.drawCircle(centre, centre, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
    })
    // A white ring keeps the pin legible against grass, water and a dark tile
    // set alike, and thickens when selected so the choice is visible at a glance.
    canvas.drawCircle(centre, centre, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = (if (highlighted) 3f else 1.5f) * density
    })

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textAlign = Paint.Align.CENTER
        textSize = size * if (label.length > 2) 0.38f else 0.5f
        isFakeBoldText = true
    }
    // Centres the glyphs on the circle rather than on the text baseline.
    canvas.drawText(label, centre, centre - (text.descent() + text.ascent()) / 2f, text)

    return BitmapDrawable(context.resources, bitmap)
}
