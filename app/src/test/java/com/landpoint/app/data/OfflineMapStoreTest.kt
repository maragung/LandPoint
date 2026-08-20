package com.landpoint.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.map.reader.MapFile
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OfflineMapStoreTest {

    private lateinit var context: Context
    private lateinit var store: OfflineMapStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = OfflineMapStore(context)
        store.baseDir.deleteRecursively()
    }

    @Test
    fun `configure points osmdroid at files dir not cache dir`() {
        store.configure()

        val base = org.osmdroid.config.Configuration.getInstance().osmdroidBasePath
        // The whole point of the offline feature: Android may reclaim cacheDir,
        // and a map that disappears in the field is worse than none.
        assertTrue(base.absolutePath.startsWith(context.filesDir.absolutePath))
        assertFalse(base.absolutePath.startsWith(context.cacheDir.absolutePath))
    }

    @Test
    fun `mbtiles is a supported archive type`() {
        assertTrue(store.supportedArchiveExtensions().contains("mbtiles"))
    }

    @Test
    fun `archives lists map files and ignores everything else`() {
        store.baseDir.mkdirs()
        File(store.baseDir, "jawa-barat.mbtiles").writeText("x")
        File(store.baseDir, "notes.txt").writeText("x")

        val found = store.archives()

        assertEquals(1, found.size)
        assertEquals("jawa-barat.mbtiles", found.first().name)
    }

    @Test
    fun `delete removes a stored archive`() {
        store.baseDir.mkdirs()
        val archive = File(store.baseDir, "area.mbtiles")
        archive.writeText("x")

        assertTrue(store.deleteArchive("area.mbtiles"))
        assertFalse(archive.exists())
    }

    /** A name that climbs out of the directory must not delete anything. */
    @Test
    fun `delete refuses to escape the map directory`() {
        store.baseDir.mkdirs()
        val outside = File(context.filesDir, "keep-me.txt")
        outside.writeText("x")

        assertFalse(store.deleteArchive("../keep-me.txt"))
        assertTrue(outside.exists())
    }

    @Test
    fun `import rejects a file type osmdroid cannot read`() = runTest {
        val source = File(context.cacheDir, "holiday.jpg")
        source.writeText("not a map")

        val result = store.importArchive(android.net.Uri.fromFile(source))

        assertTrue(result.isFailure)
        assertEquals(UnsupportedType("jpg"), result.error)
        assertNull(result.name)
    }

    /**
     * `.map` is mapsforge's format, not one of osmdroid's archive types, so it
     * has to be admitted separately or the importer would reject the very file
     * OpenAndroMaps hands the user.
     */
    @Test
    fun `map is an importable type even though osmdroid does not know it`() {
        assertTrue(store.supportedArchiveExtensions().contains("map"))
    }

    @Test
    fun `archives distinguishes a vector map from a tile archive`() {
        store.baseDir.mkdirs()
        File(store.baseDir, "jawa-barat.map").writeText("x")
        File(store.baseDir, "area.mbtiles").writeText("x")

        val byName = store.archives().associateBy { it.name }

        assertEquals(MapKind.VECTOR, byName.getValue("jawa-barat.map").kind)
        assertEquals(MapKind.RASTER_ARCHIVE, byName.getValue("area.mbtiles").kind)
    }

    @Test
    fun `vector map files ignores tile archives`() {
        store.baseDir.mkdirs()
        File(store.baseDir, "bali.map").writeText("x")
        File(store.baseDir, "area.mbtiles").writeText("x")

        val found = store.vectorMapFiles()

        assertEquals(1, found.size)
        assertEquals("bali.map", found.first().name)
    }

    @Test
    fun `no vector source when nothing has been imported`() = runTest {
        store.baseDir.mkdirs()

        assertNull(store.vectorTileSource())
    }

    /** A truncated or mistyped download must not take the map screen down. */
    @Test
    fun `a corrupt map file yields no source instead of throwing`() = runTest {
        store.baseDir.mkdirs()
        File(store.baseDir, "broken.map").writeText("this is not a mapsforge file")

        assertNull(store.vectorTileSource())
    }

    /**
     * The one test that puts a genuine mapsforge file through the real path:
     * graphics factory, header parse, `MultiMapDataStore`, tile source. Every
     * other vector test here only proves we reject the wrong things.
     */
    @Test
    fun `a real map file opens as a vector source`() = runTest {
        installFixtureMap("jawa-barat.map")

        val source = store.vectorTileSource()

        assertNotNull("A valid mapsforge file must produce a source", source)
        source!!.dispose()
    }

    /**
     * Asserts the header was actually decoded rather than merely accepted: the
     * area the source reports has to match what the file itself declares, read
     * independently through mapsforge's own reader.
     */
    @Test
    fun `the opened source reports the area the map header declares`() = runTest {
        val file = installFixtureMap("bali.map")
        // MapDataStore has close() but does not implement Closeable, so `use`
        // does not apply here.
        val reader = MapFile(file)
        val expected = try {
            reader.boundingBox()
        } finally {
            reader.close()
        }

        val source = store.vectorTileSource()
        assertNotNull(source)
        val actual = source!!.bounds
        source.dispose()

        assertEquals(expected.minLatitude, actual.minLatitude, TOLERANCE)
        assertEquals(expected.minLongitude, actual.minLongitude, TOLERANCE)
        assertEquals(expected.maxLatitude, actual.maxLatitude, TOLERANCE)
        assertEquals(expected.maxLongitude, actual.maxLongitude, TOLERANCE)
    }

    /**
     * Pins the fixture's published extent. Without this the test above would
     * still pass if the fixture were swapped for some other map, and the header
     * decode would go unverified against anything external.
     */
    @Test
    fun `the fixture covers the extent its generator was given`() = runTest {
        installFixtureMap("fixture.map")

        val source = store.vectorTileSource()
        assertNotNull(source)
        val bounds = source!!.bounds
        source.dispose()

        assertEquals(FIXTURE_MIN_DEGREES, bounds.minLatitude, TOLERANCE)
        assertEquals(FIXTURE_MIN_DEGREES, bounds.minLongitude, TOLERANCE)
        assertEquals(FIXTURE_MAX_DEGREES, bounds.maxLatitude, TOLERANCE)
        assertEquals(FIXTURE_MAX_DEGREES, bounds.maxLongitude, TOLERANCE)
    }

    /**
     * A user importing one map per province is the normal case, so the
     * multi-file store has to hold more than one open at a time.
     */
    @Test
    fun `several real map files open as a single source`() = runTest {
        installFixtureMap("a-jawa.map")
        installFixtureMap("b-bali.map")

        assertEquals(2, store.vectorMapFiles().size)
        val source = store.vectorTileSource()

        assertNotNull("Both files should load into one data store", source)
        source!!.dispose()
    }

    /** Copies the bundled mapsforge fixture into the store's map directory. */
    private fun installFixtureMap(name: String): File {
        store.baseDir.mkdirs()
        val target = File(store.baseDir, name)
        val stream = javaClass.getResourceAsStream(FIXTURE_RESOURCE)
            ?: error("Test fixture $FIXTURE_RESOURCE is missing from the classpath")
        stream.use { input -> target.outputStream().use(input::copyTo) }
        return target
    }

    private companion object {
        /**
         * A 2.4 KB map from mapsforge's own reader tests: three nodes and one
         * motorway, written by osmosis with `bbox=0,0,0.08,0.08`.
         */
        const val FIXTURE_RESOURCE = "/maps/with-data.map"

        /** The bbox above. Stored in the header as microdegrees, so it is exact. */
        const val FIXTURE_MIN_DEGREES = 0.0
        const val FIXTURE_MAX_DEGREES = 0.08

        /** Well under a microdegree, the header's own resolution. */
        const val TOLERANCE = 1e-9
    }
}
