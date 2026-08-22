package com.landpoint.app.data.export

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password protection for the backup archive.
 *
 * A backup is the one copy of someone's land records that leaves the phone — it
 * lands in Downloads, on an SD card, in a chat message to themselves. This wraps
 * that file so a password is needed to read it.
 *
 * The container:
 * ```
 * "LPBK01"                     6 bytes, also the marker that says "encrypted"
 * salt                        16 bytes
 * iterations                   4 bytes, big-endian
 * chunk size                   4 bytes, big-endian
 * frames, until the final one:
 *     iv                      12 bytes
 *     length                   4 bytes, big-endian: ciphertext including tag
 *     ciphertext + tag       length bytes
 * ```
 *
 * AES-256-GCM, key from PBKDF2-HMAC-SHA256. Both the iteration count and the
 * chunk size are stored rather than assumed, so raising either in a later version
 * still reads every archive written by this one.
 *
 * The payload is split into frames rather than encrypted as one stream because
 * `CipherInputStream` swallows the exception GCM raises for a bad tag: tampering
 * would surface as a silently truncated archive instead of a refusal. Each frame
 * is verified with an explicit `doFinal`, the frame's index and its final-frame
 * flag are authenticated as associated data, and the last frame is always written
 * even when empty — so dropping, reordering or duplicating frames is caught, and
 * so is a file that simply stops early.
 */
object ArchiveCrypto {

    /** Bytes to read before deciding whether a file is one of ours. */
    const val PROBE_BYTES = 6

    private val MAGIC = byteArrayOf(0x4C, 0x50, 0x42, 0x4B, 0x30, 0x31) // "LPBK01"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** OWASP's 2023 floor for PBKDF2-HMAC-SHA256. Under a second on a slow phone. */
    const val ITERATIONS = 210_000

    /** 1 MiB of plaintext per frame: 64 frames for a 64 MB archive of photos. */
    const val CHUNK_BYTES = 1 shl 20

    /** The password did not open the archive. Distinct from a damaged file. */
    class WrongPassphraseException : IOException("Wrong password for this backup")

    /** Does this file start with our marker? */
    fun looksEncrypted(probe: ByteArray): Boolean =
        probe.size >= MAGIC.size &&
            MAGIC.indices.all { probe[it] == MAGIC[it] }

    /**
     * Wraps [sink] so everything written to the returned stream is encrypted.
     * Closing it writes the final frame — the archive is incomplete until then.
     */
    fun encryptingStream(
        sink: OutputStream,
        passphrase: CharArray,
        iterations: Int = ITERATIONS,
        chunkBytes: Int = CHUNK_BYTES
    ): OutputStream {
        require(chunkBytes in 1..MAX_CHUNK_BYTES) { "Unreasonable chunk size" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)

        sink.write(MAGIC)
        sink.write(salt)
        sink.writeIntBE(iterations)
        sink.writeIntBE(chunkBytes)

        return FrameWriter(sink, deriveKey(passphrase, salt, iterations), chunkBytes, random)
    }

    /**
     * Wraps [source], which must be positioned at the start of the file, and
     * returns the plaintext. Throws [WrongPassphraseException] on the first frame
     * when the password is wrong, and [IOException] when the file is damaged,
     * altered, or cut short.
     */
    fun decryptingStream(source: InputStream, passphrase: CharArray): InputStream {
        val magic = source.readExactly(MAGIC.size) ?: throw IOException("Not a LandPoint backup")
        if (!looksEncrypted(magic)) throw IOException("Not an encrypted LandPoint backup")

        val salt = source.readExactly(SALT_BYTES) ?: throw IOException("Backup header is truncated")
        val iterations = source.readIntBE() ?: throw IOException("Backup header is truncated")
        val chunkBytes = source.readIntBE() ?: throw IOException("Backup header is truncated")
        // A hostile file must not be able to name a work factor that hangs the
        // phone, nor a frame size that exhausts its memory.
        if (iterations !in 1..MAX_ITERATIONS) throw IOException("Unsupported backup header")
        if (chunkBytes !in 1..MAX_CHUNK_BYTES) throw IOException("Unsupported backup header")

        return FrameReader(source, deriveKey(passphrase, salt, iterations), chunkBytes)
    }

