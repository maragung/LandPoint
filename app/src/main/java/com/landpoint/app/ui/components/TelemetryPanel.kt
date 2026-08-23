package com.landpoint.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.landpoint.app.R
import com.landpoint.app.location.FixQuality
import com.landpoint.app.location.FixSource
import com.landpoint.app.location.LocationTelemetry
import com.landpoint.app.location.SignalStrength
import com.landpoint.app.location.TrackingState
import com.landpoint.app.util.GeoUtils
import com.landpoint.app.util.Localization
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Same as the round map controls, so the two read as one set of chrome. */
private const val CHROME_ALPHA = 0.92f

/** Wide enough for "Mobile network or Wi-Fi" on a narrow phone, no wider. */
private val PANEL_MAX_WIDTH = 280.dp

/** Metres per second to kilometres per hour. */
private const val KMH_PER_MPS = 3.6

/** Big enough to find at a glance, small enough not to read as a button. */
private val DOT_SIZE = 10.dp

/** An icon set beside one line of label text, so it matches the line rather than leads it. */
private val ICON_SIZE = 18.dp

/**
 * What the receiver is reporting, right now, in the user's own units.
 *
 * On the map rather than on a screen of its own because the question it answers is
 * asked while looking at the map: *should I trust this dot?* A marker with a circle
 * around it says how uncertain the position is, and no more — this says why. Four
 * satellites heard faintly explains a wandering dot; nine at 40 dB-Hz says the dot
 * is as good as this phone gets and the corner can be marked.
 *
 * Collapsed to a single chip by default. Expanded it covers a third of a phone
 * screen, which is a third of the ground the user came here to look at, so it opens
 * on a tap and stays open only as long as it is wanted.
 *
 * Nothing here is computed from anything else: every row is a figure Android
 * reported, and a row the fix did not carry says so instead of showing a nought.
 * The two exceptions are labelled as what they are — the heading can come from the
 * compass, and the movement row is the app's own reading of the accelerometer.
 */
@Composable
fun TelemetryPanel(
    state: TrackingState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = { onExpandedChange(!expanded) },
        modifier = modifier
            .widthIn(max = PANEL_MAX_WIDTH)
            .animateContentSize(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = CHROME_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp
    ) {
        if (expanded) ExpandedReadout(state) else CollapsedReadout(state)
    }
}

/**
 * Stands in for the readout when the app is not allowed to know where it is.
 *
 * Shown rather than an empty corner because the map itself gives no hint: no
 * marker, no accuracy circle, and no reason offered. One tap goes wherever the
 * permission can still be granted — the system dialog, or its settings page once
 * Android has stopped asking on our behalf.
 *
 * @param blocked the system will no longer show the dialog, so the tap has to open
 *   settings instead of an ask that would be swallowed.
 */
