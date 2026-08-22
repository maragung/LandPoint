package com.landpoint.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two width decisions, on the window sizes real devices hand the app.
 *
 * Worth a test because neither is visible in a unit test otherwise — a layout is
 * checked by looking at it, and there is no tablet here to look at. What can be
 * pinned down is the arithmetic: that a phone held upright keeps the layout it was
 * drawn for, that the same phone on its side gets both changes, and that the
 * thresholds are the exact dp they claim to be rather than one out.
 */
class WindowLayoutTest {

    /** dp widths, taken from the window sizes these devices actually report. */
    private val phoneUpright = 411
    private val phoneOnItsSide = 891
    private val smallPhoneOnItsSide = 731
    private val tabletUpright = 800
    private val tabletOnItsSide = 1280
    private val splitScreenSliver = 320

    @Test
    fun `a phone held upright keeps the bottom bar and one pane`() {
        assertFalse(WindowLayout.railInsteadOfBottomBar(phoneUpright))
        assertFalse(WindowLayout.listBesideDetail(phoneUpright))
    }

    @Test
    fun `a phone on its side moves the destinations aside and shows both panes`() {
        assertTrue(WindowLayout.railInsteadOfBottomBar(phoneOnItsSide))
        assertTrue(WindowLayout.listBesideDetail(phoneOnItsSide))
    }

    @Test
    fun `a small phone on its side gets the rail but not a second pane`() {
        // 731dp split two ways is a 290dp record: a rail is worth having long
        // before a second pane is, which is the whole reason for two thresholds.
        assertTrue(WindowLayout.railInsteadOfBottomBar(smallPhoneOnItsSide))
        assertFalse(WindowLayout.listBesideDetail(smallPhoneOnItsSide))
    }

    @Test
    fun `a tablet upright gets the rail and keeps one pane`() {
        assertTrue(WindowLayout.railInsteadOfBottomBar(tabletUpright))
        assertFalse(WindowLayout.listBesideDetail(tabletUpright))
    }

    @Test
    fun `a tablet on its side gets both`() {
        assertTrue(WindowLayout.railInsteadOfBottomBar(tabletOnItsSide))
        assertTrue(WindowLayout.listBesideDetail(tabletOnItsSide))
    }

    @Test
    fun `a window squeezed into a corner of a split screen is treated as a phone`() {
        // The decisions read the window, not the screen, so a tablet running the
        // app in a third of its width must still get the phone layout.
        assertFalse(WindowLayout.railInsteadOfBottomBar(splitScreenSliver))
        assertFalse(WindowLayout.listBesideDetail(splitScreenSliver))
    }

    @Test
    fun `both thresholds include the width they name and exclude the one below`() {
        assertFalse(WindowLayout.railInsteadOfBottomBar(WindowLayout.RAIL_FROM_DP - 1))
        assertTrue(WindowLayout.railInsteadOfBottomBar(WindowLayout.RAIL_FROM_DP))
        assertFalse(WindowLayout.listBesideDetail(WindowLayout.TWO_PANE_FROM_DP - 1))
        assertTrue(WindowLayout.listBesideDetail(WindowLayout.TWO_PANE_FROM_DP))
    }

    @Test
    fun `a second pane never appears without the rail`() {
        // A window wide enough for two panes but still carrying a bottom bar would
        // be the worst of both: the shorter half of the screen spent on the bar,
        // and the record squeezed for it.
        assertTrue(WindowLayout.TWO_PANE_FROM_DP >= WindowLayout.RAIL_FROM_DP)
        for (width in 0..1600 step 7) {
            if (WindowLayout.listBesideDetail(width)) {
                assertTrue(
                    "two panes at ${width}dp but no rail",
                    WindowLayout.railInsteadOfBottomBar(width)
                )
            }
        }
    }
}
