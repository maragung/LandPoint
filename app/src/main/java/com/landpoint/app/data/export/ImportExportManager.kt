package com.landpoint.app.data.export

import android.content.Context
import android.net.Uri
import com.landpoint.app.data.LandRepository
import com.landpoint.app.data.model.Land
import com.landpoint.app.data.model.LandEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * JSON and CSV import/export. Files are read and written through SAF uris, so
 * the app needs no storage permission at all.
 */
class ImportExportManager(
    private val context: Context,
    private val repository: LandRepository
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ---------------- export ----------------

    suspend fun exportJson(uri: Uri, lands: List<Land>): ExportResult = withContext(Dispatchers.IO) {
        runCatching {
            val backup = LandPointBackup(
                version = 1,
                exportedAt = System.currentTimeMillis(),
                lands = lands.map { it.toExportJson() }
            )
            writeText(uri, json.encodeToString(backup))
            ExportResult(count = lands.size)
        }.getOrElse { ExportResult(error = it.message ?: "Export failed") }
    }

    suspend fun exportCsv(uri: Uri, lands: List<Land>): ExportResult = withContext(Dispatchers.IO) {
        runCatching {
            writeText(uri, CsvSupport.write(lands))
            ExportResult(count = lands.size)
        }.getOrElse { ExportResult(error = it.message ?: "Export failed") }
    }

    suspend fun exportGpx(uri: Uri, lands: List<Land>): ExportResult =
        withContext(Dispatchers.IO) {
            runCatching {
                writeText(uri, GeoExporter.toGpx(lands, disclaimer()))
                ExportResult(count = lands.size)
            }.getOrElse { ExportResult(error = it.message ?: "Export failed") }
        }

    suspend fun exportKml(uri: Uri, lands: List<Land>): ExportResult =
        withContext(Dispatchers.IO) {
            runCatching {
                writeText(uri, GeoExporter.toKml(lands, disclaimer()))
                ExportResult(count = lands.size)
            }.getOrElse { ExportResult(error = it.message ?: "Export failed") }
        }

    /**
     * Carried into every exported file. A GPX or KML opened months later in some
     * other program has none of the app's on-screen context, so the note that
     * these coordinates are not a legal survey has to travel with the data.
     */
    private fun disclaimer(): String =
        context.getString(com.landpoint.app.R.string.legal_disclaimer)

    private fun writeText(uri: Uri, text: String) {
        // "wt" truncates — without it, overwriting a longer file leaves a tail of
        // the previous contents behind and the result is corrupt.
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: error("Cannot open file for writing")
    }

    // ---------------- import ----------------

    suspend fun import(uri: Uri, strategy: DuplicateStrategy): ImportResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = readText(uri)
                val read = readRecords(text)
                    ?: return@withContext ImportResult(error = "Unrecognised file format")
                if (read.records.isEmpty()) {
                    // Distinguish "nothing in the file" from "nothing we could
                    // read": the second one means the user's data is still
                    // there, just in a shape this importer rejected.
                    val error = if (read.unreadableRows > 0) {
                        "No valid locations found — ${read.unreadableRows} row(s) could not be read"
                    } else {
                        "No valid locations found in file"
                    }
                    return@withContext ImportResult(error = error)
                }
                applyRecords(read.records, strategy)
                    .let { it.copy(invalid = it.invalid + read.unreadableRows) }
            }.getOrElse { ImportResult(error = it.message ?: "Import failed") }
        }

    private fun readText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Cannot open file for reading")

    /**
     * Sniffs the content rather than trusting the file extension, so a .txt
     * holding JSON still restores correctly.
     */
    internal fun parseRecords(text: String): List<ImportRecord>? = readRecords(text)?.records

    /**
     * As [parseRecords], but keeps the count of rows that could not be read so
     * the user is told about data that was dropped instead of silently losing it.
     */
    internal fun readRecords(text: String): CsvReadResult? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return CsvReadResult(emptyList(), 0)

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            // Full backup envelope
            runCatching {
                json.decodeFromString<LandPointBackup>(trimmed)
            }.getOrNull()?.let { backup ->
                return CsvReadResult(backup.lands.map { l -> l.toRecord() }, 0)
            }

            // Bare array of lands
            runCatching {
                json.decodeFromString<List<LandExportJson>>(trimmed)
            }.getOrNull()?.let { lands ->
                return CsvReadResult(lands.map { l -> l.toRecord() }, 0)
            }

            return null
        }

        return CsvSupport.read(CsvSupport.parse(trimmed))
    }

    /**
     * Writes records to the database, honouring [strategy] for anything that
     * matches something already stored.
     *
     * [photoPaths] maps a backup archive's photo file name to where that image
     * now lives on this device; it is empty for every import that is not a
     * restore. Photos are attached to the id the land was *actually* written
     * under, which is not the incoming uuid when the strategy is KEEP_BOTH.
     */
    internal suspend fun applyRecords(
        records: List<ImportRecord>,
        strategy: DuplicateStrategy,
        photoPaths: Map<String, String> = emptyMap()
    ): ImportResult {
        var imported = 0
        var replaced = 0
        var skipped = 0
        var invalid = 0
        var photosLinked = 0

        val withIds = records.mapNotNull { it.uuid }
        val existingIds = repository.findExistingIds(withIds)

        val toWrite = mutableListOf<LandEntity>()
        // Photos cannot be inserted until their land row exists, or the foreign
        // key rejects them.
        val pendingPhotos = mutableListOf<Pair<String, PhotoExportJson>>()

        for (record in records) {
            if (record.latitude !in -90.0..90.0 || record.longitude !in -180.0..180.0) {
                invalid++
                continue
            }

            val duplicateById = record.uuid != null && record.uuid in existingIds
            val duplicateByCoords = if (!duplicateById) {
                repository.findDuplicateByCoordinates(record.name, record.latitude, record.longitude)
            } else null

            val isDuplicate = duplicateById || duplicateByCoords != null

            val writtenId: String? = when {
                !isDuplicate -> (record.uuid ?: UUID.randomUUID().toString()).also { imported++ }
                strategy == DuplicateStrategy.SKIP -> { skipped++; null }
                strategy == DuplicateStrategy.REPLACE -> {
                    replaced++
                    record.uuid?.takeIf { duplicateById }
                        ?: duplicateByCoords?.id
                        ?: UUID.randomUUID().toString()
                }
                strategy == DuplicateStrategy.KEEP_BOTH ->
                    UUID.randomUUID().toString().also { imported++ }
                else -> null
            }

            if (writtenId != null) {
                toWrite += record.toEntity(writtenId)
                record.photos.forEach { pendingPhotos += writtenId to it }
            }
        }

        if (toWrite.isNotEmpty()) repository.upsertAll(toWrite)

        for ((landId, photo) in pendingPhotos) {
            val path = photoPaths[photo.fileName] ?: continue
            // A restored photo gets a fresh row id: keeping the original would
            // collide when the same backup is restored twice with KEEP_BOTH.
            repository.addPhoto(landId, path, photo.caption)
            photosLinked++
        }

        return ImportResult(
            imported = imported,
            replaced = replaced,
            skipped = skipped,
            invalid = invalid,
            photos = photosLinked
        )
    }

    private fun ImportRecord.toEntity(id: String): LandEntity {
        val now = System.currentTimeMillis()
        return LandEntity(
            id = id,
            name = name.ifBlank { "Unnamed" },
            description = description,
            notes = notes,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            accuracy = accuracy,
            address = address,
            parcelNumber = parcelNumber,
            areaSqm = areaSqm,
            geometryType = geometryType,
            geometryJson = geometryJson,
            createdAt = createdAt ?: now,
            updatedAt = updatedAt ?: now
        )
    }

    fun suggestFileName(format: ExportFormat, single: Land? = null): String {
        val stamp = android.text.format.DateFormat.format("yyyyMMdd-HHmmss", System.currentTimeMillis())
        return if (single != null) {
            val safe = single.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "land" }
            "landpoint-$safe.${format.extension}"
        } else {
            "landpoint-backup-$stamp.${format.extension}"
        }
    }
}
