package com.landpoint.app.map.offline

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.landpoint.app.map.ImportedArchiveSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** The one archive format MapLibre can read from a local file. */
private const val ARCHIVE_EXTENSION = "pmtiles"

/** Suffix on a copy still in progress, so a failed import cannot be mistaken for a map. */
private const val PARTIAL_SUFFIX = ".part"

/** Sidecar holding the hash of a completed import. */
private const val DIGEST_SUFFIX = ".sha256"

/**
 * Maps the user brought with them: PMTiles archives sitting on this device.
 *
 * This is the path that is unambiguously theirs to use. Nothing here fetches
 * anything — the user obtains the file however they like and imports it, which keeps
 * the app out of the redistribution chain entirely and is the only way to have
 * aerial imagery offline, since no imagery provider this app can reach for free
 * permits its tiles to be stored.
 *
 * The downloadable side of offline maps is [OfflineRegionStore], which saves an area
 * from a source whose licence allows it.
 */
class ArchiveStore(private val context: Context) {

    /**
     * Where archives live. `filesDir`, never `cacheDir`: Android reclaims cache
     * directories under storage pressure, and a map that disappears the week before
     * a field trip is worse than no offline map at all — the user finds out with no
     * signal to fetch it again.
     */
    val baseDir: File get() = File(context.filesDir, "maps")

    private val archiveDir: File get() = File(baseDir, "archives")

    /** What the file picker should offer. */
    fun supportedExtensions(): Set<String> = setOf(ARCHIVE_EXTENSION)

    /**
     * Copies a user-picked archive in, and refuses it if it is not a whole one.
     *
     * The copy is necessary rather than wasteful: a SAF uri is not a file path, and
     * MapLibre's PMTiles reader opens an absolute path off disk.
     *
     * It lands on a `.part` name first and is only renamed once the header has been
     * read back and checked. So an import interrupted by a dead battery or a
     * cancelled picker leaves a stray temporary file — cleaned up on the next import
     * — rather than an archive that looks fine in the list and draws nothing.
     */
    suspend fun importArchive(uri: Uri): ImportedArchive = withContext(Dispatchers.IO) {
        val name = sanitise(displayNameOf(uri) ?: "map.$ARCHIVE_EXTENSION")
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension != ARCHIVE_EXTENSION) {
            return@withContext ImportedArchive(error = UnsupportedType(extension))
        }

        archiveDir.mkdirs()
        clearPartials()

        val partial = File(archiveDir, name + PARTIAL_SUFFIX)
        val digest = runCatching {
            val sha = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        sha.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: error("cannot open the selected file")
            sha.digest().toHex()
        }.getOrElse { failure ->
            partial.delete()
            return@withContext ImportedArchive(error = CopyFailed(failure.message))
        }

        // Read back from the file just written, not from the stream that wrote it:
        // this is what proves the bytes that landed on disk are a whole archive.
        val header = when (val result = PmtilesHeader.read(partial)) {
            is PmtilesResult.Ok -> result.header
            else -> {
                partial.delete()
                return@withContext ImportedArchive(error = Damaged(result))
            }
        }

        val target = File(archiveDir, name)
        if (!partial.renameTo(target)) {
            partial.delete()
            return@withContext ImportedArchive(error = CopyFailed("could not store the file"))
        }
        File(archiveDir, name + DIGEST_SUFFIX).writeText(digest)

