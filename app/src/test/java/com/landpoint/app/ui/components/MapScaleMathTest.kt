package com.landpoint.app.ui.components

import com.landpoint.app.map.MapProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which distances the scale bar can label, worked out from the camera's zoom cap
 * rather than off a phone.
 *
 * A user reported that the map "does not support a 10 m scale". The bar's arithmetic
 * was never wrong; what fixed the reachable rungs was the camera's zoom cap. The bar
 * caps its own width at 96.dp, so the widest span it can label at a zoom is
 * `metresPerDp(zoom, lat) * 96` — the density factor cancels out of it — and the round
 * ladder that span lands on is what these tests pin down. They are the proof that
 * raising the caps is what answers the report, and like [ScaleStepTest] they run
 * without a map because the alternative is squinting at a bar on a phone and trusting
 * it.
 */
class MapScaleMathTest {

    // Mirrors SCALE_BAR_MAX_WIDTH (96.dp) in MapScaleBar: the bar never grows past it,
    // so this is the multiplier that turns "metres per dp" into the widest labellable
    // span. Kept as a plain number because a dp is density-independent here.
    private val barMaxDp = 96.0
    private val equator = 0.0

    // Bandung. This app is used in Indonesia, so the ladder has to hold here, not only
    // at the equator where cos(lat) is a tidy 1.
    private val indonesiaLat = -6.9

    @Test
    fun `each zoom level lands the bar on the round rung the release promises`() {
        // The whole ladder the "no 10 m scale" report turns on: z19 must reach 10 m, and
        // the caps above it 5 m and 2 m. Read as a table so the rungs are legible here.
        // Checked at the equator and at Bandung, because Mercator makes the span depend
        // on latitude and both have to fall on the same rung.
        val ladder = listOf(
            17 to 50.0,
            18 to 20.0,
            19 to 10.0,
            20 to 5.0,
            21 to 2.0
        )
        for (latitude in listOf(equator, indonesiaLat)) {
            for ((zoom, metres) in ladder) {
                val step = scaleStep(metresPerDp(zoom.toDouble(), latitude) * barMaxDp, imperial = false)
                assertEquals(
                    "zoom $zoom at latitude $latitude should label ${metres.toInt()} m",
                    metres,
                    step!!.metres,
                    0.0
                )
                // Whole metres and the unit, which is what a surveyor reads off the bar.
                assertEquals("${metres.toInt()} m", step.label)
            }
        }
    }

    @Test
    fun `every built-in basemap can reach a 10 m or finer bar in Indonesia`() {
        // The literal proof the user's complaint is fixed. Every shipped basemap —
        // Terrain included — must have a camera maxZoom whose widest bar is 10 m or finer
        // at Indonesian latitudes. Terrain is the one that regressed: at the old cap of
        // 17 its finest bar is 50 m, so this assertion MUST fail if Terrain's maxZoom is
        // ever put back to 17.
        for (provider in MapProviders.builtIn) {
            val step = scaleStep(metresPerDp(provider.maxZoom, indonesiaLat) * barMaxDp, imperial = false)
            assertNotNull("${provider.mode} draws no bar at all at its own zoom cap", step)
            assertTrue(
                "${provider.mode} caps at zoom ${provider.maxZoom}; its finest bar is " +
                    "${step!!.metres} m, coarser than the 10 m the report asked for",
                step.metres <= 10.0
            )
        }
    }

    @Test
    fun `moving off the equator only ever shortens the span`() {
        // cos(latitude): away from the equator Web Mercator stretches the ground, so a
        // pixel covers less of it. The same zoom at Bandung must span slightly *less*
        // than at the equator — and only slightly. The lower bound is the real catch: a
        // degrees-for-radians slip in the cos() call would shrink -6.9 degrees to about
        // 0.81 of the equator's span instead of 0.99, and the rungs would start sliding.
        for (zoom in 17..21) {
            val atEquator = metresPerDp(zoom.toDouble(), equator)
            val atBandung = metresPerDp(zoom.toDouble(), indonesiaLat)
            assertTrue("latitude lengthened the span at zoom $zoom", atBandung < atEquator)
            assertTrue("latitude shortened the span far too much at zoom $zoom", atBandung > atEquator * 0.99)
        }
    }

    @Test
    fun `a non-finite zoom or latitude comes back as zero, never a NaN`() {
        // metresPerDp feeds the bar's width arithmetic, and a NaN width crashes the
        // Compose layout pass rather than merely drawing the wrong number. Every
        // non-finite input has to return a plain 0.0, which the bar already reads as
        // "no scale yet" and draws nothing for.
        assertEquals(0.0, metresPerDp(Double.NaN, indonesiaLat), 0.0)
        assertEquals(0.0, metresPerDp(Double.POSITIVE_INFINITY, indonesiaLat), 0.0)
        assertEquals(0.0, metresPerDp(Double.NEGATIVE_INFINITY, indonesiaLat), 0.0)
        assertEquals(0.0, metresPerDp(19.0, Double.NaN), 0.0)
    }

    @Test
    fun `one more zoom level halves the span exactly`() {
        // The property the whole ladder rests on: each zoom step doubles MapLibre's tile
        // pyramid, so the ground under a pixel halves. If this ever drifts, the
        // 50/20/10/5/2 rungs stop lining up with the zoom caps that are meant to reach
        // them. A tight delta because it is exact bar the last bit of floating point.
        for (zoom in 17..21) {
            for (latitude in listOf(equator, indonesiaLat)) {
                assertEquals(
                    metresPerDp(zoom.toDouble(), latitude) / 2.0,
                    metresPerDp(zoom + 1.0, latitude),
                    1e-12
                )
            }
        }
    }
}
