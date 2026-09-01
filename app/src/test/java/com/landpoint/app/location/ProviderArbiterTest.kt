package com.landpoint.app.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The referee between the providers the live tracker subscribes at once.
 *
 * Pure JVM, no Robolectric: the rule is three booleans of logic, and what it
 * guards against — the marker that jumped between a cell tower and a satellite
 * — is exactly the kind of behaviour a stream test is too slow to pin down.
 */
class ProviderArbiterTest {

    @Test
    fun `a tower fix never displaces a satellite fix`() {
        for (shown in listOf(FixSource.GPS, FixSource.FUSED)) {
            assertFalse(ProviderArbiter.accepts(FixSource.NETWORK, shown))
            assertFalse(ProviderArbiter.accepts(FixSource.OTHER, shown))
        }
    }

    @Test
    fun `a satellite fix displaces anything`() {
        for (incoming in listOf(FixSource.GPS, FixSource.FUSED)) {
            for (shown in FixSource.entries) {
                assertTrue("$incoming over $shown", ProviderArbiter.accepts(incoming, shown))
            }
        }
    }

    @Test
    fun `the first fix through is always accepted`() {
        for (incoming in FixSource.entries) {
            assertTrue(ProviderArbiter.accepts(incoming, null))
        }
    }

    @Test
    fun `tower fixes compete only among themselves`() {
        assertTrue(ProviderArbiter.accepts(FixSource.NETWORK, FixSource.NETWORK))
        assertTrue(ProviderArbiter.accepts(FixSource.NETWORK, FixSource.OTHER))
        assertTrue(ProviderArbiter.accepts(FixSource.OTHER, FixSource.NETWORK))
    }
}
