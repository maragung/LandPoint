package com.landpoint.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * What to do with the width the window happens to have.
 *
 * The app was drawn for a phone held upright, where there is one column of room
 * and the three destinations belong along the bottom edge. Turn that phone on its
 * side, or open the app on a tablet, and the shape of the problem inverts: width
 * to spare, height suddenly scarce, and a bottom bar spending some of the little
 * height left on something that would sit happily in the margin.
 *
 * Two thresholds, both Material's, both measured on the window rather than the
 * screen so a freeform or split-screen window is judged on the room it actually
 * got:
 *
 *  - from 600dp ("medium") the destinations move to a rail at the side;
 *  - from 840dp ("expanded") the land list keeps the detail beside it instead of
 *    handing the whole screen over to it.
 *
 * Kept as plain arithmetic on a number, apart from the reading of that number, so
 * the decision can be checked in a test rather than only on a device.
 */
object WindowLayout {

    /** Material's medium window: a tablet upright, most phones on their side. */
    const val RAIL_FROM_DP = 600

    /** Material's expanded window: room for two panes without cramping either. */
    const val TWO_PANE_FROM_DP = 840

    /** Destinations at the side rather than along the bottom. */
    fun railInsteadOfBottomBar(widthDp: Int): Boolean = widthDp >= RAIL_FROM_DP

    /**
     * The chosen record shown beside the list rather than over it.
     *
     * Deliberately stricter than [railInsteadOfBottomBar]: a 600dp window split
     * two ways leaves a record in about 360dp, which is a phone's worth of width
     * for a screen carrying a map, an area readout and a list of corners. The rail
     * is worth having long before a second pane is.
     */
    fun listBesideDetail(widthDp: Int): Boolean = widthDp >= TWO_PANE_FROM_DP
}

/**
 * The width of the window, in dp — not of the screen.
 *
 * Reads it from the configuration rather than from a size class, because the size
 * classes live in a library this app does not otherwise need, and one integer is
 * all the two decisions above take.
 */
@Composable
@ReadOnlyComposable
fun windowWidthDp(): Int = LocalConfiguration.current.screenWidthDp