    private const val MAX_ITERATIONS = 10_000_000
    private const val MAX_CHUNK_BYTES = 1 shl 26 // 64 MiB

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            try {
                SecretKeySpec(bytes, "AES")
            } finally {
                // SecretKeySpec copies, so this array is ours to wipe.
                Arrays.fill(bytes, 0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    /** Associated data: ties every frame to its position and to this format. */
    private fun aad(index: Long, isFinal: Boolean): ByteArray {
        val out = ByteArray(MAGIC.size + 9)
        MAGIC.copyInto(out)
        for (i in 0 until 8) {
            out[MAGIC.size + i] = (index ushr (56 - 8 * i)).toByte()
        }
        out[MAGIC.size + 8] = if (isFinal) 1 else 0
        return out
    }

    private class FrameWriter(
        private val sink: OutputStream,
        private val key: SecretKey,
        chunkBytes: Int,
        private val random: SecureRandom
    ) : OutputStream() {

        private val buffer = ByteArray(chunkBytes)
        private var filled = 0
        private var index = 0L
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "Stream is closed" }
            var done = 0
            while (done < len) {
                val take = minOf(buffer.size - filled, len - done)
                System.arraycopy(b, off + done, buffer, filled, take)
                filled += take
                done += take
                if (filled == buffer.size) writeFrame(isFinal = false)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            // Always written, even empty: it is what tells the reader the archive
            // ended on purpose rather than being cut off.
            writeFrame(isFinal = true)
            sink.flush()
            sink.close()
        }

        private fun writeFrame(isFinal: Boolean) {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                updateAAD(aad(index, isFinal))
            }
            val payload = cipher.doFinal(buffer, 0, filled)
            sink.write(iv)
            sink.writeIntBE(payload.size)
            sink.write(payload)
            filled = 0
            index++
        }
    }

    private class FrameReader(
        private val source: InputStream,
        private val key: SecretKey,
        private val chunkBytes: Int
    ) : InputStream() {

        private var plain = ByteArray(0)
        private var offset = 0
        private var index = 0L
        private var sawFinal = false

        override fun read(): Int {
            if (!advance()) return -1
            return plain[offset++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!advance()) return -1
            val take = minOf(len, plain.size - offset)
            System.arraycopy(plain, offset, b, off, take)
            offset += take
            return take
        }

        override fun close() = source.close()

        /** True while there are plaintext bytes to hand out. */
        private fun advance(): Boolean {
            while (offset >= plain.size) {
                if (sawFinal) return false
                readFrame()
            }
            return true
        }

        private fun readFrame() {
            val iv = source.readExactly(IV_BYTES)
                ?: throw IOException("Backup ends before its last block")
            val length = source.readIntBE()
                ?: throw IOException("Backup ends before its last block")
            // Tag included, so a frame is longer than its plaintext but not by much.
            if (length !in 1..(chunkBytes + TAG_BITS / 8)) throw IOException("Backup is damaged")
            val payload = source.readExactly(length)
                ?: throw IOException("Backup ends before its last block")

            // Which frame this is, and whether it claims to be the last, are both
            // authenticated — so the flag cannot be flipped to hide a truncation.
            plain = decrypt(iv, payload, isFinal = false)
                ?: decrypt(iv, payload, isFinal = true)?.also { sawFinal = true }
                ?: throw if (index == 0L) WrongPassphraseException()
                else IOException("Backup is damaged or was altered")
            offset = 0
            index++
        }

        private fun decrypt(iv: ByteArray, payload: ByteArray, isFinal: Boolean): ByteArray? =
            try {
                Cipher.getInstance(TRANSFORMATION).run {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                    updateAAD(aad(index, isFinal))
                    doFinal(payload)
                }
            } catch (_: BadPaddingException) {
                // AEADBadTagException on most providers, but the superclass is what
                // the JCE contract promises for a tag that does not match.
                null
            }
    }
}

private fun OutputStream.writeIntBE(value: Int) {
    write(byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    ))
}

private fun InputStream.readIntBE(): Int? {
    val bytes = readExactly(4) ?: return null
    return (bytes[0].toInt() and 0xFF shl 24) or
        (bytes[1].toInt() and 0xFF shl 16) or
        (bytes[2].toInt() and 0xFF shl 8) or
        (bytes[3].toInt() and 0xFF)
}

/** Null on a short read: every field in this format has a known length. */
private fun InputStream.readExactly(count: Int): ByteArray? {
    val out = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(out, read, count - read)
        if (n < 0) return null
        read += n
    }
    return out
}
