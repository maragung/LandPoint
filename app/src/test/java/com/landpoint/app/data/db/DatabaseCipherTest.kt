package com.landpoint.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The header check decides whether an existing database gets migrated or handed
 * straight to the encrypted opener — and getting it wrong the second way means
 * handing SQLCipher a plaintext file, which fails on the first query rather than
 * on open.
 *
 * Only the two pure functions are covered here. Everything else in [DatabaseCipher]
 * needs the native library, so it is exercised on a device.
 */
class DatabaseCipherTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** The first sixteen bytes of any unencrypted SQLite file. */
    private val header = "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0

    private fun file(name: String, bytes: ByteArray): File =
        folder.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun `a real sqlite file is recognised`() {
        val db = file("plain.db", header + ByteArray(1024))
        assertTrue(DatabaseCipher.looksPlaintext(db))
    }

    @Test
    fun `an encrypted file is not plaintext`() {
        // SQLCipher writes its salt over the header, so the first bytes are random.
        val db = file("cipher.db", ByteArray(1024) { (it * 31 + 7).toByte() })
        assertFalse(DatabaseCipher.looksPlaintext(db))
    }

    @Test
    fun `a file that is not there yet is not plaintext`() {
        // The common case on a fresh install: there is nothing to migrate, and
        // reporting it as plaintext would send the migration down a path with no
        // database at the end of it.
        assertFalse(DatabaseCipher.looksPlaintext(File(folder.root, "absent.db")))
    }

    @Test
    fun `a directory is not plaintext`() {
        assertFalse(DatabaseCipher.looksPlaintext(folder.newFolder("notafile")))
    }

    @Test
    fun `a file too short to hold a header is not plaintext`() {
        // A create interrupted partway through. Reading sixteen bytes out of nine
        // must answer the question, not throw.
        assertFalse(DatabaseCipher.looksPlaintext(file("stub.db", header.copyOf(9))))
    }

    @Test
    fun `an empty file is not plaintext`() {
        assertFalse(DatabaseCipher.looksPlaintext(file("empty.db", ByteArray(0))))
    }

    @Test
    fun `a file whose header is nearly right is not plaintext`() {
        // The NUL that terminates the magic is part of it. Without this the check
        // would accept anything starting with the same fifteen characters.
        val db = file("nearly.db", "SQLite format 3x".toByteArray(Charsets.US_ASCII))
        assertFalse(DatabaseCipher.looksPlaintext(db))
    }

    @Test
    fun `ordinary values pass through the quoter unchanged`() {
        // The passphrase is Base64, whose alphabet has no quote in it. This is the
        // case that always happens, and it must not mangle the key.
        assertEquals("aGVsbG8rL3dvcmxk", DatabaseCipher.sqlLiteral("aGVsbG8rL3dvcmxk"))
    }

    @Test
    fun `a quote is doubled rather than escaped`() {
        // SQLite has no backslash escape; a doubled quote is the only form it reads.
        assertEquals("it''s", DatabaseCipher.sqlLiteral("it's"))
        assertEquals("''''", DatabaseCipher.sqlLiteral("''"))
    }
}
