package com.landpoint.app.data.export

import com.landpoint.app.data.model.Land

/**
 * CSV read/write. Deliberately hand-rolled: the format is small, and this keeps
 * the app free of another dependency while handling quoted fields correctly.
 */
object CsvSupport {

    val HEADER = listOf(
        "uuid", "name", "description", "notes", "latitude", "longitude",
        "altitude", "accuracy", "address", "parcel_number", "area_sqm",
        "created_at", "updated_at"
    )

    fun write(lands: List<Land>): String = buildString {
        appendLine(HEADER.joinToString(","))
        lands.forEach { land ->
            val row = listOf(
                land.id,
                land.name,
                land.description,
                land.notes,
                land.latitude.toString(),
                land.longitude.toString(),
                land.altitude?.toString() ?: "",
                land.accuracy?.toString() ?: "",
                land.address ?: "",
                land.parcelNumber ?: "",
                land.areaSqm?.toString() ?: "",
                land.createdAt.toString(),
                land.updatedAt.toString()
            )
            appendLine(row.joinToString(",") { escape(it) })
        }
    }

    private fun escape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /**
     * Reads a number written in either convention.
     *
     * A spreadsheet set to Indonesian — or any European locale — writes
     * `-6,914744`, and plain [String.toDoubleOrNull] rejects it. Dropping those
     * rows silently was losing whole files, so both conventions are accepted.
     * The separator that appears last decides the fraction, which is what
     * distinguishes `1.234,56` from `1,234.56`.
     */
    internal fun parseDecimal(raw: String?): Double? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toDoubleOrNull()?.let { return it }

        val lastDot = value.lastIndexOf('.')
        val lastComma = value.lastIndexOf(',')
        return when {
            // Both present: the later one is the decimal mark, the other groups.
            lastDot >= 0 && lastComma >= 0 ->
                if (lastComma > lastDot) value.replace(".", "").replace(',', '.').toDoubleOrNull()
                else value.replace(",", "").toDoubleOrNull()
            // Comma only. For a coordinate a grouping comma makes no sense, and
            // an out-of-range result is rejected by the caller either way.
            lastComma >= 0 -> value.replace(',', '.').toDoubleOrNull()
            else -> null
        }
    }

    /**
     * Picks the column separator by counting candidates in the first line.
     *
     * A locale that writes decimal commas also exports semicolon-separated
     * files, so assuming a comma would collapse every row into one field.
     */
    internal fun sniffDelimiter(text: String): Char {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        var inQuotes = false
        val counts = mutableMapOf(',' to 0, ';' to 0, '\t' to 0)
        firstLine.forEach { c ->
            when {
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && counts.containsKey(c) -> counts[c] = counts.getValue(c) + 1
            }
        }
        val best = counts.maxByOrNull { it.value }
        return if (best != null && best.value > 0) best.key else ','
    }

    /**
     * Parses CSV into rows of fields, handling quoted fields with embedded
     * commas, quotes and newlines.
     */
    fun parse(text: String, delimiter: Char = sniffDelimiter(text)): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            if (row.size > 1 || row.firstOrNull()?.isNotBlank() == true) rows.add(row)
            row = mutableListOf()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"'); i++
                    }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == delimiter -> endField()
                c == '\r' -> {
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                    endRow()
                }
                c == '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /**
     * Maps parsed rows to import records. Tolerates unknown column order and
     * missing optional columns; a row is skipped when it has no usable
     * latitude/longitude pair.
     */
    fun toRecords(rows: List<List<String>>): List<ImportRecord> = read(rows).records

    /**
     * Same mapping as [toRecords], but also reports how many data rows were
     * thrown away. A silent drop let a whole unreadable file look like an empty
     * one, so the count is carried out to the import summary.
     */
    fun read(rows: List<List<String>>): CsvReadResult {
        if (rows.isEmpty()) return CsvReadResult(emptyList(), 0)
        val header = rows.first().map { it.trim().lowercase().replace(" ", "_") }

        fun idx(vararg names: String): Int =
            names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } } ?: -1

        val iUuid = idx("uuid", "id")
        val iName = idx("name", "title", "label")
        val iDesc = idx("description", "desc")
        val iNotes = idx("notes", "note")
        val iLat = idx("latitude", "lat")
        val iLon = idx("longitude", "lon", "lng", "long")
        val iAlt = idx("altitude", "alt", "elevation")
        val iAcc = idx("accuracy", "acc")
        val iAddr = idx("address")
        val iParcel = idx("parcel_number", "parcel", "parcel_no")
        val iArea = idx("area_sqm", "area")
        val iCreated = idx("created_at", "created")
        val iUpdated = idx("updated_at", "updated")

        if (iLat < 0 || iLon < 0) return CsvReadResult(emptyList(), 0)

        fun List<String>.at(index: Int): String? =
            if (index >= 0 && index < size) this[index].trim().takeIf { it.isNotEmpty() } else null

        var skipped = 0
        val records = rows.drop(1).mapNotNull { row ->
            // A row of empty strings is trailing whitespace, not lost data.
            if (row.all { it.isBlank() }) return@mapNotNull null

            val lat = parseDecimal(row.at(iLat))
            val lon = parseDecimal(row.at(iLon))
            if (lat == null || lon == null ||
                lat !in -90.0..90.0 || lon !in -180.0..180.0
            ) {
                skipped++
                return@mapNotNull null
            }
            ImportRecord(
                uuid = row.at(iUuid),
                name = row.at(iName) ?: "Unnamed",
                description = row.at(iDesc) ?: "",
                notes = row.at(iNotes) ?: "",
                latitude = lat,
                longitude = lon,
                altitude = parseDecimal(row.at(iAlt)),
                accuracy = parseDecimal(row.at(iAcc))?.toFloat(),
                address = row.at(iAddr),
                parcelNumber = row.at(iParcel),
                areaSqm = parseDecimal(row.at(iArea)),
                createdAt = row.at(iCreated)?.toLongOrNull(),
                updatedAt = row.at(iUpdated)?.toLongOrNull()
            )
        }
        return CsvReadResult(records, skipped)
    }
}

/** Records read from a CSV, plus the data rows that could not be understood. */
data class CsvReadResult(
    val records: List<ImportRecord>,
    val unreadableRows: Int
)

/** Provider-neutral record produced by both the JSON and CSV readers. */
data class ImportRecord(
    val uuid: String?,
    val name: String,
    val description: String,
    val notes: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val address: String?,
    val parcelNumber: String?,
    val areaSqm: Double?,
    val createdAt: Long?,
    val updatedAt: Long?,
    /**
     * A CSV has no column for a boundary, so these stay at their defaults there;
     * only the JSON reader fills them in. Without them a restore turned every
     * mapped boundary back into a bare point.
     */
    val geometryType: String = com.landpoint.app.data.model.GeometryType.POINT,
    val geometryJson: String? = null,
    val photos: List<PhotoExportJson> = emptyList()
)
