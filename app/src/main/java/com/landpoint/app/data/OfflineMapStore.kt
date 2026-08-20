package com.landpoint.app.data

import android.app.Application
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.modules.ArchiveFileFactory
import java.io.File

/**
 * Offline map support.
 *
 * Deliberately *not* a bulk tile downloader. The default map source is
 * OpenStreetMap's own tile server, and its usage policy forbids downloading
 * areas in bulk; osmdroid enforces that itself — `TileSourceFactory.MAPNIK`
 * carries `FLAG_NO_BULK`, so constructing a `CacheManager` against it throws
 * `TileSourcePolicyException`. Shipping a "download this area" button would mean
 * pointing every user of this app at someone else's donated bandwidth.
 *
 * So offline maps work the three ways that are actually allowed:
 *
 *  1. Tiles viewed normally are kept. osmdroid already caches every tile it
 *     fetches; the app just has to store that cache somewhere Android will not
 *     silently reclaim. Panning over the plot once, on signal, leaves it
 *     readable in the field.
 *  2. A prepared raster archive can be imported — `.mbtiles`, `.gemf` or `.zip`
 *     from a source that permits redistribution.
 *  3. A mapsforge vector map (`.map`, e.g. from OpenAndroMaps) can be imported.
 *     This is the best of the three: rendering happens on the device from a
 *     local file, so a whole province fits in tens of megabytes and using the
 *     map afterwards touches the network exactly zero times.
 *
 * Nothing here downloads a map by itself. The user fetches the file in a
 * browser and imports it, which keeps this app off other people's servers and
 * keeps us out of the redistribution chain — the vector maps carry CC-BY-SA data
 * plus separate terms for their elevation and coastline layers, and none of that
 * binds an app that merely reads a file the user already had.
 */
class OfflineMapStore(private val context: Context) {

    /**
     * Where tiles live. `filesDir`, never `cacheDir`: Android deletes cache
     * directories under storage pressure, and a map that vanishes the week
     * before a field trip is worse than no offline map at all — the user would
     * only find out with no signal to re-fetch it.
     */
    val baseDir: File get() = File(context.filesDir, "osmdroid")

    private val tileCacheDir: File get() = File(baseDir, "tiles")

    /** Archives dropped in here are picked up by osmdroid on the next map open. */
    private val archiveDir: File get() = baseDir

    /** Applies the paths to osmdroid. Called once, before any map is created. */
    fun configure() {
        tileCacheDir.mkdirs()
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = baseDir
            osmdroidTileCache = tileCacheDir
            // Kept generous on purpose: an expired tile is still a perfectly good
            // picture of a field boundary, and re-fetching it needs a network the
            // user may not have.
            expirationExtendedDuration = ARCHIVE_TILE_LIFETIME_MS
        }
    }

    /**
     * Extensions that can be imported: osmdroid's raster archive formats plus
     * mapsforge's `.map`, which osmdroid itself knows nothing about — it is read
     * by the mapsforge renderer, not by [ArchiveFileFactory].
     */
    fun supportedArchiveExtensions(): Set<String> =
        ArchiveFileFactory.getRegisteredExtensions() + VECTOR_EXTENSION

    /**
     * Copies a user-picked map archive into the osmdroid base directory.
     *
     * The copy is required rather than wasteful: a SAF uri is not a file path,
     * and osmdroid opens archives directly off disk.
     */
    suspend fun importArchive(uri: Uri): ImportedArchive =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = sanitise(displayNameOf(uri) ?: "map.mbtiles")
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension !in supportedArchiveExtensions()) {
                    return@withContext ImportedArchive(error = UnsupportedType(extension))
                }

                archiveDir.mkdirs()
                val target = File(archiveDir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Cannot open the selected file")

                ImportedArchive(name = name, bytes = target.length())
            }.getOrElse { ImportedArchive(error = CopyFailed(it.message)) }
        }

    /** Maps currently available offline, newest first. */
    fun archives(): List<OfflineArchive> {
        val extensions = supportedArchiveExtensions()
        return archiveDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in extensions }
            ?.sortedByDescending { it.lastModified() }
            ?.map { OfflineArchive(it.name, it.length(), kindOf(it)) }
            .orEmpty()
    }

    /** The imported vector maps, in a stable order so the map looks the same twice. */
    fun vectorMapFiles(): List<File> =
        archiveDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == VECTOR_EXTENSION }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * A tile source that renders the imported vector maps, or null if there are
     * none — in which case the caller should stay on the online raster source.
     *
     * Off the main thread on purpose: this opens every map file and reads its
     * header. Callers must [MapsForgeTileSource.dispose] the result, since it
     * holds those files open for as long as the map is on screen.
     */
    suspend fun vectorTileSource(): MapsForgeTileSource? = withContext(Dispatchers.IO) {
        val files = vectorMapFiles()
        if (files.isEmpty()) return@withContext null
        runCatching {
            initVectorRenderer()
            MapsForgeTileSource.createFromFiles(files.toTypedArray())
        }.getOrNull()
    }

    /**
     * mapsforge needs its Android graphics factory built from the Application
     * before any map file is opened. Done lazily rather than in [configure] so a
     * user who never imports a vector map never pays for it at startup.
     */
    private fun initVectorRenderer() {
        if (vectorRendererReady) return
        val application = context.applicationContext as? Application
            ?: error("Vector maps need an Application context")
        MapsForgeTileSource.createInstance(application)
        vectorRendererReady = true
    }

    private fun kindOf(file: File): MapKind =
        if (file.extension.lowercase() == VECTOR_EXTENSION) MapKind.VECTOR
        else MapKind.RASTER_ARCHIVE

    fun deleteArchive(name: String): Boolean {
        // Guards against a crafted name walking out of the directory.
        val target = File(archiveDir, sanitise(name))
        return target.parentFile == archiveDir && target.delete()
    }

    /** Bytes held by the tile cache database, for showing what offline maps cost. */
    fun cachedTileBytes(): Long =
        tileCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * The user-facing file name behind a SAF uri. Needed for the extension: the
     * uri path is an opaque document id and carries no reliable suffix.
     */
    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun sanitise(name: String): String =
        name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        /** One year. Long enough that offline maps stay usable between seasons. */
        const val ARCHIVE_TILE_LIFETIME_MS = 365L * 24 * 60 * 60 * 1000

        /** mapsforge's vector map format, as published by OpenAndroMaps. */
        const val VECTOR_EXTENSION = "map"

        /** Process-wide, because the graphics factory it builds is process-wide. */
        @Volatile
        var vectorRendererReady = false
    }
}

/** How a stored map is read: as pre-rendered tiles, or rendered on the device. */
enum class MapKind { RASTER_ARCHIVE, VECTOR }

data class OfflineArchive(
    val name: String,
    val bytes: Long,
    val kind: MapKind = MapKind.RASTER_ARCHIVE
)

sealed interface ArchiveError
data class UnsupportedType(val extension: String) : ArchiveError
data class CopyFailed(val reason: String?) : ArchiveError

data class ImportedArchive(
    val name: String? = null,
    val bytes: Long = 0,
    val error: ArchiveError? = null
) {
    val isFailure: Boolean get() = error != null
}
