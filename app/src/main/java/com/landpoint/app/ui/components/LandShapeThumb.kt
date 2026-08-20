package com.landpoint.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.landpoint.app.R
import com.landpoint.app.data.model.Land
import com.landpoint.app.util.GeoPoint
import kotlin.math.cos
import kotlin.math.min

/**
 * The actual outline of a plot, drawn small.
 *
 * A column of names says nothing about which plot is which, but a long strip
 * along a road and a square garden are told apart at a glance. This draws
 * straight from the boundary already loaded with the record — no map tiles, no
 * bitmaps, no disk access — so it costs one path and nothing else.
 *
 * Lands stored as a single point have no outline to draw and get a pin instead.
 */
@Composable
fun LandShapeThumb(
    land: Land,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    // Land.boundary is a getter that re-parses the stored JSON on every read, and
    // a walked boundary can hold hundreds of points. Inside a LazyColumn that
    // would be re-parsed on every frame, so the result is kept per record.
    val corners = remember(land.id, land.geometryJson) { land.boundary }
    val isShape = corners.size >= 3

    val description = stringResource(
        if (isShape) R.string.label_shape_polygon else R.string.label_shape_point
    )
    val outlineColor = MaterialTheme.colorScheme.primary
    val fillColor = outlineColor.copy(alpha = 0.18f)

    Surface(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = description },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        if (!isShape) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size * 0.5f)
                )
            }
        } else {
            Canvas(modifier = Modifier.size(size)) {
                val path = corners.toPath(
                    width = this.size.width,
                    height = this.size.height,
                    inset = this.size.minDimension * 0.16f
                )
                drawPath(path, color = fillColor)
                drawPath(
                    path,
                    color = outlineColor,
                    style = Stroke(width = this.size.minDimension * 0.07f)
                )
            }
        }
    }
}
/**
 * Fits this ring into a [width] × [height] box, [inset] in from each edge.
 *
 * Two corrections keep the outline recognisable as the same shape the user sees
 * on the map:
 *
 * - A degree of longitude is shorter than a degree of latitude by cos(latitude).
 *   Without that factor every plot looks stretched sideways, and badly so away
 *   from the equator.
 * - The scale is the same on both axes, so a long thin strip stays long and
 *   thin. Stretching each axis to fill the box independently would turn every
 *   plot into roughly the same blob, which is precisely what this is for.
 *
 * Screen y grows downwards while latitude grows north, so y is flipped.
 */
private fun List<GeoPoint>.toPath(width: Float, height: Float, inset: Float): Path {
    val path = Path()
    if (isEmpty()) return path

    val minLat = minOf { it.latitude }
    val maxLat = maxOf { it.latitude }
    val minLon = minOf { it.longitude }
    val maxLon = maxOf { it.longitude }

    val midLatRad = Math.toRadians((minLat + maxLat) / 2.0)
    val lonScale = cos(midLatRad).coerceAtLeast(0.01)

    val spanY = (maxLat - minLat).coerceAtLeast(1e-9)
    val spanX = ((maxLon - minLon) * lonScale).coerceAtLeast(1e-9)

    val boxW = (width - inset * 2).coerceAtLeast(1f)
    val boxH = (height - inset * 2).coerceAtLeast(1f)
    val scale = min(boxW / spanX, boxH / spanY)

    // Whatever the shape does not use is split evenly, so it sits centred.
    val offsetX = inset + (boxW - spanX * scale).toFloat() / 2f
    val offsetY = inset + (boxH - spanY * scale).toFloat() / 2f

    forEachIndexed { index, point ->
        val x = offsetX + ((point.longitude - minLon) * lonScale * scale).toFloat()
        // Flipped: north belongs at the top.
        val y = offsetY + ((maxLat - point.latitude) * scale).toFloat()
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
