package com.landpoint.app.ui.compass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.landpoint.app.R
import com.landpoint.app.util.GeoUtils
import kotlin.math.min

/**
 * In-app bearing mode: an arrow that points at the saved land, rotated by the
 * difference between the target bearing and the device heading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompassScreen(
    onBack: () -> Unit,
    viewModel: CompassViewModel = viewModel(factory = CompassViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val heading = rememberDeviceHeading()

    val targetRotation = if (state.bearing != null && heading != null) {
        ((state.bearing!! - heading).toFloat() + 360f) % 360f
    } else {
        state.bearing?.toFloat() ?: 0f
    }

    val rotation by animateFloatAsState(targetValue = targetRotation, label = "needle")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.land?.name ?: stringResource(R.string.compass_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
        ) {
            val primary = MaterialTheme.colorScheme.primary
            val outline = MaterialTheme.colorScheme.outlineVariant

            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(240.dp)) {
                    val radius = min(size.width, size.height) / 2f
                    drawCircle(color = outline, radius = radius, style = Stroke(width = 3f))
                    drawCircle(color = outline, radius = radius * 0.66f, style = Stroke(width = 1.5f))

                    rotate(rotation) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val arrow = Path().apply {
                            moveTo(cx, cy - radius * 0.78f)
                            lineTo(cx - radius * 0.20f, cy + radius * 0.34f)
                            lineTo(cx, cy + radius * 0.14f)
                            lineTo(cx + radius * 0.20f, cy + radius * 0.34f)
                            close()
                        }
                        drawPath(path = arrow, color = primary)
                    }

                    drawCircle(color = primary, radius = 7f, center = Offset(size.width / 2f, size.height / 2f))
                }
            }

            when {
                state.isWaitingForFix -> Text(
                    stringResource(R.string.compass_waiting),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                state.distanceMeters != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        GeoUtils.formatDistance(state.distanceMeters!!, state.imperial),
                        style = MaterialTheme.typography.displaySmall
                    )
                    state.bearing?.let { bearing ->
                        val cardinals = stringArrayResource(R.array.cardinal_directions)
                        Text(
                            "${cardinals[GeoUtils.cardinalIndex(bearing)]} · ${bearing.toInt()}°",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.accuracy?.let {
                        Text(
                            stringResource(R.string.label_gps_accuracy) + " " +
                                    stringResource(R.string.value_accuracy, it.toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (heading == null) {
                Text(
                    stringResource(R.string.compass_no_sensor),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
