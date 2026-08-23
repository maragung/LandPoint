package com.landpoint.app.ui.components

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.landpoint.app.R
import com.landpoint.app.map.GeoBounds
import com.landpoint.app.map.MapLibreInit
import com.landpoint.app.map.MapStyle
import com.landpoint.app.util.GeoPoint
import com.landpoint.app.util.GeoUtils
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature

/** Close enough to read a fence line, and the deepest any bundled source draws. */
const val PARCEL_ZOOM = 19.0

/** One press of a zoom button. */
private const val ZOOM_STEP = 1.0

/** Long enough to be seen as movement, short enough not to be a wait. */
private const val ZOOM_ANIMATION_MS = 250

/** Breathing room around a fitted boundary, so its corners are not on the bezel. */
private val FIT_PADDING = 48.dp

/**
 * How far from a finger a pin still counts as tapped.
 *
 * Larger than the pins themselves. A corner pin is about ten density-independent
 * pixels across and a fingertip covers several times that, so hit-testing the drawn
 * shape alone would make a correctly aimed tap miss.
 */
private val TAP_RADIUS = 20.dp

/** Breathing room around the stand-in message where a map cannot be drawn. */
private val MESSAGE_PADDING = 24.dp

/**
 * The horizontal span, in pixels, used to measure the map's scale.
 *
 * Wide enough that the two sample points are far apart relative to any rounding in
 * the projection, narrow enough that Mercator's own scale change across it is
 * negligible.
 */
private const val SCALE_BASELINE_PX = 100f

/**
 * Below this the extent of a set of points is treated as a single place.
 *
 * Roughly a centimetre of latitude. Two corners recorded on the same spot, or a
 * walk that never moved, otherwise ask to be fitted to a box with no extent — which
 * resolves to maximum zoom over a blank field.
 */
private const val DEGENERATE_SPAN = 1e-7

/**
 * A closed area drawn on the map: a plot boundary, or the outline being tapped out.
 *
 * @param clickable whether a tap inside it should be reported. False on the corner
 *   picker, where the outline is a drawing in progress and a tap on it means "put a
 *   point here", not "select this shape". Reported as a feature property rather than
 *   enforced by a layer filter, because a filter would stop the shape being *drawn*
 *   as well as tapped.
 */
data class MapShape(
    val id: String,
    val points: List<GeoPoint>,
    val clickable: Boolean = false
)

/** A place marker — one recorded land, at its stored position. */
data class MapPin(
    val id: String,
    val latitude: Double,
    val longitude: Double
)

/**
 * One numbered vertex of a boundary.
 *
 * The number is drawn on the map rather than left to a tooltip because the order of
 * the corners *is* the outline: a user working out why an edge crosses itself has to
 * be able to read the sequence straight off the map.
 */
data class MapCorner(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val selected: Boolean = false
)

/**
 * Where the device thinks it is, and how sure it is.
 *
 * [accuracyM] is Android's own figure, passed through untouched and drawn as a
 * circle of that radius on the ground. Null when the fix carries no estimate, in
 * which case only the dot is drawn — an invented radius would be a claim about
 * precision the receiver never made.
 */
data class MapFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double? = null
)

/**
 * What the user tapped.
 *
 * Reported as one sealed type rather than as four callbacks so the priority between
 * them is decided in one place. A corner sits inside the boundary it belongs to and
 * a boundary sits on the ground, so without a fixed order every screen would have to
 * work out for itself which of the three it meant.
 */
sealed interface MapTap {

    /** A numbered corner. Also how a corner is *selected* while dragging is enabled. */
    data class Corner(val id: String) : MapTap

    /** A land's place marker. */
    data class Pin(val id: String) : MapTap

    /** Inside a shape that asked to be clickable. */
    data class Shape(val id: String) : MapTap