        ImportedArchive(
            name = name,
            bytes = target.length(),
            kind = header.kindOf(),
            error = null
        )
    }

    /** Imported maps, newest first, each with what its header says it holds. */
    suspend fun archives(): List<OfflineArchive> = withContext(Dispatchers.IO) {
        archiveFiles().map { file ->
            val header = (PmtilesHeader.read(file) as? PmtilesResult.Ok)?.header
            OfflineArchive(
                name = file.name,
                bytes = file.length(),
                kind = header?.kindOf() ?: MapKind.UNREADABLE,
                minZoom = header?.minZoom ?: 0,
                maxZoom = header?.maxZoom ?: 0
            )
        }
    }

    /**
     * The archive to draw, described well enough to build a style from, or null when
     * there is nothing importable on the device.
     *
     * [preferred] is the file name the user chose. A name that no longer resolves —
     * the file was deleted, or the archive turned out to be unreadable — falls
     * through to the newest one that does, because a user who has an offline map and
     * asked for offline maps should get a map rather than an empty screen.
     */
    suspend fun spec(preferred: String?): ImportedArchiveSpec? = withContext(Dispatchers.IO) {
        val candidates = archiveFiles()
        val ordered = if (preferred == null) {
            candidates
        } else {
            val chosen = candidates.filter { it.name == preferred }
            chosen + (candidates - chosen.toSet())
        }
        ordered.firstNotNullOfOrNull { file ->
            (PmtilesHeader.read(file) as? PmtilesResult.Ok)?.let { ok ->
                ImportedArchiveSpec(
                    file = file,
                    vector = ok.header.isVector,
                    minZoom = ok.header.minZoom.toDouble()
                )
            }
        }
    }

    /**
     * Removes an archive and its hash.
     *
     * The name is sanitised and the parent checked before anything is deleted, so a
     * crafted name cannot walk out of the directory.
     */
    suspend fun deleteArchive(name: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(archiveDir, sanitise(name))
        if (target.parentFile != archiveDir) return@withContext false
        val deleted = target.delete()
        if (deleted) File(archiveDir, target.name + DIGEST_SUFFIX).delete()
        deleted
    }

    /**
     * Re-hashes an archive and compares it with what was recorded at import.
     *
     * Null when there is nothing to compare against — an archive imported before
     * hashes were kept, which is not a failure. Reads the whole file, so it belongs
     * behind a button the user pressed and not on the path to opening a map.
     */
    suspend fun verify(name: String): Boolean? = withContext(Dispatchers.IO) {
        val target = File(archiveDir, sanitise(name))
        if (target.parentFile != archiveDir || !target.isFile) return@withContext false
        val recorded = File(archiveDir, target.name + DIGEST_SUFFIX)
            .takeIf { it.isFile }?.readText()?.trim()?.lowercase()
            ?: return@withContext null
        val sha = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                sha.update(buffer, 0, read)
            }
        }
        sha.digest().toHex() == recorded
    }

    /** Bytes held by imported archives, for showing what offline maps cost. */
    suspend fun archiveBytes(): Long = withContext(Dispatchers.IO) {
        archiveFiles().sumOf { it.length() }
    }

    private fun archiveFiles(): List<File> =
        archiveDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == ARCHIVE_EXTENSION }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /** Leftovers from an import that never finished. Not an error, just rubbish. */
    private fun clearPartials() {
        archiveDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(PARTIAL_SUFFIX) }
            ?.forEach { it.delete() }
    }

    /**
     * The user-facing file name behind a SAF uri. Needed for the extension: the uri
     * path is an opaque document id and carries no reliable suffix.
     */
    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun sanitise(name: String): String =
        name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun PmtilesHeader.kindOf(): MapKind =
        if (isVector) MapKind.VECTOR else MapKind.RASTER_ARCHIVE
}

/**
 * How a stored map is read: geometry rendered on the device, or ready-made pictures.
 *
 * [UNREADABLE] is listed rather than hidden. A file the user imported and can see in
 * their file manager should appear in the app too, with the reason it cannot be used
 * — a map that silently vanishes from the list looks like the app lost it.
 */
enum class MapKind { RASTER_ARCHIVE, VECTOR, UNREADABLE }

data class OfflineArchive(
    val name: String,
    val bytes: Long,
    val kind: MapKind = MapKind.RASTER_ARCHIVE,
    val minZoom: Int = 0,
    val maxZoom: Int = 0
)

sealed interface ArchiveError

/** The picked file is not a `.pmtiles` archive. */
data class UnsupportedType(val extension: String) : ArchiveError

/** The bytes could not be copied in. */
data class CopyFailed(val reason: String?) : ArchiveError

/** Copied, but the archive itself is not usable — see [PmtilesResult]. */
data class Damaged(val reason: PmtilesResult) : ArchiveError

data class ImportedArchive(
    val name: String? = null,
    val bytes: Long = 0,
    val kind: MapKind = MapKind.RASTER_ARCHIVE,
    val error: ArchiveError? = null
) {
    val isFailure: Boolean get() = error != null
}
