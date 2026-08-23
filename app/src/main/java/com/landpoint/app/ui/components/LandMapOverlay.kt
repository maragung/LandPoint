package com.landpoint.app.ui.components

import android.graphics.Color as AndroidColor
import com.landpoint.app.util.GeoPoint
import com.landpoint.app.util.GeoUtils
import java.util.Locale
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Everything the app draws on top of a basemap, as five GeoJSON sources and eight
 * layers.
 *
 * The previous engine drew each of these as its own view object: one `Polygon` per
 * boundary, one `Marker` per corner, one more per land. That does not scale — a
 * boundary walked rather than tapped can carry several hundred points, and several
 * hundred marker views is a stutter the user feels while panning. Here the count of
 * *layers* is fixed and only the contents of a source change, so redrawing a
 * six-hundred-point track costs one `setGeoJson` call.
 *
 * The other reason for a single place: every screen must agree on what a corner
 * looks like. A number in a filled circle on the picker and a plain dot on the
 * preview would leave the user checking two different maps of the same land.
 *
 * ### Why shapes are drawn from geometry rather than icons
 *
 * Nothing here uses a bitmap. Circles, lines, fills and glyph text are sized in
 * style units, which MapLibre scales by the display's pixel ratio, so one number
 * gives the same physical size on every phone. A bitmap would have to be rasterised
 * per density and re-registered whenever the theme colour changed.
 */
internal class LandMapOverlay {

    private var style: Style? = null

    private var accent: Int = AndroidColor.TRANSPARENT
    private var selected: Int = AndroidColor.TRANSPARENT

    private var shapes: FeatureCollection = EMPTY
    private var pins: FeatureCollection = EMPTY
    private var corners: FeatureCollection = EMPTY
    private var accuracy: FeatureCollection = EMPTY
    private var fix: FeatureCollection = EMPTY

    /**
     * Attaches to a freshly loaded style.
     *
     * Called again after every style change, because setting a new style throws away
     * the old one along with everything added to it — the sources and layers below
     * included. Anything already handed to [set] is re-pushed here, so a basemap
     * switch does not blank the boundaries for a frame.
     */
    fun bind(style: Style) {
        this.style = style
        install(style)
        push()
    }

    /** Forgets the style without touching it; the map is being torn down. */
    fun unbind() {
        style = null
    }

    /**
     * Replaces the whole overlay contents.
     *
     * Takes everything at once rather than offering a setter per kind because the
     * caller is a Compose `update` block that already holds all of it, and a partial
     * update would need a diff to know what to leave alone.
     */
    fun set(
        shapes: List<MapShape>,
        pins: List<MapPin>,
        corners: List<MapCorner>,
        fix: MapFix?,
        accentColour: Int,
        selectedColour: Int
    ) {
        val recolour = accentColour != accent || selectedColour != selected
        accent = accentColour
        selected = selectedColour

        this.shapes = FeatureCollection.fromFeatures(shapes.mapNotNull { it.toFeature() })
        this.pins = FeatureCollection.fromFeatures(pins.map { it.toFeature() })
        this.corners = FeatureCollection.fromFeatures(corners.map { it.toFeature() })
        this.accuracy = FeatureCollection.fromFeatures(
            listOfNotNull(fix?.toAccuracyFeature())
        )
        this.fix = FeatureCollection.fromFeatures(listOfNotNull(fix?.toDotFeature()))

        if (recolour) applyColours()
        push()
    }