    /**
     * Bare map.
     *
     * @param toleranceM the caller's touch tolerance converted to metres on the
     *   ground at the current zoom. The corner picker needs it to decide whether a
     *   tap landed on an existing edge, and only the map knows the conversion.
     */
    data class Ground(val point: GeoPoint, val toleranceM: Double) : MapTap
}

/**
 * The camera, the zoom buttons and the map's scale, held outside the map view.
 *
 * Two things make this worth its own object rather than a handful of lambdas.
 *
 * The camera is saved state. Screen rotation destroys the map view and builds a new
 * one, and process recreation destroys the whole activity; without somewhere durable
 * to keep the camera, both throw the user back to the view the screen opened with.
 * The previous engine's map screens each kept a `remember { mutableStateOf(false) }`
 * for "have we centred yet", which reset on rotation and re-framed every time.
 *
 * And the camera is asked to move before there is anything to move. `frame` is
 * called from a Compose `update` block that runs before the native map exists and
 * before the view has been measured — a bounding box cannot be fitted to a view of
 * no size — so requests are buffered and replayed. The buffer holds one request,
 * deliberately: two camera moves queued back to back mean the user changed their
 * mind, and only the last one is worth honouring.
 */
@Stable
class LandMapController internal constructor(
    private val cameraState: MutableState<CameraPosition?>,
    private val framedState: MutableState<Boolean>,
    private val fitPaddingPx: Int
) {
    private var view: MapView? = null
    private var map: MapLibreMap? = null
    private var pending: ((MapLibreMap) -> Unit)? = null

    /**
     * Ground metres per screen pixel at the centre of the view, or zero before the
     * map has drawn.
     *
     * Compose state, so the scale bar and anything else showing a distance recomposes
     * as the user pinches.
     *
     * Measured rather than asked for. `Projection.getMetersPerPixelAtLatitude` exists,
     * but whether it answers in screen pixels or in style pixels cannot be settled
     * from the library's bytecode, and being wrong by the display's density factor
     * would silently mis-size the corner picker's edge tolerance — a bug that looks
     * like the app ignoring taps rather than like a unit error. Two points a known
     * number of pixels apart, put through the projection and then through the same
     * distance function the rest of the app uses, cannot be wrong about its units.
     */
    var metresPerPixel: Double by mutableStateOf(0.0)
        private set

    /**
     * The ground rectangle inside the selection frame, or null when nothing is being
     * selected and before the map has drawn.
     *
     * Only sampled once a caller has asked for it with [trackSelection], so the
     * screens that never select an area pay nothing for this existing.
     *
     * Read off the projection at the frame's own corners rather than derived from the
     * camera. Web Mercator's scale changes with latitude, so shrinking the viewport's
     * bounds by a percentage would put the rectangle's south edge somewhere other than
     * where it is drawn — and the whole point of the frame is that what is inside it is
     * what gets downloaded.
     */
    var selection: GeoBounds? by mutableStateOf(null)
        private set

    /** Negative until a caller asks for a selection, which is what switches it on. */
    private var selectionInsetPx: Float = -1f

    /**
     * Starts reporting [selection] for a frame inset [insetPx] from every edge.
     *
     * Idempotent, so it can be called from composition on every frame.
     */
    fun trackSelection(insetPx: Float) {
        if (selectionInsetPx == insetPx) return
        selectionInsetPx = insetPx
        sampleSelection()
    }

    /** One step in. */
    fun zoomIn() = zoomBy(ZOOM_STEP)

    /** One step out. */
    fun zoomOut() = zoomBy(-ZOOM_STEP)

    /**
     * Puts [points] on screen: a fitted box for a real extent, a centred view at
     * [singlePointZoom] for one place.
     *
     * @param animated for a fit the user asked for by pressing a button, where the
     *   movement is what confirms the press. Left off for the fit on opening: a screen
     *   that flies in from a view of the whole planet is a slow way to say hello.
     * @return false when there is nothing to frame yet, which is how a caller knows
     *   to try again once data arrives.
     */
    fun frame(
        points: List<GeoPoint>,
        singlePointZoom: Double = PARCEL_ZOOM,
        animated: Boolean = false
    ): Boolean {
        val target = targetFor(points, singlePointZoom) ?: return false
        onMap { map -> target.applyTo(map, animated, fitPaddingPx) }
        return true
    }

    /**
     * Frames [points] the first time there is anything to frame, and never again.
     *
     * "Never again" survives rotation and process death, because the flag lives in
     * saved state alongside the camera. Panning away and rotating the phone leaves the
     * user where they were, rather than snapping back to the boundary.
     */
    fun frameOnce(points: List<GeoPoint>, singlePointZoom: Double = PARCEL_ZOOM): Boolean {
        if (framedState.value) return false
        if (!frame(points, singlePointZoom, animated = false)) return false
        framedState.value = true
        return true
    }

    internal fun attach(view: MapView, map: MapLibreMap) {
        this.view = view
        this.map = map
        cameraState.value?.let { map.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
        val queued = pending
        pending = null
        queued?.let { onMap(it) }
        sampleScale()
        sampleSelection()
    }

    internal fun detach() {
        settle()
        view = null
        map = null
    }

    /** Called while the camera is moving; keeps the scale bar honest during a pinch. */
    internal fun moving() {
        sampleScale()
        sampleSelection()
    }

    /**
     * Called when the camera stops.
     *
     * Saving here rather than on pause covers every way a screen can go away —
     * rotation, backgrounding, process death — without depending on which callback
     * arrives before the state is written out.
     */
    internal fun settle() {
        val map = map ?: return
        cameraState.value = map.cameraPosition
        sampleScale()
        sampleSelection()
    }

    private fun zoomBy(steps: Double) {
        onMap { map ->
            map.animateCamera(CameraUpdateFactory.zoomBy(steps), ZOOM_ANIMATION_MS)
        }
    }

    /**
     * Runs [work] against a laid-out map, buffering or posting when there is not one
     * yet. A camera update against a view of zero size resolves to maximum zoom.
     */
    private fun onMap(work: (MapLibreMap) -> Unit) {
        val map = map
        if (map == null) {
            pending = work
            return
        }
        if (map.width >= 1f && map.height >= 1f) work(map) else view?.post { this.map?.let(work) }
    }

    private fun sampleScale() {
        val map = map ?: return
        val width = map.width
        val height = map.height
        if (width < SCALE_BASELINE_PX || height < 1f) return
        val y = height / 2f
        val projection = map.projection
        val left = projection.fromScreenLocation(PointF((width - SCALE_BASELINE_PX) / 2f, y))
        val right = projection.fromScreenLocation(PointF((width + SCALE_BASELINE_PX) / 2f, y))
        val metres = GeoUtils.distance(
            left.latitude, left.longitude, right.latitude, right.longitude
        )
        if (metres > 0.0 && metres.isFinite()) metresPerPixel = metres / SCALE_BASELINE_PX
    }

    /**
     * Reads the selection frame's corners back off the map.
     *
     * `min`/`max` rather than assuming which corner is which: a projection asked for
     * a point outside the world clamps it, and a box built from a south edge north of
     * its north edge would fail [GeoBounds]' own contract.
     */
    private fun sampleSelection() {
        val inset = selectionInsetPx
        if (inset < 0f) return
        val map = map ?: return
        val width = map.width
        val height = map.height
        if (width < 2 * inset + 1f || height < 2 * inset + 1f) return
        val projection = map.projection
        val topLeft = projection.fromScreenLocation(PointF(inset, inset))
        val bottomRight = projection.fromScreenLocation(PointF(width - inset, height - inset))
        val south = min(topLeft.latitude, bottomRight.latitude)
        val north = max(topLeft.latitude, bottomRight.latitude)
        val west = min(topLeft.longitude, bottomRight.longitude)
        val east = max(topLeft.longitude, bottomRight.longitude)
        if (!south.isFinite() || !north.isFinite() || !west.isFinite() || !east.isFinite()) return
        selection = GeoBounds(south = south, west = west, north = north, east = east)
    }
}

/**
 * A controller tied to the current screen, surviving rotation and process death.
 *
 * @param fitPadding the margin left around a fitted boundary.
 */
@Composable
fun rememberLandMapController(fitPadding: Dp = FIT_PADDING): LandMapController {
    val camera = rememberSaveable { mutableStateOf<CameraPosition?>(null) }
    val framed = rememberSaveable { mutableStateOf(false) }
    val paddingPx = with(LocalDensity.current) { fitPadding.roundToPx() }
    return remember(paddingPx) { LandMapController(camera, framed, paddingPx) }
}

/**
 * The one map in the app.
 *
 * Four screens draw a map — the map tab, the corner picker, the boundary summary and
 * the record's preview card — and they draw the same things in the same colours
 * because they all come through here. What varies between them is which of the
 * content lists is empty and whether gestures are on, not how a corner looks.
 *
 * Everything is declarative: hand it the shapes, pins, corners and fix that should
 * be on screen and it makes the map match. The previous engine's API was the
 * opposite — clear the overlay list, add objects back, invalidate — which meant every
 * screen carried the same twenty lines of teardown and rebuild, and forgetting the
 * clear leaked a boundary per recomposition.
 *
 * @param style null until the stored basemap preference has been read, which is a
 *   frame or two after the screen opens. The map is composed anyway and simply has
 *   nothing to draw yet — the alternative, defaulting to the street style and
 *   switching when the real answer arrives, means loading two styles and showing the
 *   user a basemap they did not choose on the way to the one they did.
 * @param interactive false for the preview card, which is a picture of a map rather
 *   than a map: no gestures, and the card itself takes the tap.
 * @param touchTolerance how close to something a tap counts as being on it, in
 *   [MapTap.Ground.toleranceM] terms. Zero unless a caller needs it.
 * @param onMoveCorner enables dragging corners. When it is null the corner layer is
 *   still tappable through [onTap]; when it is set, this view takes over the touch
 *   stream for touches that start on a corner, which is why a short press is
 *   reported as [MapTap.Corner] from here rather than by the map's own click
 *   handling.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun LandMap(
    style: MapStyle?,
    controller: LandMapController,
    accentColour: Int,
    modifier: Modifier = Modifier,
    shapes: List<MapShape> = emptyList(),
    pins: List<MapPin> = emptyList(),
    corners: List<MapCorner> = emptyList(),
    fix: MapFix? = null,
    selectedColour: Int = accentColour,
    interactive: Boolean = true,
    contentDescription: String? = null,
    touchTolerance: Dp = 0.dp,
    onTap: ((MapTap) -> Unit)? = null,
    onMoveCorner: ((id: String, latitude: Double, longitude: Double) -> Unit)? = null
) {
    // Checked before anything else here, because every object remembered below is a
    // native one and the first of them would throw on construction. The answer is
    // fixed for the life of the process — it is decided in `Application.onCreate` —
    // so this branch cannot change between recompositions.
    if (!MapLibreInit.isAvailable) {
        MapUnavailable(modifier, contentDescription)
        return
    }

    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val overlay = remember { LandMapOverlay() }

    val tapRadiusPx = with(density) { TAP_RADIUS.toPx() }
    val tolerancePx = with(density) { touchTolerance.toPx() }
    val slopPx = remember(context) {
        ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }

    // Read through state rather than captured, because the listeners below are
    // installed once per map and would otherwise hold the first composition's
    // lambdas for the life of the screen.
    val currentTap = rememberUpdatedState(onTap)
    val currentMove = rememberUpdatedState(onMoveCorner)

    val mapView = remember(context) {
        MapView(
            context,
            MapLibreMapOptions.createFromAttributes(context)
                // The app draws its own attribution, on every screen, in its own
                // typography; MapLibre's logo and info button would be a second copy
                // of the same licence notice fighting the chrome for the corner.
                .logoEnabled(false)
                .attributionEnabled(false)
                .compassEnabled(false)
        ).apply {
            // Not given a Bundle: the camera is restored by the controller from
            // Compose's own saved state, which survives process death in the same
            // place as the rest of the screen's state instead of in a second one.
            onCreate(null)
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    mapView.onStart()
                    started = true
                }
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    resumed = true
                }
                Lifecycle.Event.ON_PAUSE -> {
                    mapView.onPause()
                    resumed = false
                }
                Lifecycle.Event.ON_STOP -> {
                    mapView.onStop()
                    started = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Back down the steps it came up. The renderer's GL surface is released
            // in onStop and the native map in onDestroy, and going straight to
            // destroy from a running state leaves the surface behind — the one leak
            // this engine is easy to write.
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    DisposableEffect(mapView) {
        val app = context.applicationContext
        // The tile cache is native memory the garbage collector cannot reclaim, so
        // nothing frees it unless the map is told to. Registered on the application
        // rather than handled in an activity callback because a map may be composed
        // on a screen whose activity knows nothing about it.
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory()
            }

            @Deprecated("Kept because ComponentCallbacks still declares it.")
            override fun onLowMemory() = mapView.onLowMemory()

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        app.registerComponentCallbacks(callbacks)
        onDispose { app.unregisterComponentCallbacks(callbacks) }
    }

    var maplibre by remember { mutableStateOf<MapLibreMap?>(null) }
    DisposableEffect(mapView) {
        mapView.getMapAsync { maplibre = it }
        onDispose { maplibre = null }
    }

    LaunchedEffect(maplibre, style?.key) {
        val map = maplibre ?: return@LaunchedEffect
        val current = style ?: return@LaunchedEffect
        // Set before the style, so a switch from a source that draws to zoom 19 to
        // one that stops at 17 pulls the camera back rather than leaving it staring
        // at a level with no tiles behind it.
        map.setMinZoomPreference(current.minZoom)
        map.setMaxZoomPreference(current.maxZoom)
        map.setStyle(Style.Builder().fromJson(current.json)) { loaded -> overlay.bind(loaded) }
    }

    LaunchedEffect(maplibre, interactive) {
        val map = maplibre ?: return@LaunchedEffect
        map.uiSettings.apply {
            setAllGesturesEnabled(interactive)
            // North stays up, always. Corner numbers are the only clue to an
            // outline's direction, so a rotated map makes them harder to read rather
            // than easier, and a tilted one puts the ground under a tap somewhere
            // other than under the finger.
            setRotateGesturesEnabled(false)
            setTiltGesturesEnabled(false)
            setCompassEnabled(false)
            setLogoEnabled(false)
            setAttributionEnabled(false)
        }
    }

    DisposableEffect(maplibre) {
        val map = maplibre ?: return@DisposableEffect onDispose { }
        controller.attach(mapView, map)

        val idle = MapLibreMap.OnCameraIdleListener { controller.settle() }
        val moving = MapLibreMap.OnCameraMoveListener { controller.moving() }
        val click = MapLibreMap.OnMapClickListener { point ->
            val handler = currentTap.value
            if (handler == null) {
                false
            } else {
                map.dispatchTap(
                    point = point,
                    radiusPx = tapRadiusPx,
                    toleranceM = tolerancePx * controller.metresPerPixel,
                    onTap = handler
                )
                true
            }
        }
        map.addOnCameraIdleListener(idle)
        map.addOnCameraMoveListener(moving)
        map.addOnMapClickListener(click)

        onDispose {
            map.removeOnCameraIdleListener(idle)
            map.removeOnCameraMoveListener(moving)
            map.removeOnMapClickListener(click)
            controller.detach()
            overlay.unbind()
        }
    }

    val draggable = interactive && onMoveCorner != null
    DisposableEffect(maplibre, draggable) {
        val map = maplibre
        if (map == null || !draggable) return@DisposableEffect onDispose { }
        val dragger = CornerDragger(
            map = map,
            slopPx = slopPx,
            hitRadiusPx = tapRadiusPx,
            onMove = { id, latitude, longitude ->
                currentMove.value?.invoke(id, latitude, longitude)
            },
            onSelect = { id -> currentTap.value?.invoke(MapTap.Corner(id)) }
        )
        mapView.setOnTouchListener { _, event -> dragger.onTouch(event) }
        onDispose { mapView.setOnTouchListener(null) }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = {
            it.describeForAccessibility(contentDescription)
            overlay.set(shapes, pins, corners, fix, accentColour, selectedColour)
        }
    )
}

/**
 * What stands in for the map when there is no renderer to draw one.
 *
 * Takes the same [Modifier] as the map, so a screen laid out around a map keeps its
 * shape, and carries the same content description, so a screen reader is told what
 * this area is for either way.
 */
@Composable
private fun MapUnavailable(modifier: Modifier, contentDescription: String?) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription?.let { this.contentDescription = it } },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.map_engine_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(MESSAGE_PADDING)
        )
    }
}

