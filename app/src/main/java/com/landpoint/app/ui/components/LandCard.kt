package com.landpoint.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.landpoint.app.R
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.data.model.Land
import com.landpoint.app.util.AreaFormat
import com.landpoint.app.util.GeoUtils

/**
 * A land in the list.
 *
 * [selecting] switches the card into bulk-selection mode: a tap then toggles the
 * tick rather than opening the land, which is what makes ticking a run of ten
 * plots bearable. Long-press starts selection from anywhere, so the mode is
 * reachable without a separate toolbar hunt.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LandCard(
    land: Land,
    dms: Boolean,
    imperial: Boolean,
    areaUnit: SettingsRepository.AreaUnit,
    onClick: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selecting && onToggleSelect != null) onToggleSelect() else onClick() },
                onLongClick = onToggleSelect?.let {
                    {
                        // Selection mode changes what a tap does, so it needs to
                        // announce itself by more than a checkbox appearing.
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                }
            ),
        colors = if (selected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LandShapeThumb(land = land)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    land.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Area leads the row and carries no icon: for a landowner it
                    // is the number the record exists for, and the thumbnail beside
                    // it has already said this is a measured boundary.
                    val area = land.areaSqm
                    if (land.isPolygon && area != null && area > 0.0) {
                        Text(
                            stringResource(areaUnit.valueRes, AreaFormat.value(area, areaUnit)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }

                    land.distanceMeters?.let { metres ->
                        MetaItem(
                            icon = Icons.Default.Place,
                            text = GeoUtils.formatDistance(metres, imperial)
                        )
                    }

                    if (land.photos.isNotEmpty()) {
                        MetaItem(
                            icon = Icons.Outlined.PhotoCamera,
                            text = land.photos.size.toString(),
                            // A bare "3" tells a screen reader nothing; the icon
                            // carries the meaning for sighted users only.
                            iconDescription = stringResource(R.string.photos_title)
                        )
                    }

                    land.parcelNumber?.takeIf { it.isNotBlank() }?.let { parcel ->
                        Text(
                            parcel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Shrinks rather than pushing the row off the card when
                            // a parcel number runs long.
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }

                Text(
                    if (dms) GeoUtils.formatDMS(land.latitude, land.longitude)
                    else GeoUtils.formatDecimal(land.latitude, land.longitude),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                land.address?.takeIf { it.isNotBlank() }?.let { address ->
                    Text(
                        address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (selecting) {
                Checkbox(
                    checked = selected,
                    // The whole card is the hit target; a separate one here would
                    // be a 24 dp box next to a 300 dp one doing the same thing.
                    onCheckedChange = null
                )
            } else {
                IconButton(onClick = onNavigate) {
                    Icon(
                        Icons.Outlined.Directions,
                        contentDescription = stringResource(R.string.card_navigate_to, land.name)
                    )
                }
            }
        }
    }
}

/** One icon-and-value pair in the card's metadata row. */
@Composable
private fun MetaItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    iconDescription: String? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            icon,
            contentDescription = iconDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
