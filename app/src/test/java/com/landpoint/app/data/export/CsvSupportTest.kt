package com.landpoint.app.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvSupportTest {

    @Test
    fun `round trip preserves every field`() {
        val lands = listOf(
            LandTestFactory.land(
                name = "Kebun Belakang",
                description = "Kelapa & pisang",
                notes = "Line one\nLine two",
                latitude = -6.914744,
                longitude = 107.609810,
                altitude = 723.4,
                accuracy = 3.2f,
                address = "Jl. Merdeka 12, Bandung",
                parcelNumber = "P-0042",
                areaSqm = 2500.0
            ),
            LandTestFactory.land(name = "Sawah", latitude = -6.5, longitude = 107.2)
        )

        val csv = CsvSupport.write(lands)
        val records = CsvSupport.toRecords(CsvSupport.parse(csv))

        assertEquals(2, records.size)

        val first = records[0]
        assertEquals(lands[0].id, first.uuid)
        assertEquals("Kebun Belakang", first.name)
        assertEquals("Kelapa & pisang", first.description)
        assertEquals("Line one\nLine two", first.notes)
        assertEquals(-6.914744, first.latitude, 1e-9)
        assertEquals(107.609810, first.longitude, 1e-9)
        assertEquals(723.4, first.altitude!!, 1e-9)
        assertEquals(3.2f, first.accuracy!!, 0.01f)
        assertEquals("Jl. Merdeka 12, Bandung", first.address)
        assertEquals("P-0042", first.parcelNumber)
        assertEquals(2500.0, first.areaSqm!!, 1e-9)

        assertEquals("Sawah", records[1].name)
    }

    @Test
    fun `quoted fields survive commas quotes and newlines`() {
        val csv = CsvSupport.write(
            listOf(
                LandTestFactory.land(
                    name = "Kebun, Belakang",
                    description = "He said \"halo\""
                )
            )
        )

        assertTrue(csv.contains("\"Kebun, Belakang\""))
        assertTrue(csv.contains("\"He said \"\"halo\"\"\""))

        val parsed = CsvSupport.parse(csv)
        assertEquals("Kebun, Belakang", parsed[1][1])
        assertEquals("He said \"halo\"", parsed[1][2])
    }

    @Test
    fun `parse handles CRLF line endings`() {
        val rows = CsvSupport.parse("a,b\r\nc,d\r\n")
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b"), rows[0])
        assertEquals(listOf("c", "d"), rows[1])
    }

    @Test
    fun `parse skips blank lines`() {
        val rows = CsvSupport.parse("a,b\n\n\nc,d\n")
        assertEquals(2, rows.size)
    }

    @Test
    fun `toRecords tolerates alternative column names`() {
        val csv = "lat,lng,title\n" +
                "-6.91,107.61,Warung"
        val records = CsvSupport.toRecords(CsvSupport.parse(csv))
        assertEquals(1, records.size)
        assertEquals("Warung", records[0].name)
        assertEquals(-6.91, records[0].latitude, 1e-9)
        assertEquals(107.61, records[0].longitude, 1e-9)
    }

    @Test
    fun `toRecords ignores rows without coordinates`() {
        val csv = "name,latitude,longitude\n" +
                "Valid,-6.9,107.6\n" +
                "No coords,,\n" +
                "Bad lat,abc,107.6\n" +
                "Out of range,95,107.6"
        val records = CsvSupport.toRecords(CsvSupport.parse(csv))
        assertEquals(1, records.size)
        assertEquals("Valid", records[0].name)
    }

    @Test
    fun `toRecords returns empty when header has no coordinate columns`() {
        val csv = "foo,bar\n1,2\n3,4"
        assertTrue(CsvSupport.toRecords(CsvSupport.parse(csv)).isEmpty())
    }

    @Test
    fun `missing optional columns become null`() {
        val csv = "latitude,longitude\n-6.9,107.6"
        val record = CsvSupport.toRecords(CsvSupport.parse(csv)).single()
        assertEquals(null, record.address)
        assertEquals(null, record.altitude)
        assertEquals(null, record.uuid)
        assertEquals("Unnamed", record.name)
    }

    // ---------------- locale tolerance ----------------

    /**
     * The case that silently lost whole files: a spreadsheet set to Indonesian
     * writes decimal commas, and separates columns with semicolons because the
     * comma is taken.
     */
    @Test
    fun `a semicolon file with decimal commas imports`() {
        val csv = "name;latitude;longitude;area_sqm\n" +
                "Kebun;-6,914744;107,609810;2500,5"
        val record = CsvSupport.toRecords(CsvSupport.parse(csv)).single()

        assertEquals("Kebun", record.name)
        assertEquals(-6.914744, record.latitude, 1e-9)
        assertEquals(107.609810, record.longitude, 1e-9)
        assertEquals(2500.5, record.areaSqm!!, 1e-9)
    }

    @Test
    fun `a tab separated file imports`() {
        val csv = "name\tlatitude\tlongitude\nSawah\t-6.5\t107.2"
        val record = CsvSupport.toRecords(CsvSupport.parse(csv)).single()

        assertEquals("Sawah", record.name)
        assertEquals(-6.5, record.latitude, 1e-9)
    }

    /** A comma-decimal value survives a comma-delimited file when quoted. */
    @Test
    fun `quoted decimal commas import from a comma delimited file`() {
        val csv = "name,latitude,longitude\n" + "Kebun,\"-6,9\",\"107,6\""
        val record = CsvSupport.toRecords(CsvSupport.parse(csv)).single()

        assertEquals(-6.9, record.latitude, 1e-9)
        assertEquals(107.6, record.longitude, 1e-9)
    }

    /** The separator that appears last is the decimal mark, in either order. */
    @Test
    fun `grouped thousands parse in both conventions`() {
        assertEquals(1234.56, CsvSupport.parseDecimal("1.234,56")!!, 1e-9)
        assertEquals(1234.56, CsvSupport.parseDecimal("1,234.56")!!, 1e-9)
        assertEquals(-6.9, CsvSupport.parseDecimal("-6.9")!!, 1e-9)
        assertEquals(null, CsvSupport.parseDecimal("abc"))
        assertEquals(null, CsvSupport.parseDecimal(""))
        assertEquals(null, CsvSupport.parseDecimal(null))
    }

    @Test
    fun `an all comma header still reads as comma separated`() {
        assertEquals(',', CsvSupport.sniffDelimiter("a,b,c\n1,2,3"))
        assertEquals(';', CsvSupport.sniffDelimiter("a;b;c\n1;2;3"))
        // A quoted separator must not win the count.
        assertEquals(',', CsvSupport.sniffDelimiter("\"a;b;c;d\",x\n1,2"))
    }

    // ---------------- dropped rows are reported ----------------

    /**
     * A row that carries a coordinate we cannot read is data loss, so it has to
     * be counted. Previously it vanished and the import reported success.
     */
    @Test
    fun `read counts rows it could not understand`() {
        val csv = "name,latitude,longitude\n" +
                "Valid,-6.9,107.6\n" +
                "Bad lat,abc,107.6\n" +
                "Out of range,95,107.6"
        val result = CsvSupport.read(CsvSupport.parse(csv))

        assertEquals(1, result.records.size)
        assertEquals(2, result.unreadableRows)
    }

    /** Trailing blank lines are formatting, not lost records. */
    @Test
    fun `read does not count trailing blank rows as unreadable`() {
        val csv = "name,latitude,longitude\nValid,-6.9,107.6\n,,\n"
        val result = CsvSupport.read(CsvSupport.parse(csv))

        assertEquals(1, result.records.size)
        assertEquals(0, result.unreadableRows)
    }
}
