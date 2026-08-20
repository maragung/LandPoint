package com.landpoint.app.data.export

import com.landpoint.app.data.model.Land
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds GPX 1.1 and KML 2.2 documents from stored lands.
 *
 * Pure string building with no Android dependency, so the output format is unit
 * testable — which matters more here than elsewhere, because these two files are
 * read by other people's software and a malformed tag is silent until someone
 * else's importer rejects it.
 *
 * Coordinates go through [num], never `String.format`: the app ships an
 * Indonesian locale, and a comma decimal separator would produce a file that
 * every GPS tool refuses.
 */
object GeoExporter {

    /** Both formats want ISO 8601 in UTC regardless of where the device is. */
    private fun isoUtc(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(millis))
    }

    /** Locale-independent by construction — [Double.toString] always uses a dot. */
    private fun num(value: Double): String = value.toString()

    private fun esc(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    /** The description block shared by both formats, disclaimer included. */
    private fun describe(land: Land, disclaimer: String): String {
        val parts = buildList {
            land.description.takeIf { it.isNotBlank() }?.let { add(it) }
            land.notes.takeIf { it.isNotBlank() }?.let { add(it) }
            land.parcelNumber?.takeIf { it.isNotBlank() }?.let { add("No. $it") }
            land.address?.takeIf { it.isNotBlank() }?.let { add(it) }
            land.accuracy?.let { add("±${it.toInt()} m") }
            land.areaSqm?.let { add("${it.toLong()} m²") }
            add(disclaimer)
        }
        return parts.joinToString(" — ")
    }

    fun toGpx(lands: List<Land>, disclaimer: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"LandPoint\" ")
        append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <metadata>\n")
        append("    <name>LandPoint</name>\n")
        append("    <desc>").append(esc(disclaimer)).append("</desc>\n")
        append("  </metadata>\n")

        lands.forEach { land ->
            append("  <wpt lat=\"").append(num(land.latitude))
            append("\" lon=\"").append(num(land.longitude)).append("\">\n")
            land.altitude?.let { append("    <ele>").append(num(it)).append("</ele>\n") }
            append("    <time>").append(isoUtc(land.createdAt)).append("</time>\n")
            append("    <name>").append(esc(land.name)).append("</name>\n")
            append("    <desc>").append(esc(describe(land, disclaimer))).append("</desc>\n")
            append("  </wpt>\n")
        }

        // GPX has no polygon type, so a boundary is written as a closed track:
        // the ring is what a reader needs, and every GPX tool understands trk.
        lands.filter { it.isPolygon }.forEach { land ->
            val ring = land.boundary
            if (ring.size < 3) return@forEach
            append("  <trk>\n")
            append("    <name>").append(esc(land.name)).append("</name>\n")
            append("    <trkseg>\n")
            (ring + ring.first()).forEach { point ->
                append("      <trkpt lat=\"").append(num(point.latitude))
                append("\" lon=\"").append(num(point.longitude)).append("\" />\n")
            }
            append("    </trkseg>\n")
            append("  </trk>\n")
        }

        append("</gpx>\n")
    }

    fun toKml(lands: List<Land>, disclaimer: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        append("  <Document>\n")
        append("    <name>LandPoint</name>\n")
        append("    <description>").append(esc(disclaimer)).append("</description>\n")
        append("    <Style id=\"landpoint-boundary\">\n")
        append("      <LineStyle><color>ff2b8c2b</color><width>3</width></LineStyle>\n")
        append("      <PolyStyle><color>402b8c2b</color></PolyStyle>\n")
        append("    </Style>\n")

        lands.forEach { land ->
            append("    <Placemark>\n")
            append("      <name>").append(esc(land.name)).append("</name>\n")
            append("      <description>")
            append(esc(describe(land, disclaimer)))
            append("</description>\n")
            append("      <TimeStamp><when>")
            append(isoUtc(land.createdAt))
            append("</when></TimeStamp>\n")
            append("      <Point><coordinates>")
            // KML writes lon,lat[,alt] — the opposite order to the rest of the app.
            append(num(land.longitude)).append(',').append(num(land.latitude))
            land.altitude?.let { append(',').append(num(it)) }
            append("</coordinates></Point>\n")
            append("    </Placemark>\n")

            val ring = land.boundary
            if (land.isPolygon && ring.size >= 3) {
                append("    <Placemark>\n")
                append("      <name>").append(esc(land.name)).append("</name>\n")
                append("      <styleUrl>#landpoint-boundary</styleUrl>\n")
                append("      <Polygon><outerBoundaryIs><LinearRing><coordinates>\n")
                // A LinearRing must repeat its first point as the last one.
                (ring + ring.first()).forEach { point ->
                    append("        ")
                    append(num(point.longitude)).append(',').append(num(point.latitude))
                    append('\n')
                }
                append("      </coordinates></LinearRing></outerBoundaryIs></Polygon>\n")
                append("    </Placemark>\n")
            }
        }

        append("  </Document>\n")
        append("</kml>\n")
    }
}
