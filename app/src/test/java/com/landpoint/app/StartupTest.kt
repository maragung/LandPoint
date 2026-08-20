package com.landpoint.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Launches the real Application and Activity.
 *
 * Every other test here exercises a class in isolation, which is exactly why the
 * app could ship with 88 green tests and still die on the launcher icon: nothing
 * had ever run `LandPointApp.onCreate` followed by `setContent`. This closes
 * that gap for the two API levels that matter — the floor the app claims to
 * support, and the one it targets.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupTest {

    @Test
    @Config(sdk = [28])
    fun `the app starts on the minimum supported android version`() {
        launchAndAssert()
    }

    @Test
    @Config(sdk = [35])
    fun `the app starts on the version it targets`() {
        launchAndAssert()
    }

    private fun launchAndAssert() {
        val app = ApplicationProvider.getApplicationContext<LandPointApp>()
        assertNotNull("Application.onCreate must have built the container", app.container)

        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            assertNotNull("MainActivity must reach a resumed state", controller.get())
        }
    }
}
