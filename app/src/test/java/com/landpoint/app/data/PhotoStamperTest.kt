package com.landpoint.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.landpoint.app.util.AppStrings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Native graphics is required, not a preference: under Robolectric's legacy
 * graphics Canvas draws nothing and BitmapFactory returns a stub bitmap even for
 * a text file, so the stamp would appear to work no matter what this class did.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoStamperTest {

    private lateinit var context: Context
    private lateinit var stamper: PhotoStamper

    private val data = StampData(
        latitude = -6.914744,
        longitude = 107.609810,
        accuracyM = 4.2f,
        altitudeM = 768.0,
        takenAt = 1_754_700_000_000L,
        address = "Bandung, Jawa Barat",
        dms = false
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stamper = PhotoStamper(context, AppStrings(context))
    }

    /** A plain white JPEG, so any darkening is the stamp and nothing else. */
    private fun whiteJpeg(width: Int = 400, height: Int = 300): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val file = File.createTempFile("stamp", ".jpg", context.cacheDir)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        return file
    }

    private fun brightness(color: Int): Int =
        (Color.red(color) + Color.green(color) + Color.blue(color)) / 3

    @Test
    fun `stamp darkens a bar along the bottom and leaves the top alone`() = runTest {
        val file = whiteJpeg()

        assertTrue(stamper.stamp(file, data))

        val stamped = BitmapFactory.decodeFile(file.absolutePath)
        assertNotNull(stamped)
        // x = 2 sits left of the text inset, so this samples the backing bar only.
        assertTrue(brightness(stamped.getPixel(2, stamped.height - 4)) < 200)
        assertTrue(brightness(stamped.getPixel(2, 4)) > 240)
    }

    @Test
    fun `stamp writes the position back to EXIF`() = runTest {
        val file = whiteJpeg()

        assertTrue(stamper.stamp(file, data))

        val exif = ExifInterface(file.absolutePath)
        val latLong = FloatArray(2)
        assertTrue(exif.getLatLong(latLong))
        // EXIF stores rationals, so the round trip is close rather than exact.
        assertEquals(data.latitude, latLong[0].toDouble(), 1e-4)
        assertEquals(data.longitude, latLong[1].toDouble(), 1e-4)
        assertEquals(768.0, exif.getAltitude(0.0), 0.5)
        assertNotNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("LandPoint", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
    }

    @Test
    fun `stamp reports failure instead of throwing on a file that is not an image`() = runTest {
        val notAnImage = File.createTempFile("broken", ".jpg", context.cacheDir)
        notAnImage.writeText("this is not a JPEG")

        assertEquals(false, stamper.stamp(notAnImage, data))
    }
}
