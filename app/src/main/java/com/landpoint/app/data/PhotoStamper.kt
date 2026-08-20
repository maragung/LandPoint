package com.landpoint.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import com.landpoint.app.R
import com.landpoint.app.util.AppStrings
import com.landpoint.app.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What gets burned onto a capture. Everything is optional except the position —
 * a stamp is only written when we actually know where the photo was taken.
 */
data class StampData(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
    val altitudeM: Double?,
    val takenAt: Long,
    val address: String?,
    val dms: Boolean
)

/**
 * Draws the coordinates, timestamp and accuracy directly into the JPEG and also
 * writes them to EXIF. The visible stamp is what survives being shared over
 * chat apps (which strip metadata); the EXIF tags are what mapping software
 * reads back.
 *
 * Only ever applied to camera captures. A photo picked from the gallery was
 * taken somewhere else at some other time, so stamping it with the current fix
 * would put a confident-looking lie on the image.
 */
class PhotoStamper(
    private val context: Context,
    private val strings: AppStrings
) {

    suspend fun stamp(file: File, data: StampData): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val source = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching false
            val canvasBitmap = source.copy(Bitmap.Config.ARGB_8888, true)
                ?: return@runCatching false
            if (canvasBitmap !== source) source.recycle()

            drawStamp(canvasBitmap, buildLines(data))

            file.outputStream().use { out ->
                canvasBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            canvasBitmap.recycle()

            writeExif(file, data)
            true
        }.getOrDefault(false)
    }

    private fun buildLines(data: StampData): List<String> = buildList {
        add(
            if (data.dms) GeoUtils.formatDMS(data.latitude, data.longitude)
            else GeoUtils.formatDecimal(data.latitude, data.longitude)
        )

        val stampedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(data.takenAt))
        val detail = buildList {
            add(stampedAt)
            data.accuracyM?.let { add(strings.get(R.string.value_accuracy, it.roundToInt())) }
            data.altitudeM?.let { add(strings.get(R.string.value_metres, it.roundToInt())) }
        }
        add(detail.joinToString("  ·  "))

        data.address?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    private fun drawStamp(bitmap: Bitmap, lines: List<String>) {
        val canvas = Canvas(bitmap)
        // Scale off the short edge so portrait and landscape stamps look alike.
        val base = minOf(bitmap.width, bitmap.height)
        val textSize = base * 0.032f
        val pad = base * 0.022f
        val lineGap = textSize * 0.42f

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        // A shadow keeps the stamp readable if the backing bar is ever skipped.
        text.setShadowLayer(textSize * 0.08f, 0f, textSize * 0.04f, Color.BLACK)

        val lineHeight = textSize + lineGap
        val barHeight = lineHeight * lines.size + pad * 2 - lineGap

        val bar = Paint().apply { color = Color.argb(140, 0, 0, 0) }
        canvas.drawRect(
            0f,
            bitmap.height - barHeight,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            bar
        )

        var y = bitmap.height - barHeight + pad + textSize
        lines.forEach { line ->
            canvas.drawText(line, pad, y, text)
            y += lineHeight
        }
    }

    /** Writes standard GPS EXIF tags so other tools can read the position back. */
    private fun writeExif(file: File, data: StampData) {
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            exif.setLatLong(data.latitude, data.longitude)
            data.altitudeM?.let { exif.setAltitude(it) }
            exif.setAttribute(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(data.takenAt))
            )
            data.accuracyM?.let {
                exif.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, it.toRational())
            }
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, context.getString(R.string.app_name))
            exif.saveAttributes()
        }
    }

    /** EXIF rationals are "numerator/denominator"; two decimals is ample here. */
    private fun Float.toRational(): String = "${(this * 100).roundToInt()}/100"
}
