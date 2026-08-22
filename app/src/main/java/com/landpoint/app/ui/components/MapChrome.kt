package com.landpoint.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.landpoint.app.R
import com.landpoint.app.data.BasemapMode

/**
 * A map control: smaller than a button in a form, larger than a fingertip.
 *
 * Material asks for 48dp, which is right for a screen made of buttons. These sit
 * *on top of* the thing they control, and every dp of them is a dp of ground the
 * user cannot see — so they come down to the size the map apps everyone already
 * uses have settled on, which is still a comfortable target.
 */
private val MAP_CONTROL_SIZE = 44.dp

/**
 * Solid enough to read an icon against a bright satellite image, sheer enough
 * that the map keeps going underneath. Fully opaque chrome over a map reads as a
 * panel bolted to the screen; this reads as floating above it.
 */
private const val CHROME_ALPHA = 0.92f

/**
 * One round, floating control over a map.
 *
 * A [Surface] rather than an `IconButton` because the button's own minimum touch
 * target would quietly grow the layout past [MAP_CONTROL_SIZE] and leave a column
 * of them unevenly spaced.
 */
@Composable
fun MapControl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(MAP_CONTROL_SIZE),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = CHROME_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription)
        }
    }
}

/**
 * The controls that belong beside the user's thumb: zoom, and "where am I".
 *
 * Zoom buttons alongside pinch, not instead of it. Pinching needs two fingers and
 * a free hand, and someone standing in a field is often holding something in the
 * other one — a stake, a tape, an umbrella.
 *
 * @param onMyLocation null on a map with no location to jump to, which drops the
 *   button rather than leaving a dead one on screen.
 */
@Composable
fun MapSideControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onMyLocation: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MapControl(
            icon = Icons.Default.Add,
            contentDescription = stringResource(R.string.map_zoom_in),
            onClick = onZoomIn
        )
        MapControl(
            icon = Icons.Default.Remove,
            contentDescription = stringResource(R.string.map_zoom_out),
            onClick = onZoomOut
        )
        onMyLocation?.let {
            MapControl(
                icon = Icons.Default.MyLocation,
                contentDescription = stringResource(R.string.map_my_location),
                onClick = it
            )
        }
    }
}

/**
 * The top of a full-screen map: a way back, what is being looked at, and the
 * controls that are about the map rather than about the view.
 *
 * Floating rather than an app bar, because a bar takes a strip of the map away
 * for the whole visit in exchange for showing a title nobody needs to keep
 * reading. The status bar is stepped around, not painted over.
 */
@Composable
fun MapTopChrome(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        onBack?.let {
            MapControl(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                onClick = it
            )
        }
        // One weighted box holds the title and all the space it does not use, so
        // the actions sit against the far edge whether there is a title or not,
        // and a long land name is truncated at the actions rather than pushing
        // them off the screen.
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (title != null) MapTitle(title)
        }
        actions()
    }
}

/** What the map is of, on a chip that keeps it legible over any tiles. */
@Composable
private fun MapTitle(title: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = CHROME_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/** Opens the style chooser. Belongs in [MapTopChrome]'s actions. */
@Composable
fun MapStyleAction(onClick: () -> Unit) {
    MapControl(
        icon = Icons.Default.Layers,
        contentDescription = stringResource(R.string.map_style),
        onClick = onClick
    )
}

/**
 * Puts everything back on screen at once.
 *
 * The one control a map like this cannot do without: pan far enough while looking
 * for a corner and the plot is gone, with nothing on screen to say which way it
 * went.
 */
@Composable
fun MapFitAction(onClick: () -> Unit) {
    MapControl(
        icon = Icons.Default.ZoomOutMap,
        contentDescription = stringResource(R.string.map_fit),
        onClick = onClick
    )
}

/**
 * Names whoever the tiles came from.
 *
 * Not decoration and not optional: every style this app can draw is somebody
 * else's work, offered on the condition that it is credited where it is shown.
 * Small, low-contrast and out of the way — but on screen, always.
 */
@Composable
fun MapAttribution(basemap: BasemapMode, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = stringResource(basemap.attributionRes),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * The style chooser.
 *
 * A sheet rather than a row of chips over the map: each style needs a sentence to
 * choose between them sensibly — which one shows the ground itself, which one
 * works with no signal — and a sentence does not fit on a chip.
 *
 * @param hasVectorMap whether an offline vector map has been imported.
 *   [BasemapMode.IMPORTED] is shown either way, because a user who has never
 *   imported one has no other way to find out the option exists; it is simply not
 *   selectable, and says what to do about that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasemapSheet(
    current: BasemapMode,
    hasVectorMap: Boolean,
    onPick: (BasemapMode) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Text(
                stringResource(R.string.map_style),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            BasemapMode.entries.forEach { mode ->
                val available = mode != BasemapMode.IMPORTED || hasVectorMap
                BasemapRow(
                    mode = mode,
                    selected = mode == current,
                    available = available,
                    onPick = {
                        onPick(mode)
                        onDismiss()
                    }
                )
            }

            Text(
                stringResource(R.string.map_style_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun BasemapRow(
    mode: BasemapMode,
    selected: Boolean,
    available: Boolean,
    onPick: () -> Unit
) {
    val labelColour =
        if (available) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = available, onClick = onPick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Null onClick: the whole row is the target, and a radio button with its
        // own would be a second, smaller one inside it.
        RadioButton(selected = selected, onClick = null, enabled = available)
        Column {
            Text(
                stringResource(mode.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = labelColour
            )
            Text(
                stringResource(
                    if (available) mode.summaryRes else R.string.basemap_imported_missing
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
