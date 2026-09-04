package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Repairs session databases that earlier releases keyed with an all-zero passphrase.
 *
 * Room keeps only a reference to the passphrase array and SQLCipher reads it when the database
 * is first opened, long after `Room.databaseBuilder(...).build()` returns. Earlier releases zeroed
 * the array right after `build()`, so every database they created was effectively encrypted with
 * 32 zero bytes. Opening such a file with the real passphrase fails, so before Room touches the
 * database this migration rekeys it in place; if neither key opens it, the file is discarded and
 * the user signs in again rather than the app crashing at launch.
 *
 * Probing the key means a full SQLCipher key derivation, a few hundred milliseconds on the main
 * thread while Hilt builds the graph, so once the database is known to open with the real
 * passphrase a marker next to it records that and every later launch skips the probe. Room
 * creates a missing database with the real passphrase, so a missing file earns the marker too.
 * The marker is only ever written once the file is known to open with the real passphrase or is
 * known to be gone: a marker beside a database that still opens with neither key would skip the
 * probe on every later launch and leave Room failing permanently.
 */
internal object ProtonDatabaseKeyMigration {
    fun rekeyLegacyDatabase(
        databaseFile: File,
        passphrase: ByteArray,
    ) {
        val marker = keyedMarker(databaseFile)
        if (marker.isFile) return
        if (!rekey(databaseFile, passphrase)) return
        marker.parentFile?.mkdirs()
        runCatching { marker.createNewFile() }
    }

    /** Sits beside the database so a cleared app storage removes both together. */
    fun keyedMarker(databaseFile: File): File = File(databaseFile.path + MARKER_SUFFIX)

    /** True when the database now opens with [passphrase] or no longer exists, so Room will succeed. */
    private fun rekey(
        databaseFile: File,
        passphrase: ByteArray,
    ): Boolean {
        if (!databaseFile.isFile) return true
        if (opensWith(databaseFile, passphrase)) return true
        val legacyPassphrase = ByteArray(passphrase.size)
        if (!opensWith(databaseFile, legacyPassphrase)) {
            LenswaveDiagnostics.reportFailure(
                LenswaveOperation.SESSION_DATABASE_REKEY,
                IllegalStateException("Session database opens with neither key; discarding it"),
            )
            return discard(databaseFile)
        }
        try {
            SQLiteDatabase
                .openDatabase(
                    databaseFile.path,
                    legacyPassphrase,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                    null,
                ).use { database -> database.changePassword(passphrase) }
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure(LenswaveOperation.SESSION_DATABASE_REKEY, error)
            return discard(databaseFile)
        }
        // The rekey is trusted only once the file demonstrably opens with the new key.
        return opensWith(databaseFile, passphrase)
    }

    private fun opensWith(
        databaseFile: File,
        passphrase: ByteArray,
    ): Boolean =
        try {
            SQLiteDatabase
                .openDatabase(
                    databaseFile.path,
                    passphrase,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                    null,
                ).use { database ->
                    database.rawQuery("SELECT count(*) FROM sqlite_master", null).use { cursor -> cursor.moveToFirst() }
                }
            true
        } catch (_: Throwable) {
            false
        }

    /** True when nothing of the database is left on disk; a file that survives keeps the probe alive. */
    private fun discard(databaseFile: File): Boolean {
        val deleted =
            listOf("", "-journal", "-wal", "-shm").map { suffix ->
                val file = File(databaseFile.path + suffix)
                file.delete() || !file.exists()
            }
        if (deleted.all { it }) return true
        LenswaveDiagnostics.reportFailure(
            LenswaveOperation.SESSION_DATABASE_REKEY,
            IllegalStateException("Could not discard the session database; the key probe stays enabled"),
        )
        return false
    }

    private const val MARKER_SUFFIX = ".keyed"
}
