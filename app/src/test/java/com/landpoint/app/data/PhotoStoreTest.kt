package com.landpoint.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A photo that cannot be written must cost the user the photo, never the land
 * record it was attached to — so every failure path here returns null instead
 * of throwing out of the coroutine that called it.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoStoreTest {

    private lateinit var context: Context
    private lateinit var store: PhotoStore

    private val imagesDir: File get() = File(context.filesDir, "images")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = PhotoStore(context)
    }

    @After
    fun tearDown() {
        // A test may leave the directory unwritable; restore it or later tests
        // inherit the damage.
        imagesDir.setWritable(true)
        imagesDir.deleteRecursively()
    }

    @Test
    fun `a capture is moved out of cache into permanent storage`() = runTest {
        val source = File(context.cacheDir, "capture.jpg").apply { writeText("jpeg bytes") }

        val path = store.persistCapture(source)

        assertNotNull("A readable capture must be stored", path)
        val stored = File(path!!)
        assertTrue(stored.exists())
        assertEquals("jpeg bytes", stored.readText())
        assertTrue("Permanent storage, not cache", stored.absolutePath.startsWith(context.filesDir.absolutePath))
        assertFalse("The cache copy must not be left behind", source.exists())
    }

    @Test
    fun `an empty capture is rejected`() = runTest {
        val source = File(context.cacheDir, "empty.jpg").apply { createNewFile() }

        assertNull(store.persistCapture(source))
    }

    @Test
    fun `a capture that never arrived is rejected`() = runTest {
        assertNull(store.persistCapture(File(context.cacheDir, "missing.jpg")))
    }

    /**
     * Stands in for a full disk: the copy throws part-way. Before the fix this
     * escaped `onPhotoCaptured`'s bare launch and killed the process.
     */
    @Test
    fun `a capture that cannot be written returns null instead of throwing`() = runTest {
        val source = File(context.cacheDir, "capture.jpg").apply { writeText("jpeg bytes") }
        imagesDir.mkdirs()
        assumeWritableToggleWorks()
        imagesDir.setWritable(false)

        val path = store.persistCapture(source)

        assertNull("A failed write must be reported as null", path)
    }

    /** A half-written file would show up forever as a corrupt thumbnail. */
    @Test
    fun `a failed capture leaves no partial file behind`() = runTest {
        val source = File(context.cacheDir, "capture.jpg").apply { writeText("jpeg bytes") }
        imagesDir.mkdirs()
        assumeWritableToggleWorks()
        imagesDir.setWritable(false)

        store.persistCapture(source)
        imagesDir.setWritable(true)

        val leftovers = imagesDir.listFiles()?.filter { it.name.startsWith("photo_") }.orEmpty()
        assertTrue("Expected no partial photos, found $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `an unreadable uri import returns null instead of throwing`() = runTest {
        val missing = android.net.Uri.fromFile(File(context.cacheDir, "nothing-here.jpg"))

        assertNull(store.importFromUri(missing))
    }

    @Test
    fun `deleting a photo that is already gone is not an error`() = runTest {
        store.delete(File(context.filesDir, "images/never-existed.jpg").absolutePath)
    }

    /**
     * Running as root ignores the permission bit, which would make the failure
     * tests silently prove nothing. Fail loudly rather than pass vacuously.
     */
    private fun assumeWritableToggleWorks() {
        val probe = File(imagesDir, "probe")
        imagesDir.setWritable(false)
        val blocked = runCatching { probe.writeText("x") }.isFailure
        imagesDir.setWritable(true)
        probe.delete()
        assertTrue(
            "This environment ignores the read-only bit, so the disk-full tests would be vacuous",
            blocked
        )
    }
}
