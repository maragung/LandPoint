package com.landpoint.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Photos live in app-private storage, so no media permission is ever needed and
 * uninstalling the app removes them with the rest of the data.
 */
class PhotoStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "images").apply { if (!exists()) mkdirs() }

    /** Cache dir is what [FileProvider] exposes, so camera targets go there first. */
    private val cacheDir: File
        get() = File(context.cacheDir, "images").apply { if (!exists()) mkdirs() }

    fun newCameraTarget(): Pair<File, Uri> {
        val file = File(cacheDir, "capture_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return file to uri
    }

    /**
     * Moves a finished camera capture out of cache into permanent storage.
     *
     * Returns null on a full disk or an unreadable capture rather than
     * throwing — the caller shows a "photo could not be saved" message, and a
     * failed photo must never cost the user the land record it belongs to.
     */
    suspend fun persistCapture(source: File): String? = withContext(Dispatchers.IO) {
        if (!source.exists() || source.length() == 0L) return@withContext null
        val target = File(dir, "photo_${UUID.randomUUID()}.jpg")
        runCatching {
            source.copyTo(target, overwrite = true)
            source.delete()
            target.absolutePath
        }.getOrElse {
            // A copy that ran out of disk leaves a half-written file behind.
            // Left in place it would show up as a corrupt thumbnail forever.
            target.delete()
            null
        }
    }

    /** Copies a user-picked image (content:// uri) into app storage. */
    suspend fun importFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        val target = File(dir, "photo_${UUID.randomUUID()}.jpg")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            target.absolutePath
        }.getOrElse {
            target.delete()
            null
        }
    }

    /**
     * Copies an already-open stream into app storage — used when restoring a
     * photo out of a backup archive, where there is no uri to open.
     *
     * The stream is deliberately *not* closed here: a zip entry stream must stay
     * usable for the next entry, and closing it would end the whole archive read.
     */
    suspend fun persistStream(input: InputStream): String? = withContext(Dispatchers.IO) {
        val target = File(dir, "photo_${UUID.randomUUID()}.jpg")
        runCatching {
            target.outputStream().use { output -> input.copyTo(output) }
            if (target.length() == 0L) {
                target.delete()
                return@runCatching null
            }
            target.absolutePath
        }.getOrElse {
            target.delete()
            null
        }
    }

    /** Opens a stored photo for writing into an archive; null if it is gone. */
    fun openForBackup(path: String): InputStream? = runCatching {
        val file = File(path)
        if (file.exists() && file.length() > 0L) file.inputStream() else null
    }.getOrNull()

    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        runCatching { File(path).delete() }
        Unit
    }

    /**
     * Stages a copy of a stored photo and returns a uri the receiving app may
     * read.
     *
     * With [stripLocation] on, the GPS tags are cleared from the staged copy —
     * never from the stored original, which is this app's own record and is what
     * mapping software reads back. What the recipient still gets is the stamp
     * [PhotoStamper] drew into the pixels: a human can read where the photo was
     * taken, while the machine-readable fix that every app in the share chain
     * would have harvested is gone.
     */
    fun shareUriFor(path: String, stripLocation: Boolean = true): Uri? = runCatching {
        val source = File(path)
        val staged = File(cacheDir, source.name)
        source.copyTo(staged, overwrite = true)
        // Refuse rather than fall back: a photo whose tags could not be cleared
        // must not be shared with the coordinates still on it.
        if (stripLocation && !clearLocationExif(staged)) {
            staged.delete()
            return@runCatching null
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged)
    }.getOrNull()

    /** Blanks every GPS tag in place; false if the file could not be rewritten. */
    private fun clearLocationExif(file: File): Boolean = runCatching {
        val exif = ExifInterface(file)
        listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD
        ).forEach { exif.setAttribute(it, null) }
        exif.saveAttributes()
        true
    }.getOrDefault(false)
}
