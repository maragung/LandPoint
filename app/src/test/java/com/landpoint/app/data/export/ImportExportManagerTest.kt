package com.landpoint.app.data.export

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.PhotoStore
import com.landpoint.app.data.db.LandDatabase
import com.landpoint.app.data.model.LandEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ImportExportManagerTest {

    private lateinit var context: Context
    private lateinit var db: LandDatabase
    private lateinit var repository: LandRepository
    private lateinit var manager: ImportExportManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LandRepository(db.landDao(), PhotoStore(context))
        manager = ImportExportManager(context, repository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------- format sniffing ----------------

    @Test
    fun `sniffs a backup envelope`() {
        val json = """
            {"version":1,"exportedAt":1700000000000,"lands":[
              {"uuid":"abc","name":"Kebun","description":"","notes":"",
               "latitude":-6.9,"longitude":107.6,"createdAt":1,"updatedAt":1}
            ]}
        """.trimIndent()
        val records = manager.parseRecords(json)
        assertNotNull(records)
        assertEquals(1, records!!.size)
        assertEquals("Kebun", records[0].name)
    }

    @Test
    fun `sniffs a bare json array`() {
        val json = """
            [{"uuid":"abc","name":"Sawah","description":"","notes":"",
              "latitude":-6.5,"longitude":107.2,"createdAt":1,"updatedAt":1}]
        """.trimIndent()
        val records = manager.parseRecords(json)
        assertEquals(1, records!!.size)
        assertEquals("Sawah", records[0].name)
    }

    @Test
    fun `sniffs csv when the content is not json`() {
        val records = manager.parseRecords("name,latitude,longitude\nWarung,-6.9,107.6")
        assertEquals(1, records!!.size)
        assertEquals("Warung", records[0].name)
    }

    @Test
    fun `returns null for json that is not a backup`() {
        assertEquals(null, manager.parseRecords("""{"unrelated":true}"""))
    }

    @Test
    fun `empty content yields no records rather than an error`() {
        assertEquals(emptyList<ImportRecord>(), manager.parseRecords("   "))
    }

    // ---------------- duplicate handling ----------------

    @Test
    fun `skip strategy leaves the stored copy untouched`() = runTest {
        seed(id = "keep-me", name = "Kebun", lat = -6.9, lon = 107.6, notes = "original")

        val result = importCsv(
            "uuid,name,latitude,longitude,notes\nkeep-me,Kebun,-6.9,107.6,incoming",
            DuplicateStrategy.SKIP
        )

        assertEquals(0, result.imported)
        assertEquals(1, result.skipped)
        assertEquals(1, repository.getAllLands().size)
        assertEquals("original", repository.getLand("keep-me")!!.notes)
    }

    @Test
    fun `replace strategy overwrites in place`() = runTest {
        seed(id = "keep-me", name = "Kebun", lat = -6.9, lon = 107.6, notes = "original")

        val result = importCsv(
            "uuid,name,latitude,longitude,notes\nkeep-me,Kebun,-6.9,107.6,incoming",
            DuplicateStrategy.REPLACE
        )

        assertEquals(1, result.replaced)
        assertEquals(1, repository.getAllLands().size)
        assertEquals("incoming", repository.getLand("keep-me")!!.notes)
    }

    @Test
    fun `keep both strategy stores a second copy under a new id`() = runTest {
        seed(id = "keep-me", name = "Kebun", lat = -6.9, lon = 107.6, notes = "original")

        val result = importCsv(
            "uuid,name,latitude,longitude,notes\nkeep-me,Kebun,-6.9,107.6,incoming",
            DuplicateStrategy.KEEP_BOTH
        )

        assertEquals(1, result.imported)
        val all = repository.getAllLands()
        assertEquals(2, all.size)
        assertTrue(all.map { it.notes }.containsAll(listOf("original", "incoming")))
    }

    @Test
    fun `a record with no uuid is a duplicate when name and coordinates match`() = runTest {
        seed(id = "stored", name = "Kebun", lat = -6.9, lon = 107.6, notes = "original")

        val result = importCsv(
            "name,latitude,longitude,notes\nKebun,-6.9,107.6,incoming",
            DuplicateStrategy.SKIP
        )

        assertEquals(1, result.skipped)
        assertEquals(1, repository.getAllLands().size)
    }

    @Test
    fun `a different name at the same spot is not a duplicate`() = runTest {
        seed(id = "stored", name = "Kebun", lat = -6.9, lon = 107.6)

        val result = importCsv(
            "name,latitude,longitude\nSawah,-6.9,107.6",
            DuplicateStrategy.SKIP
        )

        assertEquals(1, result.imported)
        assertEquals(2, repository.getAllLands().size)
    }

    @Test
    fun `invalid coordinates are counted and not stored`() = runTest {
        val result = importCsv(
            "name,latitude,longitude\nGood,-6.9,107.6\nBad,120.0,107.6",
            DuplicateStrategy.SKIP
        )

        // The CSV reader drops the out-of-range row before it reaches the writer.
        assertEquals(1, result.imported)
        assertEquals(1, repository.getAllLands().size)
    }

    @Test
    fun `import reports an error for unrecognised content`() = runTest {
        val result = manager.import(fileUri("not,a,known\nshape,of,file"), DuplicateStrategy.SKIP)
        assertTrue(result.isFailure)
    }

    // ---------------- export round trip ----------------

    @Test
    fun `json export re-imports to the same records`() = runTest {
        seed(id = "a", name = "Kebun", lat = -6.9, lon = 107.6, notes = "satu")
        seed(id = "b", name = "Sawah", lat = -6.5, lon = 107.2, notes = "dua")

        val target = File.createTempFile("backup", ".json")
        val export = manager.exportJson(Uri.fromFile(target), repository.getAllLands())
        assertEquals(2, export.count)

        repository.deleteAll()
        assertEquals(0, repository.getAllLands().size)

        val result = manager.import(Uri.fromFile(target), DuplicateStrategy.SKIP)
        assertEquals(2, result.imported)

        val restored = repository.getAllLands().associateBy { it.id }
        assertEquals("satu", restored["a"]!!.notes)
        assertEquals("dua", restored["b"]!!.notes)
        assertEquals(-6.5, restored["b"]!!.latitude, 1e-9)
    }

    @Test
    fun `csv export re-imports to the same records`() = runTest {
        seed(id = "a", name = "Kebun, dekat sungai", lat = -6.9, lon = 107.6, notes = "\"quoted\"")

        val target = File.createTempFile("export", ".csv")
        manager.exportCsv(Uri.fromFile(target), repository.getAllLands())

        repository.deleteAll()
        val result = manager.import(Uri.fromFile(target), DuplicateStrategy.SKIP)

        assertEquals(1, result.imported)
        val restored = repository.getAllLands().single()
        assertEquals("Kebun, dekat sungai", restored.name)
        assertEquals("\"quoted\"", restored.notes)
    }

    // ---------------- helpers ----------------

    private suspend fun seed(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        notes: String = ""
    ) {
        repository.save(
            LandEntity(
                id = id,
                name = name,
                latitude = lat,
                longitude = lon,
                notes = notes
            )
        )
    }

    private suspend fun importCsv(csv: String, strategy: DuplicateStrategy): ImportResult =
        manager.import(fileUri(csv), strategy)

    private fun fileUri(content: String): Uri {
        val file = File.createTempFile("import", ".csv")
        file.writeText(content)
        return Uri.fromFile(file)
    }
}
