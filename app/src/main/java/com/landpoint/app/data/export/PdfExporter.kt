package com.landpoint.app.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.landpoint.app.R
import com.landpoint.app.data.SettingsRepository
import com.landpoint.app.data.model.Land
import com.landpoint.app.util.AppStrings
import com.landpoint.app.util.AreaFormat
import com.landpoint.app.util.BoundarySketch
import com.landpoint.app.util.GeoPoint
import com.landpoint.app.util.GeoUtils
import com.landpoint.app.util.PolygonMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * PDF report writer built on android.graphics.pdf — keeps the app dependency-free
 * and works offline. A4 at 72dpi is 595x842pt.
 */
class PdfExporter(
    private val context: Context,
    private val strings: AppStrings
) {

    private companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 48f
        const val LINE = 18f
        const val ACCENT = 0xFF1F6F4A.toInt()
        // Matched to the photo block below it, so a record with both reads as
        // two panels of one report rather than two unrelated pictures.
        const val SKETCH_WIDTH = 220f
        const val SKETCH_HEIGHT = 150f
        /** Reserved along the bottom of the box for the scale bar. */
        const val SKETCH_BAR_STRIP = 14f
        /**
         * Above this many corners the numbers are drawn no longer: a walked
         * track of hundreds of points would be a ring of unreadable digits, and
         * the outline is the useful part of it.
         */
        const val SKETCH_MAX_NUMBERED = 24
    }

    private val dateFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

    private val titlePaint = Paint().apply {
        color = ACCENT
        textSize = 22f
        isAntiAlias = true
        isFakeBoldText = true
    }
    private val headingPaint = Paint().apply {
        color = Color.BLACK
        textSize = 14f
        isAntiAlias = true
        isFakeBoldText = true
    }
    private val labelPaint = Paint().apply {
        color = Color.parseColor("#666666")
        textSize = 10f
        isAntiAlias = true
    }
    private val bodyPaint = Paint().apply {
        color = Color.BLACK
        textSize = 12f
        isAntiAlias = true
    }
    private val rulePaint = Paint().apply {
        color = Color.parseColor("#DDDDDD")
        strokeWidth = 1f
    }
    private val footerPaint = Paint().apply {
        color = Color.parseColor("#888888")
        textSize = 9f
        isAntiAlias = true
    }
    // Alpha is set after the colour, not folded into it: the setter replaces the
    // alpha channel of whatever colour is already there, so the order matters.
    private val sketchFillPaint = Paint().apply {
        color = ACCENT
        alpha = 38
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val sketchLinePaint = Paint().apply {
        color = ACCENT
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        isAntiAlias = true
    }
    private val sketchCornerPaint = Paint().apply {
        color = ACCENT
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val sketchFramePaint = Paint().apply {
        color = Color.parseColor("#DDDDDD")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val sketchLabelPaint = Paint().apply {
        color = Color.parseColor("#444444")
        textSize = 7f
        isAntiAlias = true
    }

    suspend fun exportSingle(
        uri: Uri,
        land: Land,
        dms: Boolean,
        areaUnit: SettingsRepository.AreaUnit = SettingsRepository.AreaUnit.SQM
    ): ExportResult =
        export(uri, listOf(land), dms, areaUnit, strings.get(R.string.pdf_title_single))

    suspend fun exportReport(
        uri: Uri,
        lands: List<Land>,
        dms: Boolean,
        areaUnit: SettingsRepository.AreaUnit = SettingsRepository.AreaUnit.SQM
    ): ExportResult =
        export(uri, lands, dms, areaUnit, strings.get(R.string.pdf_title_report))

    private suspend fun export(
        uri: Uri,
        lands: List<Land>,
        dms: Boolean,
        areaUnit: SettingsRepository.AreaUnit,
        title: String
    ): ExportResult = withContext(Dispatchers.IO) {
        runCatching {
            val document = PdfDocument()
            val state = RenderState(document, title)

            state.startPage()
            state.drawTitle(title, lands.size)

            lands.forEachIndexed { index, land ->
                state.drawLand(land, index + 1, dms, areaUnit)
            }

            state.finishPage()

            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                document.writeTo(out)
            } ?: error("Cannot open file for writing")

            document.close()
            ExportResult(count = lands.size)
        }.getOrElse { ExportResult(error = it.message ?: strings.get(R.string.msg_pdf_generic)) }
    }

    private inner class RenderState(
        val document: PdfDocument,
        val docTitle: String
    ) {
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = MARGIN
        var pageNumber = 0

        fun startPage() {
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            page = document.startPage(info)
            canvas = page!!.canvas
            y = MARGIN
        }

        fun finishPage() {
            val p = page ?: return
            drawFooter()
            document.finishPage(p)
            page = null
            canvas = null
        }

        private fun drawFooter() {
            val c = canvas ?: return
            c.drawText(
                strings.get(R.string.pdf_footer_page, pageNumber),
                MARGIN,
                PAGE_HEIGHT - MARGIN / 2,
                footerPaint
            )
            c.drawText(
                strings.get(R.string.pdf_footer_disclaimer),
                MARGIN,
                PAGE_HEIGHT - MARGIN / 2 - 12f,
                footerPaint
            )
        }

        /** Starts a new page when [needed] points would overflow the footer area. */
        fun ensureSpace(needed: Float) {
            if (y + needed > PAGE_HEIGHT - MARGIN - 24f) {
                finishPage()
                startPage()
            }
        }

        fun drawTitle(title: String, count: Int) {
            val c = canvas ?: return
            c.drawText(title, MARGIN, y + 16f, titlePaint)
            y += 30f
            val now = dateFormat.format(Date())
            val subtitle = if (count == 1) {
                strings.get(R.string.pdf_exported_on, now)
            } else {
                strings.get(R.string.pdf_count_exported, count, now)
            }
            c.drawText(subtitle, MARGIN, y, labelPaint)
            y += 14f
            c.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rulePaint)
            y += LINE
        }

        fun drawLand(
            land: Land,
            index: Int,
            dms: Boolean,
            areaUnit: SettingsRepository.AreaUnit
        ) {
            ensureSpace(160f)
            val c = canvas ?: return

            c.drawText("$index. ${land.name}", MARGIN, y, headingPaint)
            y += LINE

            val coords = if (dms) GeoUtils.formatDMS(land.latitude, land.longitude)
            else GeoUtils.formatDecimal(land.latitude, land.longitude)

            field(strings.get(R.string.label_coordinates), coords)
            land.parcelNumber?.takeIf { it.isNotBlank() }?.let {
                field(strings.get(R.string.label_parcel_number), it)
            }
            land.address?.takeIf { it.isNotBlank() }?.let {
                field(strings.get(R.string.label_address), it)
            }
            land.altitude?.let {
                field(
                    strings.get(R.string.label_altitude),
                    strings.get(R.string.value_metres, it.toInt())
                )
            }
            land.accuracy?.let {
                field(
                    strings.get(R.string.label_gps_accuracy),
                    strings.get(R.string.value_accuracy, it.toInt())
                )
            }
            land.areaSqm?.let {
                field(
                    strings.get(R.string.label_area),
                    strings.get(areaUnit.valueRes, AreaFormat.value(it, areaUnit))
                )
            }
            // A printed report is where the boundary matters most: it is the page
            // someone takes to a meeting, so the corner count and perimeter belong
            // next to the area they were derived from.
            val ring = land.boundary
            if (land.isPolygon && ring.size >= 3) {
                field(
                    strings.get(R.string.boundary_title),
                    strings.plural(R.plurals.boundary_corners, ring.size)
                )
                field(
                    strings.get(R.string.label_perimeter),
                    strings.get(
                        R.string.value_metres,
                        PolygonMath.perimeterM(ring).roundToInt()
                    )
                )
                drawSketch(ring)
            }
            field(strings.get(R.string.label_saved), dateFormat.format(Date(land.createdAt)))
            if (land.description.isNotBlank()) {
                field(strings.get(R.string.label_description), land.description)
            }
            if (land.notes.isNotBlank()) {
                field(strings.get(R.string.label_notes), land.notes)
            }

            land.photos.firstOrNull()?.let { photo -> drawPhoto(photo.filePath) }

            y += 6f
            canvas?.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rulePaint)
            y += LINE
        }

        private fun field(label: String, value: String) {
            val wrapped = wrap(value, bodyPaint, PAGE_WIDTH - MARGIN * 2 - 110f)
            ensureSpace(LINE * wrapped.size)
            val c = canvas ?: return
            c.drawText(label, MARGIN, y, labelPaint)
            wrapped.forEachIndexed { i, line ->
                c.drawText(line, MARGIN + 110f, y, bodyPaint)
                if (i < wrapped.lastIndex) y += LINE - 4f
            }
            y += LINE - 2f
        }

        /**
         * The outline itself, to scale, north up.
         *
         * A page that states an area and a perimeter but not a shape can describe
         * two quite different parcels, and the reader has no way to tell which one
         * was measured. Drawn small and beside the figures rather than given a page
         * of its own: this is a report to be checked against a certificate, not a
         * survey drawing to be worked from.
         */
        private fun drawSketch(ring: List<GeoPoint>) {
            val sketch = BoundarySketch.of(
                ring,
                SKETCH_WIDTH,
                SKETCH_HEIGHT - SKETCH_BAR_STRIP
            ) ?: return
            ensureSpace(SKETCH_HEIGHT + 12f)
            val c = canvas ?: return
            y += 6f
            val left = MARGIN + 110f
            val top = y
            c.drawText(strings.get(R.string.pdf_sketch_label), MARGIN, top + 10f, labelPaint)
            c.drawRect(left, top, left + SKETCH_WIDTH, top + SKETCH_HEIGHT, sketchFramePaint)

            val path = Path()
            sketch.outline.forEachIndexed { i, point ->
                val px = left + point.x
                val py = top + point.y
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            c.drawPath(path, sketchFillPaint)
            c.drawPath(path, sketchLinePaint)

            if (sketch.outline.size <= SKETCH_MAX_NUMBERED) {
                sketch.outline.forEachIndexed { i, point ->
                    val px = left + point.x
                    val py = top + point.y
                    c.drawCircle(px, py, 1.8f, sketchCornerPaint)
                    // Offset up and to the right of its corner, the way corners are
                    // numbered on a survey letter.
                    c.drawText("${i + 1}", px + 3f, py - 3f, sketchLabelPaint)
                }
            }

            drawNorthArrow(c, left + SKETCH_WIDTH - 12f, top + 6f)
            drawScaleBar(c, left + 6f, top + SKETCH_HEIGHT - 6f, sketch.metresPerPoint)
            y += SKETCH_HEIGHT + 6f
        }

        /** So the sheet can be turned to face the ground it describes. */
        private fun drawNorthArrow(c: Canvas, x: Float, top: Float) {
            c.drawLine(x, top + 14f, x, top + 2f, sketchLinePaint)
            c.drawLine(x, top + 2f, x - 3f, top + 6f, sketchLinePaint)
            c.drawLine(x, top + 2f, x + 3f, top + 6f, sketchLinePaint)
            val north = strings.array(R.array.cardinal_directions).firstOrNull() ?: "N"
            c.drawText(
                north,
                x - sketchLabelPaint.measureText(north) / 2f,
                top + 22f,
                sketchLabelPaint
            )
        }

        /**
         * What the drawing measures, without which it is only a picture.
         *
         * Aimed at about two-fifths of the box so the bar stays clear of the
         * outline's own width, then rounded down to a length a reader can step
         * along by eye.
         */
        private fun drawScaleBar(c: Canvas, x: Float, baseline: Float, metresPerPoint: Double) {
            val metres = BoundarySketch.niceBarMetres(metresPerPoint * SKETCH_WIDTH * 0.4)
            if (metres <= 0.0) return
            val length = (metres / metresPerPoint).toFloat()
            if (!length.isFinite() || length <= 0f) return
            c.drawLine(x, baseline, x + length, baseline, sketchLinePaint)
            c.drawLine(x, baseline - 3f, x, baseline + 3f, sketchLinePaint)
            c.drawLine(x + length, baseline - 3f, x + length, baseline + 3f, sketchLinePaint)
            val label = strings.get(
                R.string.pdf_sketch_scale,
                if (metres >= 1.0) metres.roundToInt().toString()
                else "%.1f".format(Locale.getDefault(), metres)
            )
            c.drawText(label, x, baseline - 5f, sketchLabelPaint)
        }

        private fun drawPhoto(path: String) {
            val bitmap = decodeScaled(path, 220, 165) ?: return
            ensureSpace(bitmap.height + 12f)
            val c = canvas ?: return
            y += 6f
            c.drawBitmap(bitmap, MARGIN + 110f, y, null)
            y += bitmap.height + 6f
            bitmap.recycle()
        }
    }

    /** Downsamples to keep large camera photos from blowing up the PDF. */
    private fun decodeScaled(path: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth &&
                bounds.outHeight / (sample * 2) >= maxHeight
            ) sample *= 2

            val decoded = BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null

            val ratio = minOf(
                maxWidth.toFloat() / decoded.width,
                maxHeight.toFloat() / decoded.height,
                1f
            )
            if (ratio >= 1f) decoded
            else Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true
            ).also { if (it !== decoded) decoded.recycle() }
        }.getOrNull()
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val lines = mutableListOf<String>()
        text.split("\n").forEach { paragraph ->
            var current = StringBuilder()
            paragraph.split(" ").forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current = StringBuilder(candidate)
                } else {
                    if (current.isNotEmpty()) lines += current.toString()
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) lines += current.toString()
        }
        return lines.ifEmpty { listOf("") }
    }
}
