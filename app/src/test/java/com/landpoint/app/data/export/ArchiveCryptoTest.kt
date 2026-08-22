package com.landpoint.app.data.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The container format, checked without Android: it is plain `javax.crypto`.
 *
 * What matters here is not that a round trip works — that is the easy half — but
 * that every way of quietly changing the file is refused. A backup that decrypts
 * to *almost* the right bytes is worse than one that refuses to open, because the
 * user finds out only when they need the records back.
 *
 * Iterations are dialled down to keep the suite quick; the reader takes the count
 * from the file, which is exactly why that is safe to do.
 */
class ArchiveCryptoTest {

    private val password = "correct horse".toCharArray()
    private val iterations = 1_000
    private val chunk = 64

    private fun seal(payload: ByteArray, chunkBytes: Int = chunk): ByteArray {
        val sink = ByteArrayOutputStream()
        ArchiveCrypto.encryptingStream(sink, password, iterations, chunkBytes).use {
            it.write(payload)
        }
        return sink.toByteArray()
    }

    private fun open(file: ByteArray, secret: CharArray = password): ByteArray =
        ArchiveCrypto.decryptingStream(ByteArrayInputStream(file), secret)
            .use { it.readBytes() }

    @Test
    fun `a payload spanning several frames comes back byte for byte`() {
        val payload = ByteArray(200) { (it * 7).toByte() }
        assertArrayEquals(payload, open(seal(payload)))
    }

    @Test
    fun `an empty payload comes back empty`() {
        assertEquals(0, open(seal(ByteArray(0))).size)
    }

    @Test
    fun `a payload that exactly fills its frames comes back whole`() {
        // The boundary case: the last frame carries no data at all and exists
        // only to say the archive ended on purpose.
        val payload = ByteArray(chunk * 2) { it.toByte() }
        assertArrayEquals(payload, open(seal(payload)))
    }

    @Test
    fun `reading one byte at a time gives the same bytes`() {
        val payload = ByteArray(150) { (it + 1).toByte() }
        val stream = ArchiveCrypto.decryptingStream(
            ByteArrayInputStream(seal(payload)),
            password
        )
        val out = ByteArrayOutputStream()
        stream.use {
            while (true) {
                val b = it.read()
                if (b < 0) break
                out.write(b)
            }
        }
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun `the archive is marked as encrypted and a zip is not`() {
        assertTrue(ArchiveCrypto.looksEncrypted(seal("x".toByteArray())))
        assertFalse(ArchiveCrypto.looksEncrypted("PKstuff".toByteArray()))
        assertFalse(ArchiveCrypto.looksEncrypted(ByteArray(0)))
        assertFalse(ArchiveCrypto.looksEncrypted("LPB".toByteArray()))
    }

    @Test
    fun `the wrong password is reported as such and not as damage`() {
        val file = seal(ByteArray(200) { it.toByte() })
        val failure = runCatching { open(file, "wrong horse".toCharArray()) }.exceptionOrNull()
        assertTrue(
            "expected a wrong-password refusal, got $failure",
            failure is ArchiveCrypto.WrongPassphraseException
        )
    }

    @Test
    fun `a flipped byte in a later frame is refused`() {
        val file = seal(ByteArray(200) { it.toByte() })
        // Late in the file, so it lands past the first frame: within the first
        // frame a bad tag is indistinguishable from a wrong password, which is a
        // property of AES-GCM rather than of this format.
        val at = file.size - 40
        val tampered = file.copyOf().also { it[at] = (it[at] + 1).toByte() }
        val failure = runCatching { open(tampered) }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is IOException)
        assertFalse(
            "tampering must not be blamed on the password",
            failure is ArchiveCrypto.WrongPassphraseException
        )
    }

    @Test
    fun `an archive with its final frame removed is refused, not truncated silently`() {
        // The whole reason the payload is framed rather than run through one
        // CipherInputStream: that class swallows the bad-tag exception, so this
        // file would have opened and returned a short, plausible-looking archive.
        val payload = ByteArray(chunk * 2) { it.toByte() }
        val file = seal(payload)
        val finalFrame = 12 + 4 + 16 // iv, length, tag of an empty frame
        val failure = runCatching { open(file.copyOf(file.size - finalFrame)) }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is IOException)
        assertFalse(failure is ArchiveCrypto.WrongPassphraseException)
    }

    @Test
    fun `a file cut off mid-frame is refused`() {
        val file = seal(ByteArray(200) { it.toByte() })
        val failure = runCatching { open(file.copyOf(file.size - 1)) }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is IOException)
    }

    @Test
    fun `a file that is not one of ours is refused outright`() {
        val failure = runCatching { open("PKnot a backup".toByteArray()) }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is IOException)
    }

    @Test
    fun `a header claiming an absurd work factor is refused`() {
        val file = seal("x".toByteArray())
        // Iterations sit right after the 6-byte marker and the 16-byte salt.
        val hostile = file.copyOf().also {
            it[22] = 0x7F
            it[23] = 0xFF.toByte()
            it[24] = 0xFF.toByte()
            it[25] = 0xFF.toByte()
        }
        val failure = runCatching { open(hostile) }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is IOException)
    }

    @Test
    fun `the iteration count is read from the file, not assumed`() {
        val payload = "surat ukur".toByteArray()
        val sink = ByteArrayOutputStream()
        ArchiveCrypto.encryptingStream(sink, password, iterations = 2_048, chunkBytes = chunk)
            .use { it.write(payload) }
        assertArrayEquals(payload, open(sink.toByteArray()))
    }
}
