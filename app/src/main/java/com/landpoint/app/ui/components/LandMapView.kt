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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.landpoint.app.util.GeoPoint as LandGeoPoint
import com.landpoint.app.util.NightTiles
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/** Close enough to read a fence line; MAPNIK's tiles stop here too. */
const val PARCEL_ZOOM = 19.0

/** The "you are here" blue, the same one on every map in the app. */
private const val LOCATION_DOT_COLOUR = "#1E88E5"

/** Enough to see the shape as a shape, not a wash of colour over the ground. */
private const val BOUNDARY_FILL_ALPHA = 60

/** Room for a two-digit corner number, and for a fingertip to land on it. */
private const val CORNER_MARKER_DP = 22f

/**
 * The MapView every map screen starts from.
 *
 * Four screens draw a map — the map tab, the corner picker, the boundary
 * preview and the thumbnail on a record — and each needs the same awkward
 * setup: a mapsforge provider when a vector map has been imported (MapView's
 * default provider cannot render one, only [MapsForgeTileProvider] calls
 * `renderTile`), the data connection switched off when there is nothing to
 * fetch, and a resume/pause/detach lifecycle that leaks a tile thread if it is
 * forgotten.
 *
 * Keyed on [vectorSource] because switching provider means a new MapView, and
 * that happens at most once per visit — right after an imported `.map` file
 * finishes loading its headers.
 *
 * @param interactive false for a map that is a picture rather than a control:
 *   no pinch zoom. Pair it with a tappable overlay in front, which is what makes
 *   a mini map inside a scrolling form behave — a pannable map there would fight
 *   the scroll for every drag.
 * @param maxZoomWithoutVector caps zoom when there are no vector tiles, so a
 *   user cannot zoom past MAPNIK's last tile into a blank grey field.
 */
@Composable
fun rememberLandMapView(
    vectorSource: MapsForgeTileSource?,
    interactive: Boolean = true,
    initialZoom: Double? = null,
    maxZoomWithoutVector: Double? = null
): MapView {
    val context = LocalContext.current

    val mapView = remember(vectorSource, interactive) {
        val view = if (vectorSource != null) {
            MapView(
                context,
                MapsForgeTileProvider(
                    SimpleRegisterReceiver(context),
                    vectorSource,
                    SqlTileWriter()
                )
            )
        } else {
            MapView(context)
        }
        view.apply {
            setTileSource(vectorSource ?: TileSourceFactory.MAPNIK)
            // With a local vector map there is nothing to fetch; saying so keeps
            // the map from reaching for a radio the user may have switched off.
            setUseDataConnection(vectorSource == null)
            setMultiTouchControls(interactive)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            if (vectorSource == null && maxZoomWithoutVector != null) {
                setMaxZoomLevel(maxZoomWithoutVector)
            }
            initialZoom?.let { controller.setZoom(it) }
        }
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
 * Recolours the tiles for the theme in force, and puts them back for a light one.
 *
 * Every map in the app calls this, because there is one set of tiles and they are
 * drawn for daylight — see [NightTiles] for what the recolouring does and why
 * osmdroid's own `INVERT_COLORS` was not enough.
 */
fun MapView.applyTileTheme(dark: Boolean) {
    overlayManager.tilesOverlay.setColorFilter(if (dark) nightTileFilter else null)
}

/** One filter for the whole app: it holds nothing but the matrix. */
private val nightTileFilter by lazy { ColorMatrixColorFilter(NightTiles.MATRIX) }

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
