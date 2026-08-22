package com.landpoint.app

import android.content.ComponentName
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The launch hand-over, checked in the resources rather than on a screen.
 *
 * Three separate edits can each break the splash silently, and none of them
 * shows up as a failure anywhere else: the manifest pointing the launcher at the
 * plain app theme (no splash at all on Android 9 to 11), the starting theme
 * losing `postSplashScreenTheme` (the app left wearing the splash theme, green
 * window and all), or the icon reference going stale after an icon redraw.
 *
 * Checked on both the floor and the target, because the library forwards these
 * attributes to framework ones from Android 12 on and the answers have to agree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class SplashThemeTest {

    private val context = ApplicationProvider.getApplicationContext<LandPointApp>()

    /** The starting theme is what the launcher starts, not the finished app theme. */
    @Test
    fun `the launcher opens the activity in the splash theme`() {
        val activity = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0
        )
        assertEquals(R.style.Theme_LandPoint_Starting, activity.themeResource)
    }

    @Test
    fun `the splash theme names the app theme to hand over to`() {
        assertEquals(
            R.style.Theme_LandPoint,
            resourceId(androidx.core.splashscreen.R.attr.postSplashScreenTheme)
        )
    }

    @Test
    fun `the splash shows the launcher icon on the brand green`() {
        assertEquals(
            R.drawable.ic_launcher_foreground,
            resourceId(androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon)
        )
        assertEquals(
            context.getColor(R.color.ic_launcher_background),
            colour(androidx.core.splashscreen.R.attr.windowSplashScreenBackground)
        )
    }

    /** One attribute per call, so a lookup cannot drift onto a neighbour's index. */
    private fun themed() = ContextThemeWrapper(context, R.style.Theme_LandPoint_Starting)

    private fun resourceId(attr: Int): Int {
        val values = themed().obtainStyledAttributes(intArrayOf(attr))
        try {
            return values.getResourceId(0, 0)
        } finally {
            values.recycle()
        }
    }

    private fun colour(attr: Int): Int {
        val values = themed().obtainStyledAttributes(intArrayOf(attr))
        try {
            return values.getColor(0, 0)
        } finally {
            values.recycle()
        }
    }
}