/**
 * Turns a tap into the most specific thing under it.
 *
 * Always reports something. A tap that hits nothing is still news — it is how a
 * corner gets added — and a screen with nothing to do with bare ground simply passes
 * a handler that ignores [MapTap.Ground].
 */
private fun MapLibreMap.dispatchTap(
    point: LatLng,
    radiusPx: Float,
    toleranceM: Double,
    onTap: (MapTap) -> Unit
) {
    val screen = projection.toScreenLocation(point)
    val box = hitBox(screen.x, screen.y, radiusPx)

    idAt(box, LandMapOverlay.CORNER_LAYERS)?.let { return onTap(MapTap.Corner(it)) }
    idAt(box, LandMapOverlay.PIN_LAYERS)?.let { return onTap(MapTap.Pin(it)) }
    clickableShapeAt(box)?.let { return onTap(MapTap.Shape(it)) }

    onTap(MapTap.Ground(GeoPoint(point.latitude, point.longitude), toleranceM))
}

private fun hitBox(x: Float, y: Float, radiusPx: Float) =
    RectF(x - radiusPx, y - radiusPx, x + radiusPx, y + radiusPx)

private fun MapLibreMap.idAt(box: RectF, layers: Array<String>): String? =
    queryRenderedFeatures(box, *layers).firstNotNullOfOrNull { it.overlayId() }

