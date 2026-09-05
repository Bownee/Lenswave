package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** The SQLCipher operations the migration needs; a seam so its decisions run without the native library. */
internal interface DatabaseKeyProbe {
    fun opensWith(
        databaseFile: File,
        passphrase: ByteArray,
        kdfIterations: Int,
    ): Boolean

    /**
     * Copies the database, which opens with [from] at [fromIterations], into [target], keyed
     * with [to] at [toIterations]. Throws when SQLCipher refuses; [target] is then garbage.
     */
    fun reencrypt(
        databaseFile: File,
        from: ByteArray,
        fromIterations: Int,
        to: ByteArray,
        toIterations: Int,
        target: File,
    )
}

/**
 * Brings the session database to the key and key-derivation settings Room opens it with.
 *
 * Two generations of database need repair. Earlier releases zeroed the passphrase array right
 * after `Room.databaseBuilder(...).build()`, before SQLCipher had read it, so every database
 * they created is keyed with 32 zero bytes. Releases after that keyed with the real passphrase
 * but at SQLCipher's default [DEFAULT_KDF_ITERATIONS] PBKDF2 rounds, a few hundred milliseconds
 * per connection at every launch; the passphrase is 32 random bytes, so the stretching buys
 * nothing and the app now opens at [FAST_KDF_ITERATIONS]. SQLCipher cannot change the round
 * count of an existing file, so both generations are migrated the same way: the old file is
 * exported into a new one keyed with the real passphrase at the fast setting, which then
 * replaces it. A file that opens with neither key is discarded and the user signs in again
 * rather than the app crashing at launch.
 *
 * Probing a key at the default setting is a full derivation, so once the database is known to
 * open the fast way a marker next to it records that and later launches skip the probes. Room
 * creates a missing database the fast way, so a missing file earns the marker too. The marker
 * is only ever written once the file demonstrably opens the fast way or is known to be gone: a
 * marker beside a file that does not would leave Room failing permanently. When the export
 * fails on a database that still opens with the passphrase the slow way, the caller is told to
 * open it that way instead: slower, reported, and tried again next launch, but never a lockout.
 *
 * A passphrase that was just replaced (its file had become unreadable, see
 * [com.bownee.lenswave.storage.DatabasePassphraseStore]) makes the marker a lie: the database
 * was keyed with the old passphrase. The caller says so and the probes run regardless, find
 * that neither key opens the file and discard it.
 */
internal object ProtonDatabaseKeyMigration {
    /** SQLCipher 4's default PBKDF2 round count; every database before the fast setting was created with it. */
    const val DEFAULT_KDF_ITERATIONS = 256_000

    /** The round count the app opens with; the passphrase is random, so this is a formality. */
    const val FAST_KDF_ITERATIONS = 1_000

    /**
     * Returns the PBKDF2 round count Room must open [databaseFile] with. [passphrase] is only
     * ever read here; every SQLCipher call below gets its own copy, so a library that clears
     * the key array it was handed cannot touch the caller's.
     */
    fun prepare(
        databaseFile: File,
        passphrase: ByteArray,
        passphraseReplaced: Boolean = false,
        probe: DatabaseKeyProbe = SqlCipherKeyProbe,
        reportFailure: (Throwable) -> Unit = { error ->
            LenswaveDiagnostics.reportFailure(LenswaveOperation.SESSION_DATABASE_REKEY, error)
        },
    ): Int {
        val marker = keyedMarker(databaseFile)
        if (passphraseReplaced) {
            verifiedInProcess.remove(databaseFile.path)
            marker.delete()
        } else if (databaseFile.path in verifiedInProcess || marker.isFile) {
            return FAST_KDF_ITERATIONS
        }
        // The marker of the previous generation only vouched for the key, not the round count.
        File(databaseFile.path + LEGACY_MARKER_SUFFIX).delete()
        val iterations = migrate(databaseFile, passphrase, probe, reportFailure)
        if (iterations == FAST_KDF_ITERATIONS) {
            verifiedInProcess.add(databaseFile.path)
            recordKeyed(marker, reportFailure)
        }
        return iterations
    }

    /** The open hook that applies [kdfIterations] before SQLCipher derives the key; needed by every connection. */
    fun kdfHook(kdfIterations: Int): SQLiteDatabaseHook =
        object : SQLiteDatabaseHook {
            override fun preKey(connection: SQLiteConnection) = Unit

            override fun postKey(connection: SQLiteConnection) {
                connection.execute("PRAGMA kdf_iter = $kdfIterations", null, null)
            }
        }

    /**
     * A marker that cannot be written is reported rather than swallowed: the probes would
     * otherwise run silently on every launch. The in-process set still spares this process a
     * second round.
     */
    private fun recordKeyed(
        marker: File,
        reportFailure: (Throwable) -> Unit,
    ) {
        try {
            marker.parentFile?.mkdirs()
            if (!marker.createNewFile() && !marker.isFile) {
                reportFailure(
                    IllegalStateException(
                        "Could not record the session database key; the probe runs again next launch",
                    ),
                )
            }
        } catch (error: IOException) {
            reportFailure(error)
        }
    }

    /** Sits beside the database so a cleared app storage removes both together. */
    fun keyedMarker(databaseFile: File): File = File(databaseFile.path + MARKER_SUFFIX)

