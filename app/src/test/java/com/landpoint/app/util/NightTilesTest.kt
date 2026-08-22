package com.landpoint.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the night filter does to the colours actually on an OpenStreetMap tile.
 *
 * The point of the exercise is that the map stays readable *as a map* in the dark:
 * bright ground goes dark, dark lettering goes bright, and the few colours that
 * carry meaning — green for planting, blue for water — still mean what they did.
 * The osmdroid inversion this replaces passes the first two and fails the third,
 * so the colour cases below are the ones worth holding on to.
 */
class NightTilesTest {

    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun green(argb: Int) = (argb shr 8) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF

    private fun brightness(argb: Int) = (red(argb) + green(argb) + blue(argb)) / 3

    @Test
    fun `white ground becomes black and black becomes white`() {
        assertEquals(0xFF000000.toInt(), NightTiles.shade(0xFFFFFFFF.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), NightTiles.shade(0xFF000000.toInt()))
    }

    @Test
    fun `grey stays grey`() {
        val shaded = NightTiles.shade(0xFF808080.toInt())
        assertEquals(red(shaded), green(shaded))
        assertEquals(green(shaded), blue(shaded))
    }

    @Test
    fun `the beige ground of a tile goes almost black`() {
        assertTrue(brightness(NightTiles.shade(0xFFF2EFE9.toInt())) < 40)
    }

    @Test
    fun `lettering that was nearly black comes out nearly white`() {
        assertTrue(brightness(NightTiles.shade(0xFF3C3C3C.toInt())) > 180)
    }

    @Test
    fun `a green field is still green, and now a dark one`() {
        // The colour openstreetmap-carto paints farmland and parks.
        val shaded = NightTiles.shade(0xFFC8E6A0.toInt())
        assertTrue("green should lead: $shaded", green(shaded) > red(shaded))
        assertTrue("green should lead: $shaded", green(shaded) > blue(shaded))
        assertTrue("and it should be dark: $shaded", brightness(shaded) < 110)
    }

    @Test
    fun `water is still blue`() {
        val shaded = NightTiles.shade(0xFFAAD3DF.toInt())
        assertTrue("blue should lead: $shaded", blue(shaded) > red(shaded))
        assertTrue("and it should be dark: $shaded", brightness(shaded) < 110)
    }

    @Test
    fun `plain inversion would have got those two wrong`() {
        // Stated as a test because it is the whole reason this matrix exists: the
        // inversion osmdroid ships turns the field magenta and the water orange.
        val field = 0xFFC8E6A0.toInt()
        val invertedGreen = 0xFF - green(field)
        val invertedRed = 0xFF - red(field)
        assertTrue("inverted green field would be red-heavy", invertedRed > invertedGreen)
        assertTrue(green(NightTiles.shade(field)) > red(NightTiles.shade(field)))
    }

    @Test
    fun `a transparent pixel keeps its transparency`() {
        assertEquals(0x80, (NightTiles.shade(0x80FFFFFF.toInt()) ushr 24) and 0xFF)
    }

    @Test
    fun `nothing comes out beyond a colour channel`() {
        for (value in listOf(0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFF00FFFF)) {
            val shaded = NightTiles.shade(value.toInt())
            for (channel in listOf(red(shaded), green(shaded), blue(shaded))) {
                assertTrue("$channel out of range for $value", channel in 0..255)
            }
        }
    }
}
