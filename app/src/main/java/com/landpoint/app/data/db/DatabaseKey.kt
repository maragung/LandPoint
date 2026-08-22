package com.landpoint.app.data.db

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What came of asking this phone for the database passphrase.
 *
 * [Lost] and [Unavailable] are deliberately different answers. Unavailable means
 * this phone could not give us a key at all and the database has to carry on
 * unencrypted; Lost means a passphrase *was* stored here once and can no longer
 * be unwrapped — which means there is an encrypted file on disk that nothing will
 * ever open again, and it must be set aside rather than written over.
 */
sealed interface DatabaseKeyResult {
    data class Available(val passphrase: String) : DatabaseKeyResult
    data object Lost : DatabaseKeyResult
    data object Unavailable : DatabaseKeyResult
}

/**
 * The passphrase the land database is encrypted with, held by the phone itself.
 *
 * Not a password the user types. A password is the obvious design and the wrong
 * one here: the people this app is for keep records of land they own, sometimes
 * the only record there is, and a forgotten password would destroy exactly the
 * thing the app exists to protect. That is a worse and far likelier loss than the
 * theft encryption defends against. So the passphrase is random, this app never
 * shows it to anybody, and it is sealed with a key that lives in the phone's
 * secure hardware — where this process can use it but cannot read it, and where
 * extracting the storage chip yields nothing but ciphertext. The screen lock the
 * user already understands is what stands in front of it.
 *
 * The cost is real and is documented for the user in Settings and in the README:
 * a hardware key cannot leave the phone it was made on, so the encrypted file
 * cannot either. Moving to a new phone means restoring a backup archive, which is
 * plain JSON and photos and travels anywhere.
 */
internal object DatabaseKey {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "landpoint.database"
    private const val PREFS = "landpoint.database.key"
    private const val WRAPPED = "passphrase"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val PASSPHRASE_BYTES = 32
    private const val SEPARATOR = ":"

    /** The passphrase for this install, creating one on first use. */
    fun passphrase(context: Context): DatabaseKeyResult {
        val prefs = prefs(context)
        val stored = prefs.getString(WRAPPED, null)
        return if (stored == null) create(prefs) else unwrap(stored)
    }

    /**
     * Forgets the key and the passphrase it sealed.
     *
     * Only called once whatever they protected has been set aside, never while a
     * database they can still open exists.
     */
    fun discard(context: Context) {
        prefs(context).edit().remove(WRAPPED).commit()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun prefs(context: Context): SharedPreferences =
        // SharedPreferences rather than the DataStore the rest of the app settled
        // on: this is read while the database is being opened, from a plain
        // function with no coroutine to collect a Flow in. What it holds is a
        // sealed blob that is useless without the hardware key anyway.
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun create(prefs: SharedPreferences): DatabaseKeyResult = try {
        val random = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        // Base64 rather than the raw bytes: these same characters are spliced into
        // a SQL string literal by the migration in DatabaseCipher, and the Base64
        // alphabet has no quote in it to escape. The entropy is the 32 bytes
        // either way.
        val passphrase = Base64.encodeToString(random, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey())
        }
        val sealed = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
        val record = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            SEPARATOR +
            Base64.encodeToString(sealed, Base64.NO_WRAP)

        // commit, not apply: a passphrase that never reached disk is a database
        // that cannot be opened again, and the next line starts encrypting with it.
        if (prefs.edit().putString(WRAPPED, record).commit()) {
            DatabaseKeyResult.Available(passphrase)
        } else {
            DatabaseKeyResult.Unavailable
        }
    } catch (e: Exception) {
        // Broad on purpose. Keystore implementations vary by vendor and throw
        // both checked security exceptions and ProviderException subclasses; this
        // one path decides whether the app starts at all, and the answer to a
        // phone that cannot make a key is to carry on without one.
        DatabaseKeyResult.Unavailable
    }

    private fun unwrap(record: String): DatabaseKeyResult {
        val parts = record.split(SEPARATOR)
        if (parts.size != 2) return DatabaseKeyResult.Lost
        return try {
            val key = keyStore().getKey(ALIAS, null) as? SecretKey
                ?: return DatabaseKeyResult.Lost
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP))
                )
            }
            val plain = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
            DatabaseKeyResult.Available(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            // A cleared Keystore, a factory reset restore, the file carried to
            // another phone: whatever the cause, this passphrase is gone. Saying
            // so is what stops the caller overwriting a database its owner may
            // still be able to rebuild from an archive.
            DatabaseKeyResult.Lost
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun wrappingKey(): SecretKey =
        (keyStore().getKey(ALIAS, null) as? SecretKey) ?: generate()

    private fun generate(): SecretKey = try {
        generate(strongBox = true)
    } catch (e: ProviderException) {
        // StrongBoxUnavailableException on most phones — a separate security chip
        // is a bonus, not a requirement, and a TEE-backed key is still one this
        // process cannot read. Clear the alias first: a half-created entry would
        // fail the retry too.
        runCatching { keyStore().deleteEntry(ALIAS) }
        generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // Deliberately not setUserAuthenticationRequired or
                    // setUnlockedDeviceRequired. Either would make the key
                    // unusable while the screen is locked, which is when a
                    // location fix or an export could still be finishing — and
                    // would put a second lock in front of an app that already
                    // offers one it can explain. The threat this addresses is a
                    // phone in somebody else's hands, which the screen lock and
                    // the app lock answer directly.
                    .setIsStrongBoxBacked(strongBox)
                    .build()
            )
        }.generateKey()
}