    /**
     * Adds the sources and layers, in draw order.
     *
     * Boundaries go underneath so a corner pin is never hidden by the fill of the
     * shape it belongs to, and the position dot sits between the two: above the land
     * so it can be seen inside a filled outline, below the pins so it cannot cover
     * the corner the user is trying to tap.
     */
    private fun install(style: Style) {
        style.addSource(GeoJsonSource(SRC_SHAPES, shapes))
        style.addSource(GeoJsonSource(SRC_ACCURACY, accuracy))
        style.addSource(GeoJsonSource(SRC_FIX, fix))
        style.addSource(GeoJsonSource(SRC_PINS, pins))
        style.addSource(GeoJsonSource(SRC_CORNERS, corners))

        style.addLayer(
            FillLayer(LAYER_SHAPE_FILL, SRC_SHAPES).withProperties(
                PropertyFactory.fillColor(accent),
                PropertyFactory.fillOpacity(SHAPE_FILL_OPACITY)
            )
        )
        style.addLayer(
            LineLayer(LAYER_SHAPE_LINE, SRC_SHAPES).withProperties(
                PropertyFactory.lineColor(accent),
                PropertyFactory.lineWidth(SHAPE_LINE_WIDTH),
                // Round joins matter more here than on a road: a plot boundary is
                // full of acute corners, and mitred ones grow long spikes there.
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        style.addLayer(
            FillLayer(LAYER_ACCURACY, SRC_ACCURACY).withProperties(
                PropertyFactory.fillColor(FIX_COLOUR),
                PropertyFactory.fillOpacity(ACCURACY_OPACITY),
                PropertyFactory.fillOutlineColor(FIX_COLOUR)
            )
        )
        style.addLayer(
            CircleLayer(LAYER_FIX, SRC_FIX).withProperties(
                PropertyFactory.circleRadius(FIX_RADIUS),
                PropertyFactory.circleColor(FIX_COLOUR),
                PropertyFactory.circleStrokeWidth(FIX_STROKE),
                PropertyFactory.circleStrokeColor(AndroidColor.WHITE)
            )
        )
        // A translucent disc under each land pin. It is what makes a pin read as a
        // place rather than as one more numbered corner, without a second icon.
        style.addLayer(
            CircleLayer(LAYER_PIN_HALO, SRC_PINS).withProperties(
                PropertyFactory.circleRadius(PIN_HALO_RADIUS),
                PropertyFactory.circleColor(accent),
                PropertyFactory.circleOpacity(PIN_HALO_OPACITY)
            )
        )
        style.addLayer(
            CircleLayer(LAYER_PIN, SRC_PINS).withProperties(
                PropertyFactory.circleRadius(PIN_RADIUS),
                PropertyFactory.circleColor(accent),
                PropertyFactory.circleStrokeWidth(PIN_STROKE),
                PropertyFactory.circleStrokeColor(AndroidColor.WHITE)
            )
        )
        style.addLayer(
            CircleLayer(LAYER_CORNER, SRC_CORNERS).withProperties(
                PropertyFactory.circleRadius(cornerRadius()),
                PropertyFactory.circleColor(cornerColour()),
                PropertyFactory.circleStrokeWidth(cornerStroke()),
                PropertyFactory.circleStrokeColor(AndroidColor.WHITE)
            )
        )
        style.addLayer(
            SymbolLayer(LAYER_CORNER_LABEL, SRC_CORNERS).withProperties(
                PropertyFactory.textField(Expression.get(PROP_LABEL)),
                // Every style this app builds declares the bundled Noto glyphs,
                // including the raster ones, precisely so this layer has letters to
                // draw over satellite imagery as well as over the street map.
                PropertyFactory.textFont(arrayOf(LABEL_FONT)),
                PropertyFactory.textSize(cornerTextSize()),
                PropertyFactory.textColor(AndroidColor.WHITE),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_CENTER),
                // The numbers are the outline's order, so they are never allowed to
                // drop out for want of room: a missing "4" reads as a missing corner.
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true)
            )
        )
    }

    /** Repaints the layers that follow the theme, without rebuilding them. */
    private fun applyColours() {
        val style = style ?: return
        style.getLayerAs<FillLayer>(LAYER_SHAPE_FILL)
            ?.setProperties(PropertyFactory.fillColor(accent))
        style.getLayerAs<LineLayer>(LAYER_SHAPE_LINE)
            ?.setProperties(PropertyFactory.lineColor(accent))
        style.getLayerAs<CircleLayer>(LAYER_PIN_HALO)
            ?.setProperties(PropertyFactory.circleColor(accent))
        style.getLayerAs<CircleLayer>(LAYER_PIN)
            ?.setProperties(PropertyFactory.circleColor(accent))
        style.getLayerAs<CircleLayer>(LAYER_CORNER)
            ?.setProperties(PropertyFactory.circleColor(cornerColour()))
    }

