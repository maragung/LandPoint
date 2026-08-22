package com.landpoint.app.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import net.zetetic.database.DatabaseUtils
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileInputStream

/**
 * How the land database is stored on this phone, as far as the app can tell.
 *
 * Reported to the user rather than assumed: a claim that records are encrypted is
 * worth nothing if the app quietly failed to do it.
 *
 * @param parked file name of a database that could no longer be decrypted and was
 *   moved out of the way. Non-null means somebody has lost data and needs to be
 *   told where it went and what to do about it.
 */
data class DatabaseStorage(
    val encrypted: Boolean = false,
    val parked: String? = null
)

/**
 * Encrypts the land database at rest, and gets an existing plaintext one across
 * without losing it.
 *
 * The whole of this file exists to make one guarantee: **no path through it
 * destroys records.** Encryption is worth having, but it is worth less than the
 * data it protects, so every step keeps the old file until the new one has been
 * opened and counted, and a phone that cannot manage encryption at all carries on
 * unencrypted rather than refusing to start.
 *
 * @see DatabaseKey for why the passphrase is the phone's and not the user's.
 */
internal object DatabaseCipher {

    /** Appended while a freshly encrypted copy is being built. A copy — disposable. */
    private const val STAGED = ".staged"

    /** Appended to the plaintext original during the final swap. */
    private const val ASIDE = ".plaintext"

    /** Appended to a database whose key is gone. Never deleted by this app. */
    private const val PARKED = ".unreadable"

    /**
     * The sixteen bytes every SQLite file begins with — fifteen of text and the
     * NUL that terminates them. An encrypted database begins with noise instead.
     */
    private val SQLITE_MAGIC =
        ("SQLite format 3".toByteArray(Charsets.US_ASCII) + 0)

    private val state = MutableStateFlow(DatabaseStorage())

    /**
     * Observable because it is not settled when Settings first asks. The opener is
     * chosen while Room builds, but whether the migration actually succeeded is not
     * known until the first query runs it — so Settings watches rather than reads,
     * and corrects itself if the answer turns out to be no.
     */
    val storage: StateFlow<DatabaseStorage> = state.asStateFlow()

