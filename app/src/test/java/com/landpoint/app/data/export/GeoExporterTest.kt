package com.landpoint.app.data.export

import com.landpoint.app.data.model.GeometryCodec
import com.landpoint.app.data.model.GeometryType
import com.landpoint.app.data.model.Land
import com.landpoint.app.util.GeoPoint
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GeoExporterTest {

    private val disclaimer = "Not a legal survey document."

    private val ring = listOf(
        GeoPoint(-6.914744, 107.609810),
        GeoPoint(-6.915744, 107.609810),
        GeoPoint(-6.915744, 107.610810)
    )

    private fun land(
        name: String = "Kebun Utara",
        polygon: Boolean = false
    ) = Land(
        id = "id-1",
        name = name,
        description = "Sawah",
        notes = "",
        latitude = -6.914744,
        longitude = 107.609810,
        altitude = 768.0,
        accuracy = 4.2f,
        address = "Bandung",
        parcelNumber = "12.34",
        areaSqm = if (polygon) 1234.0 else null,
        geometryType = if (polygon) GeometryType.POLYGON else GeometryType.POINT,
        geometryJson = if (polygon) GeometryCodec.encode(ring) else null,
        createdAt = 1_754_700_000_000L,
        updatedAt = 1_754_700_000_000L,
        photos = emptyList()
    )

    @After
    fun tearDown() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun `gpx writes a waypoint with dot decimals`() {
        val gpx = GeoExporter.toGpx(listOf(land()), disclaimer)

        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpx.contains("lat=\"-6.914744\""))
        assertTrue(gpx.contains("lon=\"107.60981\""))
        assertTrue(gpx.contains("<ele>768.0</ele>"))
        assertTrue(gpx.contains("</gpx>"))
    }

    /**
     * The regression this file exists for: under an Indonesian locale a
     * format-based implementation writes "-6,914744" and every GPS tool rejects
     * the file. Nothing in the output may contain a decimal comma.
     */
    @Test
    fun `indonesian locale does not produce comma decimals`() {
        Locale.setDefault(Locale.forLanguageTag("in-ID"))

        val gpx = GeoExporter.toGpx(listOf(land(polygon = true)), disclaimer)
        val kml = GeoExporter.toKml(listOf(land(polygon = true)), disclaimer)

        assertFalse(gpx.contains("-6,9"))
        assertFalse(kml.contains("-6,9"))
        assertTrue(gpx.contains("-6.914744"))
        assertTrue(kml.contains("-6.914744"))
    }

    @Test
    fun `polygon becomes a closed gpx track and a closed kml ring`() {
        val gpx = GeoExporter.toGpx(listOf(land(polygon = true)), disclaimer)
        val kml = GeoExporter.toKml(listOf(land(polygon = true)), disclaimer)

        // 3 corners plus the repeated first point closes the ring.
        assertTrue(gpx.contains("<trkseg>"))
        assertTrue(Regex("<trkpt ").findAll(gpx).count() == 4)
        assertTrue(kml.contains("<LinearRing>"))
        assertTrue(Regex("107\\.60981,-6\\.914744").findAll(kml).count() >= 2)
    }

    @Test
    fun `point only land has no track or polygon`() {
        val gpx = GeoExporter.toGpx(listOf(land()), disclaimer)
        val kml = GeoExporter.toKml(listOf(land()), disclaimer)

        assertFalse(gpx.contains("<trk>"))
        assertFalse(kml.contains("<Polygon>"))
    }

    @Test
    fun `kml uses lon lat order and keeps the disclaimer`() {
        val kml = GeoExporter.toKml(listOf(land()), disclaimer)

        assertTrue(kml.contains("<coordinates>107.60981,-6.914744,768.0</coordinates>"))
        assertTrue(kml.contains(disclaimer))
        assertTrue(kml.contains("</kml>"))
    }

    @Test
    fun `names with xml characters are escaped`() {
        val gpx = GeoExporter.toGpx(listOf(land(name = "Tanah <A> & \"B\"")), disclaimer)

        assertTrue(gpx.contains("Tanah &lt;A&gt; &amp; &quot;B&quot;"))
        assertFalse(gpx.contains("<A>"))
    }
}
