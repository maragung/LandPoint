package com.landpoint.app.map.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Header inspection, on real files.
 *
 * No Android and no MapLibre: the header is 127 bytes of little-endian numbers, and
 * a test that can write those bytes can prove every refusal without a device. The
 * refusals are the point. An archive that is rejected clearly costs the user one
 * sentence; an archive that is accepted and cannot be drawn costs them a map that is
 * blank in a field with no signal, which is where they will find out.
 */
class PmtilesHeaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * A well-formed v3 header, with whatever is asked for written into it.
     *
     * Offsets are from the specification and are spelled out rather than named,
     * because the whole value of this test is that it does not share a layout
     * constant with the code it checks — a transposed pair would otherwise agree
     * with itself.
     */
    private fun header(
        version: Int = 3,
        tileType: Int = 1,
        minZoom: Int = 0,
        maxZoom: Int = 14,
        tileDataOffset: Long = 200L,
        tileDataLength: Long = 800L,
        rootOffset: Long = 127L,
        rootLength: Long = 73L,
        addressedTiles: Long = 4_096L,
        minLongitudeE7: Int = 1_073_000_000,
        minLatitudeE7: Int = -75_000_000,
        maxLongitudeE7: Int = 1_079_000_000,
        maxLatitudeE7: Int = -60_000_000
    ): ByteArray {
        val raw = ByteArray(PmtilesHeader.HEADER_BYTES)
        "PMTiles".toByteArray(Charsets.US_ASCII).copyInto(raw)
        raw[7] = version.toByte()

        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(8, rootOffset)
        buffer.putLong(16, rootLength)
        buffer.putLong(56, tileDataOffset)
        buffer.putLong(64, tileDataLength)
        buffer.putLong(72, addressedTiles)

        raw[99] = tileType.toByte()
        raw[100] = minZoom.toByte()
        raw[101] = maxZoom.toByte()

        buffer.putInt(102, minLongitudeE7)
        buffer.putInt(106, minLatitudeE7)
        buffer.putInt(110, maxLongitudeE7)
        buffer.putInt(114, maxLatitudeE7)
        return raw
    }

    /** An archive on disk: the header, then [bodyBytes] of anything at all. */
    private fun archive(head: ByteArray = header(), bodyBytes: Int = 1_000): File {
        val file = temp.newFile()
        file.writeBytes(head + ByteArray(bodyBytes))
        return file
    }

    private fun ok(result: PmtilesResult): PmtilesHeader {
        assertTrue("expected Ok, got $result", result is PmtilesResult.Ok)
        return (result as PmtilesResult.Ok).header
    }

    @Test
    fun `a complete vector archive is read`() {
        val head = ok(PmtilesHeader.read(archive()))

        assertEquals(PmtilesHeader.TileType.MVT, head.tileType)
        assertTrue(head.isVector)
        assertEquals(0, head.minZoom)
        assertEquals(14, head.maxZoom)
        assertEquals(4_096L, head.addressedTiles)
    }

    @Test
    fun `coordinates come back as degrees, not as the integers they are stored in`() {
        val head = ok(PmtilesHeader.read(archive()))

        // Java, around Bandung. Stored as degrees times ten million.
        assertEquals(107.3, head.minLongitude, 1e-6)
        assertEquals(-7.5, head.minLatitude, 1e-6)
        assertEquals(107.9, head.maxLongitude, 1e-6)
        assertEquals(-6.0, head.maxLatitude, 1e-6)
    }

    @Test
    fun `a photographic archive is read as one, whatever the file is called`() {
        val head = ok(PmtilesHeader.read(archive(header(tileType = 3))))

        assertEquals(PmtilesHeader.TileType.JPEG, head.tileType)
        // The one fact a file name cannot supply: photographs and geometry need
        // completely different styles, and guessing wrong draws nothing.
        assertFalse(head.isVector)
    }

    @Test
    fun `an interrupted download is refused, and says how much is missing`() {
        // The header still describes the whole archive — that is exactly why it is
        // what proves the rest is absent.
        val head = header(tileDataOffset = 200L, tileDataLength = 100_000L)
        val result = PmtilesHeader.read(archive(head, bodyBytes = 500))

        assertTrue("expected Truncated, got $result", result is PmtilesResult.Truncated)
        val truncated = result as PmtilesResult.Truncated
        assertEquals(100_200L, truncated.expectedBytes)
        assertEquals(PmtilesHeader.HEADER_BYTES + 500L, truncated.actualBytes)
        assertTrue(truncated.expectedBytes > truncated.actualBytes)
    }

    @Test
    fun `a root directory reaching past the end is caught too`() {
        // Tile data fits; the directory that indexes it does not.
        val head = header(rootOffset = 1_000L, rootLength = 50_000L)

        assertTrue(PmtilesHeader.read(archive(head)) is PmtilesResult.Truncated)
    }

    @Test
    fun `something that is not an archive at all is refused`() {
        val notAnArchive = temp.newFile()
        notAnArchive.writeBytes(ByteArray(4_000) { 0x41 })

        assertEquals(PmtilesResult.NotPmtiles, PmtilesHeader.read(notAnArchive))
    }

    @Test
    fun `a file too short to hold a header is refused before it is parsed`() {
        val stub = temp.newFile()
        stub.writeBytes("PMTiles".toByteArray(Charsets.US_ASCII))

        assertEquals(PmtilesResult.NotPmtiles, PmtilesHeader.read(stub))
    }

    @Test
    fun `a version this app cannot read is named rather than guessed at`() {
        val result = PmtilesHeader.read(archive(header(version = 2)))

        // v2 has an incompatible layout, so reading on would produce nonsense that
        // passes the sanity checks by luck.
        assertEquals(PmtilesResult.WrongVersion(2), result)
    }

    @Test
    fun `an archive holding no tiles is refused as empty`() {
        assertEquals(
            PmtilesResult.Empty,
            PmtilesHeader.read(archive(header(tileDataLength = 0L)))
        )
        assertEquals(
            PmtilesResult.Empty,
            PmtilesHeader.read(archive(header(addressedTiles = 0L)))
        )
    }

    @Test
    fun `a tile format the app has no style for is refused`() {
        assertEquals(
            PmtilesResult.UnknownTileType,
            PmtilesHeader.read(archive(header(tileType = 9)))
        )
    }

    @Test
    fun `zooms that cannot be true mean the header was misread`() {
        assertEquals(
            PmtilesResult.NotPmtiles,
            PmtilesHeader.read(archive(header(minZoom = 10, maxZoom = 4)))
        )
        assertEquals(
            PmtilesResult.NotPmtiles,
            PmtilesHeader.read(archive(header(maxZoom = 200)))
        )
    }

    @Test
    fun `a bounding box off the earth means the header was misread`() {
        assertEquals(
            PmtilesResult.NotPmtiles,
            PmtilesHeader.read(archive(header(maxLatitudeE7 = 950_000_000)))
        )
        assertEquals(
            PmtilesResult.NotPmtiles,
            // North below south: the two were written the wrong way round.
            PmtilesHeader.read(archive(header(minLatitudeE7 = -60_000_000, maxLatitudeE7 = -75_000_000)))
        )
    }

    @Test
    fun `a directory that is not a file is unreadable, not empty`() {
        assertEquals(PmtilesResult.Unreadable, PmtilesHeader.read(temp.newFolder()))
    }
}