    /**
     * The opener Room should use, or null to leave Room on its own unencrypted one.
     *
     * Null is a real answer, not an unhandled case: a phone whose ABI has no
     * SQLCipher build, or whose Keystore will not produce a key, still has land
     * records worth opening.
     */
    fun factoryFor(context: Context, name: String): SupportSQLiteOpenHelper.Factory? {
        val app = context.applicationContext
        if (!nativeLibraryLoaded()) {
            state.update { it.copy(encrypted = false) }
            return null
        }

        val passphrase = when (val result = DatabaseKey.passphrase(app)) {
            is DatabaseKeyResult.Available -> result.passphrase
            DatabaseKeyResult.Lost -> replaceLostKey(app, name)
            DatabaseKeyResult.Unavailable -> null
        }
        if (passphrase == null) {
            state.update { it.copy(encrypted = false) }
            return null
        }

        state.update { it.copy(encrypted = true) }
        return DeferredFactory(
            encrypted = SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8)),
            plain = FrameworkSQLiteOpenHelperFactory(),
            prepare = { prepare(app, name, passphrase) }
        )
    }

    /**
     * Whether [file] is an ordinary, readable SQLite database.
     *
     * Reads sixteen bytes off the front rather than trying to open it: opening a
     * database to find out what it is costs a connection pool and an exception on
     * the failure path, and the answer is in the header. A missing file is not
     * plaintext — there is nothing there to migrate.
     */
    fun looksPlaintext(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_MAGIC.size) return false
        val head = ByteArray(SQLITE_MAGIC.size)
        // The one step in the whole decision that can throw. A file we cannot read
        // is a file we must not migrate, and this is the only caller's only source
        // of exceptions — so it answers no rather than propagating into Room's open.
        val read = runCatching {
            FileInputStream(file).use { input ->
                var got = 0
                while (got < head.size) {
                    val n = input.read(head, got, head.size - got)
                    if (n < 0) break
                    got += n
                }
                got
            }
        }.getOrDefault(0)
        return read == head.size && head.contentEquals(SQLITE_MAGIC)
    }

    /**
     * Quotes a value for a SQL string literal.
     *
     * The passphrase and the staging path go into `ATTACH ... KEY '...'`, which
     * takes no bind parameters. Base64 contains no quote and an app-private path
     * will not either, so this doubles nothing in practice — it is here so that
     * stays true of code written later.
     */
    fun sqlLiteral(value: String): String = value.replace("'", "''")

    @Volatile
    private var nativeLibrary: Boolean? = null

    /**
     * Loads SQLCipher's native library, once, and says whether it is there.
     *
     * `sqlcipher-android` does not load itself. Doing it here rather than in
     * Application.onCreate keeps it off the startup path of every unit test — and
     * keeps a phone with an unexpected ABI, where this throws UnsatisfiedLinkError,
     * from being unable to open its own records.
     */
    private fun nativeLibraryLoaded(): Boolean = nativeLibrary ?: synchronized(this) {
        nativeLibrary ?: runCatching {
            System.loadLibrary("sqlcipher")
            true
        }.getOrDefault(false).also { nativeLibrary = it }
    }

    /**
     * Deals with a passphrase that can no longer be unwrapped, and returns a fresh
     * one to carry on with.
     *
     * An encrypted database whose key is gone is unreadable by anything, forever.
     * It is still somebody's records, so it is moved aside under a name they can be
     * told, never deleted and never written over.
     */
    private fun replaceLostKey(context: Context, name: String): String? {
        val db = context.getDatabasePath(name)
        if (db.exists() && !looksPlaintext(db)) {
            park(db)
        }
        DatabaseKey.discard(context)
        return (DatabaseKey.passphrase(context) as? DatabaseKeyResult.Available)?.passphrase
    }

    /** Moves a database, and the write-ahead log holding its newest rows, aside. */
    private fun park(db: File) {
        val dir = db.parentFile ?: return
        var parked = File(dir, db.name + PARKED)
        var n = 2
        // Never overwrite an earlier one. Two lost keys means two files somebody
        // may yet find a way to open, not one.
        while (parked.exists()) {
            parked = File(dir, "${db.name}$PARKED.$n")
            n++
        }
        if (!db.renameTo(parked)) return
        File(dir, "${db.name}-wal").renameTo(File(dir, "${parked.name}-wal"))
        File(dir, "${db.name}-shm").renameTo(File(dir, "${parked.name}-shm"))
        state.update { it.copy(parked = parked.name) }
    }

    /**
     * Gets the file into a state the encrypted opener can read, and says whether it
     * managed it.
     *
     * Runs on whichever thread first asks Room for the database — never the main
     * one, because Room will not allow a query there — which is the entire reason
     * for [DeferredFactory]. Re-encrypting a walked boundary set of hundreds of
     * points is not work to do during a screen transition.
     */
    private fun prepare(context: Context, name: String, passphrase: String): Boolean {
        val db = context.getDatabasePath(name)
        val dir = db.parentFile ?: return true
        val staged = File(dir, name + STAGED)
        val aside = File(dir, name + ASIDE)

        // A process that died mid-swap left the original here under another name.
        if (!db.exists() && aside.exists()) aside.renameTo(db)
        // An abandoned copy. The original is intact either way, so this can go.
        if (staged.exists()) staged.delete()

        // Nothing there yet: Room is about to create it, encrypted from the start.
        if (!db.exists()) return true
        if (!looksPlaintext(db)) return true

        return runCatching { encrypt(db, staged, passphrase) }.fold(
            onSuccess = { true },
            onFailure = {
                // The plaintext database has not been touched. An unencrypted
                // record the user still has beats an encrypted one nobody can
                // open, so Room is handed the ordinary opener and Settings is told
                // the truth about it.
                staged.delete()
                state.update { it.copy(encrypted = false) }
                false
            }
        )
    }

    /**
     * Copies a plaintext database into a new encrypted one and swaps them over.
     *
     * SQLCipher cannot encrypt a file in place; `sqlcipher_export` writes the
     * contents into a second, keyed database. That is the safe shape anyway — the
     * original is untouched until the copy has been opened with the passphrase and
     * its rows counted, so a failure at any point here leaves the user exactly
     * where they started.
     */
    private fun encrypt(db: File, staged: File, passphrase: String) {
        val version: Int
        val counts: List<Long>

        val plain = SQLiteDatabase.openOrCreateDatabase(db, "", null, null)
        try {
            plain.rawExecSQL(
                "ATTACH DATABASE '${sqlLiteral(staged.absolutePath)}' " +
                    "AS encrypted KEY '${sqlLiteral(passphrase)}'"
            )
            plain.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            plain.rawExecSQL("DETACH DATABASE encrypted")
            // Carried across by hand: sqlcipher_export copies tables and rows, not
            // the user_version Room reads to decide whether to run a migration.
            version = plain.version
            counts = rowCounts(plain)
        } finally {
            // Also checkpoints the write-ahead log, so the file about to be renamed
            // away is not still holding rows the export needed.
            plain.close()
        }

        val copy = SQLiteDatabase.openOrCreateDatabase(staged, passphrase, null, null)
        try {
            val copied = rowCounts(copy)
            check(copied == counts) { "row counts differ after export: $counts then $copied" }
            copy.version = version
        } finally {
            copy.close()
        }

        swap(db, staged)
    }

    /** Proof the copy holds what the original held, before the original is let go. */
    private fun rowCounts(db: SQLiteDatabase): List<Long> =
        listOf("lands", "photos").map { table ->
            DatabaseUtils.longForQuery(db, "SELECT count(*) FROM $table", null)
        }

    /**
     * Puts the encrypted copy where Room expects it.
     *
     * Ordered so that at no instant is the only copy of the data a file this method
     * is in the middle of moving: the original goes aside under a name [prepare]
     * recognises on the next launch, and is deleted only once the encrypted file is
     * in place.
     */
    private fun swap(db: File, staged: File) {
        val dir = db.parentFile ?: error("database has no directory")
        val aside = File(dir, db.name + ASIDE)
        aside.delete()
        check(db.renameTo(aside)) { "could not move the plaintext database aside" }

        // These belonged to the plaintext file, whose contents are now in the copy.
        File(dir, "${db.name}-wal").delete()
        File(dir, "${db.name}-shm").delete()

        if (!staged.renameTo(db)) {
            aside.renameTo(db)
            error("could not move the encrypted database into place")
        }
        aside.delete()
    }
}