    /** The round count [databaseFile] now opens with using [passphrase], or [FAST_KDF_ITERATIONS] when it no longer exists. */
    private fun migrate(
        databaseFile: File,
        passphrase: ByteArray,
        probe: DatabaseKeyProbe,
        reportFailure: (Throwable) -> Unit,
    ): Int {
        if (!databaseFile.isFile) return FAST_KDF_ITERATIONS
        if (probe.opensWith(databaseFile, passphrase, FAST_KDF_ITERATIONS)) return FAST_KDF_ITERATIONS
        val zeroPassphrase = ByteArray(passphrase.size)
        val source =
            when {
                probe.opensWith(databaseFile, passphrase, DEFAULT_KDF_ITERATIONS) -> {
                    passphrase
                }

                probe.opensWith(databaseFile, zeroPassphrase, DEFAULT_KDF_ITERATIONS) -> {
                    zeroPassphrase
                }

                else -> {
                    reportFailure(IllegalStateException("Session database opens with neither key; discarding it"))
                    discard(databaseFile, reportFailure)
                    return FAST_KDF_ITERATIONS
                }
            }
        // Room cannot open a zero-keyed file at all, so a failed export of one is discarded as
        // before; a file that opens with the passphrase the slow way is kept and opened that way.
        val fallback = {
            if (source === passphrase) {
                DEFAULT_KDF_ITERATIONS
            } else {
                discard(databaseFile, reportFailure)
                FAST_KDF_ITERATIONS
            }
        }
        val target = File(databaseFile.path + MIGRATING_SUFFIX)
        deleteWithSidecars(target)
        try {
            probe.reencrypt(databaseFile, source, DEFAULT_KDF_ITERATIONS, passphrase, FAST_KDF_ITERATIONS, target)
        } catch (error: Throwable) {
            reportFailure(error)
            deleteWithSidecars(target)
            return fallback()
        }
        // The export is trusted only once the new file demonstrably opens the fast way.
        if (!probe.opensWith(target, passphrase, FAST_KDF_ITERATIONS)) {
            reportFailure(IllegalStateException("Re-encrypted session database does not open; keeping the old one"))
            deleteWithSidecars(target)
            return fallback()
        }
        if (!discard(databaseFile, reportFailure)) {
            deleteWithSidecars(target)
            return fallback()
        }
        if (!target.renameTo(databaseFile)) {
            // The old file is gone and the new one cannot take its place: Room creates a fresh one.
            reportFailure(IllegalStateException("Could not move the re-encrypted session database into place"))
            deleteWithSidecars(target)
        }
        return FAST_KDF_ITERATIONS
    }

    /** True when nothing of the database is left on disk; a file that survives keeps the probe alive. */
    private fun discard(
        databaseFile: File,
        reportFailure: (Throwable) -> Unit,
    ): Boolean {
        if (deleteWithSidecars(databaseFile)) return true
        reportFailure(IllegalStateException("Could not discard the session database; the key probe stays enabled"))
        return false
    }

    private fun deleteWithSidecars(databaseFile: File): Boolean =
        listOf("", "-journal", "-wal", "-shm")
            .map { suffix ->
                val file = File(databaseFile.path + suffix)
                file.delete() || !file.exists()
            }.all { it }

    private const val MARKER_SUFFIX = ".keyed-fast"
    private const val LEGACY_MARKER_SUFFIX = ".keyed"
    private const val MIGRATING_SUFFIX = ".migrating"

    /** Databases known to open the fast way since this process started. */
    private val verifiedInProcess: MutableSet<String> = ConcurrentHashMap.newKeySet()
}

/** The real SQLCipher; every call copies the passphrase it is handed, see [ProtonDatabaseKeyMigration]. */
internal object SqlCipherKeyProbe : DatabaseKeyProbe {
    override fun opensWith(
        databaseFile: File,
        passphrase: ByteArray,
        kdfIterations: Int,
    ): Boolean =
        try {
            SQLiteDatabase
                .openDatabase(
                    databaseFile.path,
                    passphrase.copyOf(),
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                    ProtonDatabaseKeyMigration.kdfHook(kdfIterations),
                ).use { database ->
                    database.rawQuery("SELECT count(*) FROM sqlite_master", null).use { cursor -> cursor.moveToFirst() }
                }
            true
        } catch (_: Throwable) {
            false
        }

    /**
     * SQLCipher's export recipe: the cipher settings issued before ATTACH shape the attached
     * database, and `sqlcipher_export` copies schema and rows into it. The process-wide default
     * is set as well, so the attached file gets the round count whichever of the two SQLCipher
     * consults, and restored afterwards so nothing else in the process inherits it.
     */
    override fun reencrypt(
        databaseFile: File,
        from: ByteArray,
        fromIterations: Int,
        to: ByteArray,
        toIterations: Int,
        target: File,
    ) {
        SQLiteDatabase
            .openDatabase(
                databaseFile.path,
                from.copyOf(),
                null,
                // ATTACH creates the target only when the main connection may create files.
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
                ProtonDatabaseKeyMigration.kdfHook(fromIterations),
            ).use { database ->
                try {
                    database.execSQL("PRAGMA cipher_default_kdf_iter = $toIterations")
                    database.execSQL("PRAGMA kdf_iter = $toIterations")
                    val attachArguments = arrayOf<Any>(target.path, to.copyOf())
                    database.execSQL("ATTACH DATABASE ? AS migrated KEY ?", attachArguments)
                    database.rawQuery("SELECT sqlcipher_export('migrated')", null).use { cursor ->
                        cursor.moveToFirst()
                    }
                    database.execSQL("DETACH DATABASE migrated")
                } finally {
                    database.execSQL(
                        "PRAGMA cipher_default_kdf_iter = ${ProtonDatabaseKeyMigration.DEFAULT_KDF_ITERATIONS}",
                    )
                }
            }
    }
}
