package com.landpoint.app.ui.lands

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.landpoint.app.PendingDeletes
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.PhotoStore
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.data.db.LandDatabase
import com.landpoint.app.data.export.ImportExportManager
import com.landpoint.app.data.export.LandTestFactory
import com.landpoint.app.data.export.PdfExporter
import com.landpoint.app.data.model.toEntity
import com.landpoint.app.location.LocationProvider
import com.landpoint.app.util.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Bulk selection is the one place where a stale id turns into the wrong file on
 * disk: whatever is ticked here is what a later tap writes out or deletes. These
 * tests pin the rules that keep the tick honest — it never survives the land
 * leaving the list, and it always clears once the action has run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LandListViewModelTest {

    private lateinit var context: Context
    private lateinit var db: LandDatabase
    private lateinit var repository: LandRepository
    private lateinit var pendingDeletes: PendingDeletes
    private lateinit var viewModel: LandListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LandRepository(db.landDao(), PhotoStore(context))
        pendingDeletes = PendingDeletes(repository)
        viewModel = LandListViewModel(
            repository = repository,
            locationProvider = LocationProvider(context),
            importExport = ImportExportManager(context, repository),
            pdfExporter = PdfExporter(context, AppStrings(context)),
            strings = AppStrings(context),
            settings = SettingsRepository(context),
            pendingDeletes = pendingDeletes
        )
    }

    @After
    fun tearDown() {
        // Cancels any waiting delete: its timer outlives the test and would fire
        // against a closed database.
        pendingDeletes.undo()
        db.close()
        Dispatchers.resetMain()
    }

    /** `uiState` only produces while something collects it. */
    private fun TestScope.collectState() {
        backgroundScope.launch { viewModel.uiState.collect { } }
    }

    /**
     * Room's query executor and DataStore's own scope both emit from real
     * background threads, so the state this screen shows arrives when it
     * arrives. Every wait here is a real one with a deadline rather than a
     * virtual-time advance, which would return before the database had said
     * anything at all and make the assertion below vacuous.
     */
    private suspend fun awaitState(
        reason: String,
        predicate: (LandListUiState) -> Boolean
    ): LandListUiState = withContext(Dispatchers.Default) {
        try {
            withTimeout(10_000) { viewModel.uiState.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $reason; last state was ${describe()}")
        }
    }

    private fun describe(): String = viewModel.uiState.value.let {
        "lands=${it.lands.map(com.landpoint.app.data.model.Land::name)} " +
                "selected=${it.selectedIds.size} busy=${it.isBusy} message=${it.message}"
    }

    private suspend fun seed(vararg names: String): List<String> =
        names.map { name ->
            val land = LandTestFactory.land(name = name)
            repository.save(land.toEntity())
            land.id
        }

    @Test
    fun `tapping a land twice ticks then unticks it`() = runTest {
        val (first) = seed("Sawah utara")
        collectState()
        awaitState("the seeded land to appear") { it.lands.size == 1 }

        viewModel.toggleSelection(first)
        val ticked = awaitState("the land to be ticked") { it.isSelecting }
        assertEquals(setOf(first), ticked.selectedIds)
        assertEquals(1, ticked.selectedCount)

        viewModel.toggleSelection(first)
        val cleared = awaitState("the tick to come off") { !it.isSelecting }
        assertEquals(emptySet<String>(), cleared.selectedIds)
    }

    @Test
    fun `select all ticks the search result, not the whole database`() = runTest {
        seed("Sawah utara", "Sawah selatan", "Kebun kopi")
        collectState()
        awaitState("all three lands") { it.lands.size == 3 }

        viewModel.setQuery("sawah")
        awaitState("the search to narrow to two") { it.lands.size == 2 }

        viewModel.selectAllShown()
        val state = awaitState("both matches ticked") { it.selectedCount == 2 }
        assertTrue(state.allShownSelected)
        assertTrue(state.selectedLands.all { it.name.startsWith("Sawah") })
    }

    /**
     * The rule the view model's own comment claims: a land that has dropped out
     * of the filter must not stay ticked and reappear inside an export the user
     * can no longer see they asked for.
     */
    @Test
    fun `a land filtered out of view drops out of the selection`() = runTest {
        val ids = seed("Sawah utara", "Kebun kopi")
        collectState()
        awaitState("both lands") { it.lands.size == 2 }

        viewModel.selectAllShown()
        awaitState("both ticked") { it.selectedCount == 2 }

        viewModel.setQuery("kopi")
        val state = awaitState("the search to narrow to one") { it.lands.size == 1 }
        assertEquals(setOf(ids[1]), state.selectedIds)
        assertEquals(listOf("Kebun kopi"), state.selectedLands.map { it.name })
    }

    @Test
    fun `a land deleted underneath the selection drops out of it`() = runTest {
        val ids = seed("Sawah utara", "Kebun kopi")
        collectState()
        awaitState("both lands") { it.lands.size == 2 }

        viewModel.selectAllShown()
        awaitState("both ticked") { it.selectedCount == 2 }

        repository.delete(ids[0])
        val state = awaitState("the deleted land to leave the list") { it.lands.size == 1 }
        assertEquals(setOf(ids[1]), state.selectedIds)
    }

    @Test
    fun `deleting the selection removes exactly those lands and clears the ticks`() = runTest {
        val ids = seed("Sawah utara", "Sawah selatan", "Kebun kopi")
        collectState()
        awaitState("all three lands") { it.lands.size == 3 }

        viewModel.toggleSelection(ids[0])
        viewModel.toggleSelection(ids[2])
        awaitState("two ticked") { it.selectedCount == 2 }

        viewModel.deleteSelected()
        val state = awaitState("the delete to finish") {
            it.lands.size == 1 && !it.isSelecting && !it.isBusy
        }
        assertNotNull("the user must be told what happened", state.message)
        assertTrue("Undo has to be on offer while the window is open", state.canUndoDelete)
        // Still on disk: the delete is deferred so that Undo has something to
        // undo, which is the only reason the photo files are still intact.
        assertEquals(3, repository.getAllLands().size)

        // Once the window closes it is a real delete.
        pendingDeletes.flush()
        assertEquals(listOf("Sawah selatan"), repository.getAllLands().map { it.name })
    }

    @Test
    fun `undoing a delete brings the lands back and touches nothing on disk`() = runTest {
        val ids = seed("Sawah utara", "Sawah selatan")
        collectState()
        awaitState("both lands") { it.lands.size == 2 }

        viewModel.toggleSelection(ids[0])
        awaitState("one ticked") { it.selectedCount == 1 }

        viewModel.deleteSelected()
        awaitState("the land to disappear from the list") { it.lands.size == 1 }

        viewModel.undoDelete()
        val state = awaitState("the land to come back") { it.lands.size == 2 }
        assertEquals(2, repository.getAllLands().size)
        assertFalse("nothing is waiting to be deleted any more", state.canUndoDelete)
        assertFalse("undo must not leave the list in selection mode", state.isSelecting)
    }

    @Test
    fun `exporting the selection writes only the ticked lands and clears the ticks`() = runTest {
        val ids = seed("Sawah utara", "Sawah selatan", "Kebun kopi")
        collectState()
        awaitState("all three lands") { it.lands.size == 3 }

        viewModel.toggleSelection(ids[2])
        awaitState("one ticked") { it.selectedCount == 1 }

        val target = File.createTempFile("selection", ".csv")
        viewModel.exportSelectedCsv(Uri.fromFile(target))
        val state = awaitState("the export to finish") {
            !it.isSelecting && !it.isBusy && it.message != null
        }

        val csv = target.readText()
        assertTrue("exported file must hold the ticked land", csv.contains("Kebun kopi"))
        assertFalse("an unticked land must not reach the file", csv.contains("Sawah utara"))
        assertNotNull(state.message)
        // An export deletes nothing.
        assertEquals(3, repository.getAllLands().size)
    }

    @Test
    fun `exporting with nothing ticked does nothing at all`() = runTest {
        seed("Sawah utara")
        collectState()
        awaitState("the seeded land") { it.lands.size == 1 }

        val target = File.createTempFile("empty-selection", ".csv")
        target.writeText("untouched")
        viewModel.exportSelectedCsv(Uri.fromFile(target))

        // Nothing to wait for, so give a wrongly-fired export room to land.
        withContext(Dispatchers.Default) { delay(500) }
        assertEquals("untouched", target.readText())
        assertFalse(viewModel.uiState.value.isBusy)
    }

    @Test
    fun `a consumed message does not show again`() = runTest {
        val (id) = seed("Sawah utara")
        collectState()
        awaitState("the seeded land") { it.lands.size == 1 }

        viewModel.toggleSelection(id)
        awaitState("one ticked") { it.selectedCount == 1 }
        viewModel.deleteSelected()
        awaitState("the delete to report") { it.message != null && !it.isBusy }

        viewModel.consumeMessage()
        awaitState("the message to be cleared") { it.message == null }
    }
}