private fun MapLibreMap.clickableShapeAt(box: RectF): String? =
    queryRenderedFeatures(box, *LandMapOverlay.SHAPE_LAYERS)
        .firstOrNull { it.stringOrNull(LandMapOverlay.PROP_CLICKABLE) == LandMapOverlay.TRUE }
        ?.overlayId()

private fun Feature.overlayId(): String? = stringOrNull(LandMapOverlay.PROP_ID)

/**
 * A feature property, or null when it is absent.
 *
 * `getStringProperty` on its own assumes the property is there, and a feature from a
 * layer this app did not add — a label from the basemap caught by a wide hit box —
 * has none of these.
 */
private fun Feature.stringOrNull(key: String): String? =
    if (hasNonNullValueForProperty(key)) getStringProperty(key) else null

private fun MapView.describeForAccessibility(description: String?) {
    contentDescription = description
    importantForAccessibility =
        if (description == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO
        else View.IMPORTANT_FOR_ACCESSIBILITY_YES
}

/**
 * Drags a corner under the finger, and reports a short press as a selection.
 *
 * Sits in front of the map's own gesture handling, and only claims a touch that
 * starts on a corner — every other touch is handed straight back, so panning and
 * pinching are untouched.
 *
 * Because it claims the whole gesture including the initial press, the map's click
 * listener never sees a tap that began on a corner. That is why a release inside the
 * touch slop is reported as a selection from here: without it, tapping a corner to
 * select it would do nothing on exactly the screen where selecting corners matters.
 */
private class CornerDragger(
    private val map: MapLibreMap,
    private val slopPx: Float,
    private val hitRadiusPx: Float,
    private val onMove: (String, Double, Double) -> Unit,
    private val onSelect: (String) -> Unit
) {
    private var dragging: String? = null
    private var moved = false
    private var downX = 0f
    private var downY = 0f

    fun onTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            val id = map.idAt(hitBox(event.x, event.y, hitRadiusPx), LandMapOverlay.CORNER_LAYERS)
            dragging = id
            moved = false
            downX = event.x
            downY = event.y
            id != null
        }

        MotionEvent.ACTION_MOVE -> {
            val id = dragging
            if (id == null) {
                false
            } else {
                // Nothing moves until the finger has travelled far enough to mean it.
                // Corners are placed to the metre and a press that shifts three
                // pixels is a tap, not a correction.
                if (moved || hypot(event.x - downX, event.y - downY) >= slopPx) {
                    moved = true
                    val at = map.projection.fromScreenLocation(PointF(event.x, event.y))
                    onMove(id, at.latitude, at.longitude)
                }
                true
            }
        }

        MotionEvent.ACTION_UP -> {
            val id = dragging
            dragging = null
            if (id == null) {
                false
            } else {
                if (!moved) onSelect(id)
                true
            }
        }

        MotionEvent.ACTION_CANCEL -> {
            val had = dragging != null
            dragging = null
            had
        }

        // A second finger landing mid-drag stays with the drag rather than starting a
        // pinch the map never saw the beginning of. The drag follows pointer zero, so
        // it carries on from where it was.
        else -> dragging != null
    }
}