    private fun push() {
        val style = style ?: return
        // Not `isFullyLoaded`, which also waits on tiles: the sources were added by
        // [install] on this same style object, so they exist whether or not the
        // network has produced anything yet.
        style.getSourceAs<GeoJsonSource>(SRC_SHAPES)?.setGeoJson(shapes)
        style.getSourceAs<GeoJsonSource>(SRC_ACCURACY)?.setGeoJson(accuracy)
        style.getSourceAs<GeoJsonSource>(SRC_FIX)?.setGeoJson(fix)
        style.getSourceAs<GeoJsonSource>(SRC_PINS)?.setGeoJson(pins)
        style.getSourceAs<GeoJsonSource>(SRC_CORNERS)?.setGeoJson(corners)
    }

    /** Selected corners are bigger, ringed thicker and painted apart. */
    private fun cornerColour(): Expression = Expression.switchCase(
        isSelected, Expression.literal(colourString(selected)), Expression.literal(colourString(accent))
    )

    private fun cornerRadius(): Expression = Expression.switchCase(
        isSelected, Expression.literal(CORNER_RADIUS_ON), Expression.literal(CORNER_RADIUS)
    )

    private fun cornerStroke(): Expression = Expression.switchCase(
        isSelected, Expression.literal(CORNER_STROKE_ON), Expression.literal(CORNER_STROKE)
    )

    private fun cornerTextSize(): Expression = Expression.switchCase(
        isSelected, Expression.literal(CORNER_TEXT_ON), Expression.literal(CORNER_TEXT)
    )

    private val isSelected: Expression
        get() = Expression.eq(Expression.get(PROP_SELECTED), TRUE)

