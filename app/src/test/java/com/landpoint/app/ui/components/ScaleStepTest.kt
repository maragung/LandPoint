package com.landpoint.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The number on the scale bar.
 *
 * A scale bar is read to estimate a distance that is *not* the bar's own length, so
 * the label has to be something a person can halve and quarter in their head. That
 * is the whole rule, and it is arithmetic — testable without a map, which is the
 * only way it gets tested at all, since the alternative is squinting at a bar on a
 * phone and trusting it.
 */
class ScaleStepTest {

    private fun metric(maxMetres: Double) = scaleStep(maxMetres, imperial = false)

    private fun imperial(maxMetres: Double) = scaleStep(maxMetres, imperial = true)

    @Test
    fun `the step is the largest round distance that still fits`() {
        // Round means 1, 2 or 5 times a power of ten. Nothing else is on the ladder.
        assertEquals(100.0, metric(137.0)!!.metres, 0.0)
        assertEquals(200.0, metric(499.0)!!.metres, 0.0)
        assertEquals(500.0, metric(999.0)!!.metres, 0.0)
        assertEquals(1.0, metric(1.9)!!.metres, 0.0)
    }

    @Test
    fun `a step that fits exactly is taken, not rounded down again`() {
        assertEquals(500.0, metric(500.0)!!.metres, 0.0)
        assertEquals(2000.0, metric(2000.0)!!.metres, 0.0)
    }

    @Test
    fun `metres become kilometres at a thousand`() {
        assertEquals("500 m", metric(999.0)!!.label)
        assertEquals("1 km", metric(1_500.0)!!.label)
        assertEquals("2 km", metric(4_000.0)!!.label)
        assertEquals("500 km", metric(900_000.0)!!.label)
    }

    @Test
    fun `a label never carries a decimal point`() {
        // The bar is glanced at, and "1.5 km" on a bar that is not 1.5 km long is
        // worse than a shorter bar with a whole number on it.
        val labels = listOf(1.0, 12.0, 137.0, 1_500.0, 9_000.0, 250_000.0)
            .mapNotNull { metric(it)?.label }

        assertEquals(6, labels.size)
        assertTrue(labels.none { it.contains('.') })
    }

    @Test
    fun `zoomed in past the smallest step, nothing is drawn`() {
        // Under a metre there is no round number left, and a bar labelled "0 m" is
        // worse than no bar.
        assertNull(metric(0.9))
        assertNull(metric(0.0))
        assertNull(metric(-5.0))
        assertNull(metric(Double.NaN))
    }

    @Test
    fun `imperial steps are round feet below a mile`() {
        val step = imperial(200.0)!!

        assertEquals("500 ft", step.label)
        // The stored length is in metres, because that is what the bar is drawn from.
        assertEquals(500.0 / 3.28084, step.metres, 1e-6)
        assertTrue(step.metres <= 200.0)
    }

    @Test
    fun `imperial switches to whole miles above one`() {
        // Feet do not become miles at a power of ten, so the ladder changes rather
        // than continuing: 5280 ft would be "5000 ft" on the metric rule.
        assertEquals("1 mi", imperial(1_700.0)!!.label)
        assertEquals("2 mi", imperial(4_000.0)!!.label)
        assertEquals("5 mi", imperial(9_000.0)!!.label)
    }

    @Test
    fun `no imperial step is ever longer than the space it has`() {
        var metres = 0.5
        while (metres < 500_000.0) {
            val step = imperial(metres)
            if (step != null) {
                assertTrue("${step.label} overflows $metres m", step.metres <= metres + 1e-9)
            }
            metres *= 1.37
        }
    }

    @Test
    fun `no metric step is ever longer than the space it has`() {
        var metres = 0.5
        while (metres < 500_000.0) {
            val step = metric(metres)
            if (step != null) {
                assertTrue("${step.label} overflows $metres m", step.metres <= metres + 1e-9)
            }
            metres *= 1.37
        }
    }

    @Test
    fun `just under a mile is still labelled in feet`() {
        // 5279 ft, not "1 mi" on a bar that is short of one.
        val step = imperial(5_279.0 / 3.28084)!!

        assertTrue(step.label.endsWith(" ft"))
        assertEquals(5_000.0 / 3.28084, step.metres, 1e-6)
    }

    @Test
    fun `the fine rungs the deepened zoom caps reach are on the metric ladder`() {
        // 1.2.0 capped the camera before these spans were reachable, so "no 10 m scale"
        // was really "no bar this short". Raising the cap is only worth anything if the
        // ladder has these rungs: 7 m of room is a 5 m bar (the z20 rung) and 3 m is a
        // 2 m bar (z21), each rounded *down* to a number a person can halve by eye rather
        // than labelling the ragged width itself.
        assertEquals(5.0, metric(7.0)!!.metres, 0.0)
        assertEquals(2.0, metric(3.0)!!.metres, 0.0)
        assertEquals(1.0, metric(1.4)!!.metres, 0.0)
        // Under a metre there is still no whole rung left, so still nothing is drawn.
        assertNull(metric(0.99))
    }

    @Test
    fun `the fine rung labels stay whole metres with no decimal point`() {
        // "1.4 m" on a 1 m bar is exactly the mislabelling the whole-number rule exists
        // to stop, and it bites hardest at the close-in zooms where the bar is shortest.
        assertEquals("5 m", metric(7.0)!!.label)
        assertEquals("2 m", metric(3.0)!!.label)
        assertEquals("1 m", metric(1.4)!!.label)
        assertTrue(listOf(7.0, 3.0, 1.4).mapNotNull { metric(it)?.label }.none { it.contains('.') })
    }
}