@Composable
fun LocationAccessChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    blocked: Boolean = false
) {
    val action = stringResource(
        if (blocked) R.string.permission_open_settings else R.string.permission_grant
    )
    val reason = stringResource(R.string.permission_title)
    Surface(
        onClick = onClick,
        modifier = modifier.widthIn(max = PANEL_MAX_WIDTH),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = CHROME_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 8.dp)
                // The reason as well as the action: "Grant permission" on its own
                // does not say what for, and the chip's icon is the only other clue.
                .clearAndSetSemantics { contentDescription = "$reason. $action" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.LocationOff,
                contentDescription = null,
                modifier = Modifier.size(ICON_SIZE)
            )
            Text(text = action, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * The one-line version: how good the position is, and how the sky looks.
 *
 * Those two because they are the pair that decides what to do next. Everything else
 * in the panel is detail for someone who has already decided something is wrong.
 */
@Composable
private fun CollapsedReadout(state: TrackingState) {
    val accuracy = state.fix?.accuracyM
    val position = if (accuracy != null) {
        stringResource(R.string.boundary_corner_accuracy, GeoUtils.formatAccuracy(accuracy))
    } else if (state.hasFix) {
        stringResource(R.string.telemetry_unknown)
    } else {
        stringResource(R.string.telemetry_waiting_short)
    }
    val sky = if (state.satellites.hasReport) {
        stringResource(
            R.string.telemetry_value_satellites_short,
            state.satellites.used,
            state.satellites.visible
        )
    } else {
        stringResource(state.satellites.strength.labelRes())
    }
    val summary = stringResource(R.string.telemetry_summary, position, sky)

    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SignalDot(state.satellites.strength)
        Text(
            // One description for the row: read as three fragments, a screen reader
            // would say "plus or minus 4 m" and "7 slash 12 sat" as separate items
            // with no hint that they belong to the same reading.
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = summary
            },
            text = summary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
        Icon(
            imageVector = Icons.Default.MyLocation,
            contentDescription = stringResource(R.string.telemetry_show),
            modifier = Modifier.size(ICON_SIZE),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExpandedReadout(state: TrackingState) {
    val fix = state.fix
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SignalDot(state.satellites.strength)
            Text(
                text = stringResource(R.string.telemetry_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.ExpandLess,
                contentDescription = stringResource(R.string.telemetry_hide),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (fix == null) {
            Text(
                text = stringResource(R.string.telemetry_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            Readout(R.string.telemetry_latitude, coordinate(fix.latitude))
            Readout(R.string.telemetry_longitude, coordinate(fix.longitude))
            Readout(R.string.telemetry_accuracy, accuracyText(fix))
            Readout(R.string.telemetry_altitude, altitudeText(fix))
            Readout(R.string.telemetry_speed, speedText(fix))
        }

        Readout(R.string.telemetry_heading, headingText(state))

        if (fix != null) {
            Readout(R.string.telemetry_source, stringResource(fix.source.labelRes()))
        }

        Readout(R.string.telemetry_satellites, satellitesText(state))
        Readout(R.string.telemetry_signal, signalText(state))
        Readout(
            R.string.telemetry_motion,
            stringResource(
                if (state.moving) R.string.telemetry_value_moving else R.string.telemetry_value_still
            )
        )

        if (fix != null) Readout(R.string.telemetry_updated, timeText(fix.timestamp))
    }
}

/** One label and its figure, on a line. */
@Composable
private fun Readout(labelRes: Int, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End
        )
    }
}

/**
 * The signal, as a colour.
 *
 * Beside the words rather than instead of them: colour alone would leave the state
 * unreadable to a user who cannot tell green from amber, and the words are already
 * there. It earns its place by being findable without reading — the one thing on the
 * panel a glance can take in.
 */
@Composable
private fun SignalDot(strength: SignalStrength) {
    val colour = when (strength) {
        SignalStrength.NONE -> MaterialTheme.colorScheme.error
        SignalStrength.WEAK -> Color(0xFFEF6C00)
        SignalStrength.FAIR -> Color(0xFF558B2F)
        SignalStrength.STRONG -> Color(0xFF2E7D32)
    }
    // A drawn shape rather than a Surface or an Icon: it carries no text and needs
    // no elevation, and either of those would put a focusable, unlabelled node in
    // front of a screen reader.
    Box(modifier = Modifier.size(DOT_SIZE).background(colour, CircleShape))
}

/**
 * Coordinates, at six decimals and always with a dot.
 *
 * Six decimals is about a tenth of a metre — past what any phone can claim, and the
 * point at which a reader can copy the number into another tool and land in the same
 * place. The separator stays a dot even where the user's own numbers use a comma,
 * because this is the one figure on the panel meant to be copied rather than read.
 */
private fun coordinate(value: Double): String = "%.6f".format(Locale.US, value)

@Composable
private fun accuracyText(fix: LocationTelemetry): String {
    val accuracy = fix.accuracyM ?: return stringResource(R.string.telemetry_unknown)
    val radius = stringResource(R.string.boundary_corner_accuracy, GeoUtils.formatAccuracy(accuracy))
    val quality = fix.quality ?: return radius
    return stringResource(R.string.telemetry_summary, radius, stringResource(quality.labelRes()))
}

@Composable
private fun altitudeText(fix: LocationTelemetry): String {
    val altitude = fix.altitudeM ?: return stringResource(R.string.telemetry_unknown)
    return stringResource(R.string.value_metres, altitude.roundToInt())
}

@Composable
private fun speedText(fix: LocationTelemetry): String {
    val speed = fix.speedMps ?: return stringResource(R.string.telemetry_unknown)
    val locale = Localization.numberLocale()
    return stringResource(
        R.string.telemetry_value_speed,
        "%.1f".format(locale, speed),
        "%.1f".format(locale, speed * KMH_PER_MPS)
    )
}

@Composable
private fun headingText(state: TrackingState): String {
    val heading = state.heading ?: return stringResource(R.string.telemetry_unknown)
    val cardinal = stringArrayResource(R.array.cardinal_directions)[GeoUtils.cardinalIndex(heading)]
    return stringResource(
        if (state.headingIsCompass) {
            R.string.telemetry_value_heading_compass
        } else {
            R.string.telemetry_value_heading
        },
        heading.roundToInt() % 360,
        cardinal
    )
}

@Composable
private fun satellitesText(state: TrackingState): String {
    val sky = state.satellites
    if (!sky.hasReport) return stringResource(R.string.telemetry_unknown)
    return stringResource(R.string.telemetry_value_satellites, sky.used, sky.visible)
}

@Composable
private fun signalText(state: TrackingState): String {
    val strength = stringResource(state.satellites.strength.labelRes())
    val cn0 = state.satellites.meanCn0DbHz ?: return strength
    return stringResource(
        R.string.telemetry_value_signal,
        strength,
        "%.0f".format(Localization.numberLocale(), cn0)
    )
}

/**
 * When the receiver said this, on the clock.
 *
 * A wall-clock time rather than "3 seconds ago": an age has to be re-rendered every
 * second to stay true, and a clock time that has stopped moving says the same thing
 * — the fixes have stopped — without a ticker behind it.
 */
private fun timeText(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Localization.numberLocale()).format(Date(timestamp))

private fun SignalStrength.labelRes(): Int = when (this) {
    SignalStrength.NONE -> R.string.telemetry_signal_none
    SignalStrength.WEAK -> R.string.telemetry_signal_weak
    SignalStrength.FAIR -> R.string.telemetry_signal_fair
    SignalStrength.STRONG -> R.string.telemetry_signal_strong
}

private fun FixSource.labelRes(): Int = when (this) {
    FixSource.GPS -> R.string.telemetry_source_gps
    FixSource.FUSED -> R.string.telemetry_source_fused
    FixSource.NETWORK -> R.string.telemetry_source_network
    FixSource.OTHER -> R.string.telemetry_source_other
}

private fun FixQuality.labelRes(): Int = when (this) {
    FixQuality.EXCELLENT -> R.string.edit_quality_excellent
    FixQuality.GOOD -> R.string.edit_quality_good
    FixQuality.FAIR -> R.string.edit_quality_fair
    FixQuality.POOR -> R.string.edit_quality_poor
}
