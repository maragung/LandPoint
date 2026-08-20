package com.landpoint.app.data.export

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.PhotoStore
import com.landpoint.app.data.db.LandDatabase
import com.landpoint.app.data.model.GeometryCodec
import com.landpoint.app.data.model.GeometryType
import com.landpoint.app.data.model.toEntity
import com.landpoint.app.util.GeoPoint
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The backup archive is the answer to a lost or reset phone, so what these tests
 * check is the property the user actually depends on: everything that was in the
 * database comes back — boundary geometry and photo bytes included — after the
 * originals are gone.
 */
@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {

    private lateinit var context: Context
    private lateinit var db: LandDatabase
    private lateinit var photoStore: PhotoStore
    private lateinit var repository: LandRepository
    private lateinit var manager: BackupManager

    private val boundary = listOf(
        GeoPoint(-6.914744, 107.609810),
        GeoPoint(-6.914744, 107.610210),
        GeoPoint(-6.914344, 107.610210)
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        photoStore = PhotoStore(context)
        repository = LandRepository(db.landDao(), photoStore)
        manager = BackupManager(
            context = context,
            repository = repository,
            photoStore = photoStore,
            importExport = ImportExportManager(context, repository)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun archive() = File.createTempFile("backup", ".zip")

    /** Writes a photo file the way the store would, and links it to a land. */
    private suspend fun addPhoto(landId: String, bytes: ByteArray): String {
        val path = photoStore.persistStream(bytes.inputStream())!!
        repository.addPhoto(landId, path, caption = "pagar utara")
        return path
    }

    private suspend fun seedLandWithBoundary(name: String = "Kebun kopi"): String {
        val land = LandTestFactory.land(name = name, areaSqm = 1600.0).copy(
            geometryType = GeometryType.POLYGON,
            geometryJson = GeometryCodec.encode(boundary)
        )
        repository.save(land.toEntity())
        return land.id
    }

    @Test
    fun `round trip restores records, boundary and photo bytes`() = runTest {
        val id = seedLandWithBoundary()
        val bytes = ByteArray(512) { (it % 251).toByte() }
        val originalPath = addPhoto(id, bytes)

        val file = archive()
        val result = manager.backup(Uri.fromFile(file))
        assertFalse(result.error ?: "", result.isFailure)
        assertEquals(1, result.lands)
        assertEquals(1, result.photos)
        assertEquals(0, result.missingPhotos)
        assertTrue(file.length() > 0)

        // The phone is gone: records and image files both.
        repository.deleteAll()
        File(originalPath).delete()
        assertTrue(repository.getAllLands().isEmpty())

        val restored = manager.restore(Uri.fromFile(file), DuplicateStrategy.KEEP_BOTH)
        assertFalse(restored.error ?: "", restored.isFailure)
        assertEquals(1, restored.result!!.imported)

        val lands = repository.getAllLands()
        assertEquals(1, lands.size)
        val land = lands.single()
        assertEquals("Kebun kopi", land.name)
        assertTrue(land.isPolygon)
        assertEquals(boundary.size, land.boundary.size)
        assertEquals(boundary[1].longitude, land.boundary[1].longitude, 1e-9)
        assertEquals(1600.0, land.areaSqm!!, 1e-6)

        assertEquals(1, land.photos.size)
        val restoredFile = File(land.photos.single().filePath)
        assertTrue("restored photo must exist", restoredFile.exists())
        assertArrayEquals(bytes, restoredFile.readBytes())
        assertEquals("pagar utara", land.photos.single().caption)
    }

    @Test
    fun `a photo whose file vanished does not abort the backup`() = runTest {
        val id = seedLandWithBoundary()
        val path = addPhoto(id, ByteArray(64) { 1 })
        // Simulates storage cleaned by the OS while the row survived.
        File(path).delete()

        val file = archive()
        val result = manager.backup(Uri.fromFile(file))
        assertFalse(result.isFailure)
        assertEquals(1, result.lands)
        assertEquals(0, result.photos)
        assertEquals(1, result.missingPhotos)

        repository.deleteAll()
        val restored = manager.restore(Uri.fromFile(file), DuplicateStrategy.KEEP_BOTH)
        assertFalse(restored.isFailure)
        // The land — the irreplaceable part — still came back.
        assertEquals(1, repository.getAllLands().size)
        assertTrue(repository.getAllLands().single().photos.isEmpty())
    }

    @Test
    fun `restoring a file that is not a backup reports instead of throwing`() = runTest {
        val file = archive()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("holiday.txt"))
            zip.write("nothing to do with land".toByteArray())
            zip.closeEntry()
        }

        val restored = manager.restore(Uri.fromFile(file), DuplicateStrategy.SKIP)
        assertTrue(restored.isFailure)
        assertNotNull(restored.error)
        assertNull(restored.result)
        assertTrue(repository.getAllLands().isEmpty())
    }

    @Test
    fun `a truncated archive fails without writing half a database`() = runTest {
        val id = seedLandWithBoundary()
        addPhoto(id, ByteArray(2048) { 7 })
        val file = archive()
        manager.backup(Uri.fromFile(file))

        val whole = file.readBytes()
        val cut = archive()
        cut.writeBytes(whole.copyOfRange(0, whole.size / 2))

        repository.deleteAll()
        val restored = manager.restore(Uri.fromFile(cut), DuplicateStrategy.KEEP_BOTH)
        assertTrue("a half file must not look like a success", restored.isFailure)
        assertTrue(repository.getAllLands().isEmpty())
    }

    /**
     * A zip entry name comes from the file, not from us. An entry that climbs out
     * of `photos/` must be ignored rather than followed — the archive is a file
     * the user was handed by someone else as easily as written by this app.
     */
    @Test
    fun `an entry escaping the photo directory is ignored`() = runTest {
        val id = seedLandWithBoundary()
        addPhoto(id, ByteArray(32) { 3 })
        val file = archive()
        manager.backup(Uri.fromFile(file))

        // Rebuild the archive with a traversal entry appended.
        val poisoned = archive()
        val original = java.util.zip.ZipInputStream(file.inputStream())
        ZipOutputStream(poisoned.outputStream()).use { out ->
            original.use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    out.putNextEntry(ZipEntry(entry.name))
                    input.copyTo(out)
                    out.closeEntry()
                    entry = input.nextEntry
                }
            }
            out.putNextEntry(ZipEntry("photos/../../databases/evil.db"))
            out.write("owned".toByteArray())
            out.closeEntry()
        }

        val before = context.filesDir.parentFile
            ?.let { File(it, "databases/evil.db") }
        repository.deleteAll()
        val restored = manager.restore(Uri.fromFile(poisoned), DuplicateStrategy.KEEP_BOTH)

        assertFalse(restored.error ?: "", restored.isFailure)
        assertFalse("traversal entry must not land on disk", before?.exists() ?: false)
        // The legitimate content still restored.
        assertEquals(1, repository.getAllLands().size)
    }

    @Test
    fun `suggested file name is a zip stamped with the moment`() {
        val name = BackupManager.suggestFileName()
        assertTrue(name, name.startsWith("landpoint-backup-"))
        assertTrue(name, name.endsWith(".zip"))
        assertTrue(name, name.length > "landpoint-backup-.zip".length)
    }
}
