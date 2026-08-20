package com.landpoint.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Slightly rounder than the Material 3 defaults.
 *
 * The app is a stack of cards read at arm's length in daylight, and softer
 * corners separate one card from the next without needing a border. Anything
 * already asking for `MaterialTheme.shapes` — the corner picker's hint banner,
 * every Card, every dialog — picks these up without a change.
 */
val LandPointShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
