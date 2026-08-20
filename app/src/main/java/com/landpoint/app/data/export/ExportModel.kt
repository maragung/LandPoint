package com.landpoint.app.data.export

import com.landpoint.app.data.model.GeometryType
import kotlinx.serialization.Serializable

/**
 * Stable JSON schema for backup/restore. uuid is the stable identifier for
 * duplicate detection across imports. Once exported, a land's uuid never changes.
 *
 * Every field added after version 1 carries a default, and the reader is
 * configured with `ignoreUnknownKeys`, so a file written by any version of the
 * app can still be read by any other.
 */
@Serializable
data class LandExportJson(
    val uuid: String,
    val name: String,
    val description: String = "",
    val notes: String = "",
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val address: String? = null,
    val parcelNumber: String? = null,
    val areaSqm: Double? = null,
    /**
     * Added in version 2. Without these two a boundary drawn by the user was
     * silently flattened to its single centre point on export — the mapped shape
     * was lost and could not be got back.
     */
    val geometryType: String = GeometryType.POINT,
    val geometryJson: String? = null,
    /** Added in version 2; only ever populated inside a .zip archive. */
    val photos: List<PhotoExportJson> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A photo as it appears in a backup archive. [fileName] is the entry inside the
 * archive's `photos/` folder, never a path on the device it came from — the
 * originating phone's directory layout means nothing on the phone restoring it.
 */
@Serializable
data class PhotoExportJson(
    val uuid: String,
    val fileName: String,
    val caption: String = "",
    val createdAt: Long
)

@Serializable
data class LandPointBackup(
    val version: Int = CURRENT_BACKUP_VERSION,
    val exportedAt: Long,
    val lands: List<LandExportJson>
)

/** 1 = lands only. 2 = adds boundary geometry and photos. */
const val CURRENT_BACKUP_VERSION = 2