    internal companion object {

        private val EMPTY: FeatureCollection = FeatureCollection.fromFeatures(emptyList<Feature>())

        const val SRC_SHAPES = "lp-shapes"
        const val SRC_PINS = "lp-pins"
        const val SRC_CORNERS = "lp-corners"
        const val SRC_ACCURACY = "lp-accuracy"
        const val SRC_FIX = "lp-fix"

        const val LAYER_SHAPE_FILL = "lp-shape-fill"
        const val LAYER_SHAPE_LINE = "lp-shape-line"
        const val LAYER_ACCURACY = "lp-accuracy-fill"
        const val LAYER_FIX = "lp-fix-dot"
        const val LAYER_PIN_HALO = "lp-pin-halo"
        const val LAYER_PIN = "lp-pin-dot"
        const val LAYER_CORNER = "lp-corner-dot"
        const val LAYER_CORNER_LABEL = "lp-corner-label"

        /**
         * Tap targets, in the order a tap is offered to them.
         *
         * Corners before pins before shapes, because the smallest thing under the
         * finger is nearly always the one meant — and because a corner sits inside
         * the boundary it belongs to, so the other order would make a corner
         * untappable.
         */
        val CORNER_LAYERS = arrayOf(LAYER_CORNER, LAYER_CORNER_LABEL)
        val PIN_LAYERS = arrayOf(LAYER_PIN, LAYER_PIN_HALO)

        /** Both, so an edge can be tapped where the fill is only a hairline wide. */
        val SHAPE_LAYERS = arrayOf(LAYER_SHAPE_FILL, LAYER_SHAPE_LINE)

        const val PROP_ID = "lp-id"
        const val PROP_LABEL = "lp-label"
        const val PROP_SELECTED = "lp-selected"
        const val PROP_CLICKABLE = "lp-clickable"

        const val TRUE = "1"
        const val FALSE = "0"

        private const val LABEL_FONT = "Noto Sans Bold"

        /** MapLibre's blue for a device position, kept from the previous engine. */
        private const val FIX_COLOUR = 0xFF1E88E5.toInt()

        private const val SHAPE_FILL_OPACITY = 0.22f
        private const val SHAPE_LINE_WIDTH = 3f
        private const val ACCURACY_OPACITY = 0.16f
        private const val FIX_RADIUS = 7f
        private const val FIX_STROKE = 2.5f
        private const val PIN_RADIUS = 8f
        private const val PIN_STROKE = 2.5f
        private const val PIN_HALO_RADIUS = 15f
        private const val PIN_HALO_OPACITY = 0.28f
        private const val CORNER_RADIUS = 10f
        private const val CORNER_RADIUS_ON = 13f
        private const val CORNER_STROKE = 1.5f
        private const val CORNER_STROKE_ON = 3f
        private const val CORNER_TEXT = 11f
        private const val CORNER_TEXT_ON = 13f

        /**
         * Vertices in the drawn accuracy circle.
         *
         * Sixty is smooth at any zoom a phone can show and cheap enough to rebuild
         * on every location update, which happens as often as once a second.
         */
        private const val ACCURACY_SIDES = 60

        /**
         * `#rrggbb` for a colour that has to travel inside an expression.
         *
         * [PropertyFactory] has an `int` overload, but [Expression.literal] takes only
         * a string or a number, and a raw ARGB integer handed to it would be read as a
         * number rather than a colour. Alpha is dropped deliberately: every colour
         * that reaches here is opaque, and opacity is set as its own property so it
         * can be reasoned about on screen.
         */
        private fun colourString(colour: Int): String =
            String.format(Locale.US, "#%06X", 0xFFFFFF and colour)

        /**
         * Geometry for one shape, or null when there is not enough of it to draw.
         *
         * Three points or more make a ring, which the fill layer paints and the line
         * layer outlines. Two make a line — the state a boundary passes through while
         * it is being tapped out, where a "polygon" would be a there-and-back sliver.
         * One point is nothing either layer can draw, and the caller shows those as
         * corners anyway.
         */
        private fun MapShape.toFeature(): Feature? {
            val ring = points.map { Point.fromLngLat(it.longitude, it.latitude) }
            val geometry = when {
                ring.size >= 3 -> Polygon.fromLngLats(listOf(ring + ring.first()))
                ring.size == 2 -> LineString.fromLngLats(ring)
                else -> return null
            }
            return Feature.fromGeometry(geometry).apply {
                addStringProperty(PROP_ID, id)
                addStringProperty(PROP_CLICKABLE, if (clickable) TRUE else FALSE)
            }
        }

        private fun MapPin.toFeature(): Feature =
            Feature.fromGeometry(Point.fromLngLat(longitude, latitude)).apply {
                addStringProperty(PROP_ID, id)
            }

        private fun MapCorner.toFeature(): Feature =
            Feature.fromGeometry(Point.fromLngLat(longitude, latitude)).apply {
                addStringProperty(PROP_ID, id)
                addStringProperty(PROP_LABEL, label)
                addStringProperty(PROP_SELECTED, if (selected) TRUE else FALSE)
            }

        /**
         * The accuracy radius as a real circle on the ground.
         *
         * Walked out with [GeoUtils.destination] rather than drawn as a screen-space
         * circle, so what the user sees is the metre figure Android reported — it
         * shrinks as the fix improves, stays the same size on the ground as they zoom,
         * and is not a fixed number of pixels pretending to be a distance.
         */
        private fun MapFix.toAccuracyFeature(): Feature? {
            val radius = accuracyM ?: return null
            if (radius <= 0.0) return null
            val ring = (0..ACCURACY_SIDES).map { step ->
                val bearing = step * 360.0 / ACCURACY_SIDES
                val edge = GeoUtils.destination(latitude, longitude, bearing, radius)
                Point.fromLngLat(edge.longitude, edge.latitude)
            }
            return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
        }

        private fun MapFix.toDotFeature(): Feature =
            Feature.fromGeometry(Point.fromLngLat(longitude, latitude))
    }
}

/** Convenience for the callers that hold [GeoPoint] lists rather than shapes. */
internal fun List<GeoPoint>.asShape(id: String, clickable: Boolean = false) =
    MapShape(id = id, points = this, clickable = clickable)
