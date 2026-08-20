package com.landpoint.app.data.export

import androidx.annotation.StringRes
import com.landpoint.app.R
import com.landpoint.app.data.model.Land

/** What to do when an imported record matches something already stored. */
enum class DuplicateStrategy(@StringRes val labelRes: Int) {
    /** Keep the stored copy, ignore the incoming one. */
    SKIP(R.string.duplicate_skip),

    /** Overwrite the stored copy with the incoming one. */
    REPLACE(R.string.duplicate_replace),

    /** Store the incoming one as a separate entry under a new id. */
    KEEP_BOTH(R.string.duplicate_keep_both)
}

data class ImportResult(
    val imported: Int = 0,
    val replaced: Int = 0,
    val skipped: Int = 0,
    val invalid: Int = 0,
    /** Photos attached during a restore; always 0 for a plain JSON/CSV import. */
    val photos: Int = 0,
    val error: String? = null
) {
    val total: Int get() = imported + replaced + skipped
    val isFailure: Boolean get() = error != null
}

data class ExportResult(
    val count: Int = 0,
    val error: String? = null
) {
    val isFailure: Boolean get() = error != null
}

enum class ExportFormat(val mimeType: String, val extension: String) {
    JSON("application/json", "json"),
    CSV("text/csv", "csv"),
    PDF("application/pdf", "pdf"),

    // Neither MIME type is registered with IANA, but these are the strings
    // mapping apps actually filter on when picking a file.
    GPX("application/gpx+xml", "gpx"),
    KML("application/vnd.google-earth.kml+xml", "kml")
}

fun Land.toExportJson() = LandExportJson(
    uuid = id,
    name = name,
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
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun LandExportJson.toRecord() = ImportRecord(
    uuid = uuid,
    name = name,
    description = description,
    notes = notes,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    accuracy = accuracy,
    address = address,
    parcelNumber = parcelNumber,
    areaSqm = areaSqm,
    createdAt = createdAt,
    updatedAt = updatedAt,
    geometryType = geometryType,
    geometryJson = geometryJson,
    photos = photos
)