/** Where the camera should end up, worked out before there is a map to ask. */
private sealed interface CameraTarget {

    data class Fit(val bounds: LatLngBounds) : CameraTarget

    data class Centre(val at: LatLng, val zoom: Double) : CameraTarget

    fun applyTo(map: MapLibreMap, animated: Boolean, paddingPx: Int) {
        val update: CameraUpdate = when (this) {
            is Fit -> CameraUpdateFactory.newLatLngBounds(bounds, usablePadding(map, paddingPx))
            is Centre -> CameraUpdateFactory.newLatLngZoom(at, zoom)
        }
        if (animated) map.animateCamera(update, ZOOM_ANIMATION_MS) else map.moveCamera(update)
    }

    /**
     * Padding that still leaves something to fit into.
     *
     * A preview card is a couple of hundred pixels tall, and the default margin
     * doubled would consume it — leaving MapLibre to fit a boundary into a strip of
     * no height.
     */
    private fun usablePadding(map: MapLibreMap, paddingPx: Int): Int {
        val smallest = minOf(map.width, map.height)
        return paddingPx.coerceAtMost((smallest / 4f).toInt().coerceAtLeast(0))
    }
}

/**
 * The camera position that shows [points], or null when there is nothing to show.
 *
 * A set of points with no extent — one corner, or several recorded on the same spot —
 * cannot be fitted to, so it becomes a centred view at [singlePointZoom] instead.
 */
private fun targetFor(points: List<GeoPoint>, singlePointZoom: Double): CameraTarget? {
    val first = points.firstOrNull() ?: return null
    var north = first.latitude
    var south = first.latitude
    var east = first.longitude
    var west = first.longitude
    for (point in points) {
        if (point.latitude > north) north = point.latitude
        if (point.latitude < south) south = point.latitude
        if (point.longitude > east) east = point.longitude
        if (point.longitude < west) west = point.longitude
    }
    if (north - south < DEGENERATE_SPAN && east - west < DEGENERATE_SPAN) {
        return CameraTarget.Centre(LatLng(first.latitude, first.longitude), singlePointZoom)
    }
    return CameraTarget.Fit(
        LatLngBounds.Builder()
            .include(LatLng(north, east))
            .include(LatLng(south, west))
            .build()
    )
}