/**
 * Holds off both the choice of opener and the migration until Room actually opens
 * the file.
 *
 * Room resolves this factory inside `build()`, which the app reaches on the main
 * thread from its dependency container — so anything done here would block a
 * screen transition, and getting the choice wrong would hand the encrypted opener
 * a plaintext file and crash on the first query. Deferring solves both: the work
 * happens on the thread that first runs a query, which Room guarantees is not the
 * main one, and by then the state of the file is known.
 */
private class DeferredFactory(
    private val encrypted: SupportSQLiteOpenHelper.Factory,
    private val plain: SupportSQLiteOpenHelper.Factory,
    private val prepare: () -> Boolean
) : SupportSQLiteOpenHelper.Factory {

    override fun create(
        configuration: SupportSQLiteOpenHelper.Configuration
    ): SupportSQLiteOpenHelper = DeferredOpenHelper(configuration, encrypted, plain, prepare)
}

private class DeferredOpenHelper(
    private val configuration: SupportSQLiteOpenHelper.Configuration,
    private val encrypted: SupportSQLiteOpenHelper.Factory,
    private val plain: SupportSQLiteOpenHelper.Factory,
    private val prepare: () -> Boolean
) : SupportSQLiteOpenHelper {

    /**
     * Room asks for this during `build()` — before any query, on the main thread.
     * Remembered and replayed rather than forwarded, because forwarding it would
     * create the delegate then and there and defeat the whole arrangement.
     */
    @Volatile
    private var writeAheadLogging: Boolean? = null

    private val delegate = lazy {
        val factory = if (prepare()) encrypted else plain
        factory.create(configuration).also { helper ->
            writeAheadLogging?.let(helper::setWriteAheadLoggingEnabled)
        }
    }

    override val databaseName: String?
        get() = configuration.name

    override val writableDatabase: SupportSQLiteDatabase
        get() = delegate.value.writableDatabase

    override val readableDatabase: SupportSQLiteDatabase
        get() = delegate.value.readableDatabase

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        writeAheadLogging = enabled
        if (delegate.isInitialized()) delegate.value.setWriteAheadLoggingEnabled(enabled)
    }

    /** A database never opened has nothing to close, and must not be opened to do it. */
    override fun close() {
        if (delegate.isInitialized()) delegate.value.close()
    }
}
