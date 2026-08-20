package com.landpoint.app.data.export

import android.content.Context
import android.net.Uri
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.PhotoStore
import com.landpoint.app.data.model.Land
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-app backup into one file the user keeps themselves.
 *
 * Everything stays on the device: the archive is written straight to a location
 * the user picked through the system file picker. Nothing is uploaded and no
 * account is involved.
 *
 * Layout inside the archive:
 *   landpoint.json   — the same envelope as a plain JSON export
 *   photos/<uuid>.jpg — one entry per photo, named by the photo's own id
 *
 * A plain .json export remains supported for people who only want the numbers;
 * this one exists so the photos survive a lost phone too.
 */
class BackupManager(
    private val context: Context,
    private val repository: LandRepository,
    private val photoStore: PhotoStore,
    private val importExport: ImportExportManager
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun backup(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        runCatching {
            val lands = repository.getAllLands()
            var photosWritten = 0
            var photosMissing = 0

            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: error("Cannot open file for writing")

            output.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    val entries = lands.map { land -> land.toBackupJson() }

                    zip.putNextEntry(ZipEntry(MANIFEST))
                    val envelope = LandPointBackup(
                        version = CURRENT_BACKUP_VERSION,
                        exportedAt = System.currentTimeMillis(),
                        lands = entries
                    )
                    zip.write(json.encodeToString(envelope).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    // A photo whose file has gone missing must not abort the
                    // whole backup — the land records are the irreplaceable part.
                    lands.forEach { land ->
                        land.photos.forEach { photo ->
                            val source = photoStore.openForBackup(photo.filePath)
                            if (source == null) {
                                photosMissing++
                                return@forEach
                            }
                            zip.putNextEntry(ZipEntry("$PHOTO_DIR${photo.id}.jpg"))
                            source.use { it.copyTo(zip) }
                            zip.closeEntry()
                            photosWritten++
                        }
                    }
                }
            }

            BackupResult(
                lands = lands.size,
                photos = photosWritten,
                missingPhotos = photosMissing
            )
        }.getOrElse { BackupResult(error = it.message ?: "Backup failed") }
    }

    /**
     * A backup written by this app names every photo entry after the photo's own
     * id, so the manifest can point at it without depending on the originating
     * phone's file layout.
     */
    private fun Land.toBackupJson(): LandExportJson = toExportJson().copy(
        photos = photos.map { photo ->
            PhotoExportJson(
                uuid = photo.id,
                fileName = "${photo.id}.jpg",
                caption = photo.caption,
                createdAt = photo.createdAt
            )
        }
    )

    // ---------------- restore ----------------

    /**
     * Reads an archive back. Photos are extracted first so a land is only ever
     * written once its images are already on disk — a restore interrupted
     * half-way then leaves orphaned files rather than records pointing at
     * pictures that never arrived.
     */
    suspend fun restore(uri: Uri, strategy: DuplicateStrategy): RestoreResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Cannot open file for reading")

                var manifest: LandPointBackup? = null
                val photoPaths = mutableMapOf<String, String>()

                input.use { raw ->
                    ZipInputStream(raw).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            when {
                                name == MANIFEST || name.endsWith("/$MANIFEST") -> {
                                    val text = zip.readBytes().toString(Charsets.UTF_8)
                                    manifest = runCatching {
                                        json.decodeFromString<LandPointBackup>(text)
                                    }.getOrNull()
                                }

                                isPhotoEntry(entry, name) -> {
                                    // persistStream leaves the zip stream open on
                                    // purpose; closing it would end the archive.
                                    photoStore.persistStream(zip)?.let { stored ->
                                        photoPaths[name.substringAfterLast('/')] = stored
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }

                val backup = manifest
                    ?: return@withContext RestoreResult(
                        error = "This file is not a LandPoint backup"
                    )

                // Photos extracted for lands that end up skipped as duplicates
                // would otherwise sit in storage forever with nothing pointing
                // at them, so the applied result decides what stays.
                val applied = importExport.applyRecords(
                    records = backup.lands.map { it.toRecord() },
                    strategy = strategy,
                    photoPaths = photoPaths
                )
                discardUnusedPhotos(photoPaths, applied.photos)

                RestoreResult(result = applied)
            }.getOrElse { RestoreResult(error = it.message ?: "Restore failed") }
        }

    /** Deletes extracted images that no restored land claimed. */
    private suspend fun discardUnusedPhotos(paths: Map<String, String>, linked: Int) {
        if (linked >= paths.size) return
        val kept = repository.allPhotoPaths()
        paths.values.filterNot { it in kept }.forEach { photoStore.delete(it) }
    }

    /**
     * Rejects anything that is not a plain photo directly inside `photos/`.
     *
     * A zip entry name is attacker-controlled data, not a trusted path: an entry
     * called `photos/../../databases/lands.db` would otherwise be written outside
     * app storage. The name is only ever used as a map key here, but the guard
     * belongs at the read, not at the use.
     */
    private fun isPhotoEntry(entry: ZipEntry, name: String): Boolean {
        if (entry.isDirectory) return false
        if (!name.startsWith(PHOTO_DIR)) return false
        val leaf = name.removePrefix(PHOTO_DIR)
        return leaf.isNotEmpty() && !leaf.contains('/') && !leaf.contains('\\') && leaf != ".."
    }

    companion object {
        private const val MANIFEST = "landpoint.json"
        private const val PHOTO_DIR = "photos/"

        fun suggestFileName(): String {
            val stamp = android.text.format.DateFormat
                .format("yyyyMMdd-HHmmss", System.currentTimeMillis())
            return "landpoint-backup-$stamp.zip"
        }
    }
}

data class BackupResult(
    val lands: Int = 0,
    val photos: Int = 0,
    val missingPhotos: Int = 0,
    val error: String? = null
) {
    val isFailure: Boolean get() = error != null
}

/** The archive as read, and what applying it to the database did. */
data class RestoreResult(
    val result: ImportResult? = null,
    val error: String? = null
) {
    val isFailure: Boolean get() = error != null
}
