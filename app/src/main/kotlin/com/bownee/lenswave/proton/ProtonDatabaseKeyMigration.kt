package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Repairs session databases that earlier releases keyed with an all-zero passphrase.
 *
 * Room keeps only a reference to the passphrase array and SQLCipher reads it when the database
 * is first opened, long after `Room.databaseBuilder(...).build()` returns. Earlier releases zeroed
 * the array right after `build()`, so every database they created was effectively encrypted with
 * 32 zero bytes. Opening such a file with the real passphrase fails, so before Room touches the
 * database this migration rekeys it in place; if neither key opens it, the file is discarded and
 * the user signs in again rather than the app crashing at launch.
 */
internal object ProtonDatabaseKeyMigration {
    fun rekeyLegacyDatabase(databaseFile: File, passphrase: ByteArray) {
        if (!databaseFile.isFile) return
        if (opensWith(databaseFile, passphrase)) return
        val legacyPassphrase = ByteArray(passphrase.size)
        if (!opensWith(databaseFile, legacyPassphrase)) {
            LenswaveDiagnostics.reportFailure(
                LenswaveOperation.SESSION_DATABASE_REKEY,
                IllegalStateException("Session database opens with neither key; discarding it"),
            )
            discard(databaseFile)
            return
        }
        try {
            SQLiteDatabase.openDatabase(
                databaseFile.path,
                legacyPassphrase,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null,
            ).use { database -> database.changePassword(passphrase) }
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure(LenswaveOperation.SESSION_DATABASE_REKEY, error)
            discard(databaseFile)
        }
    }

    private fun opensWith(databaseFile: File, passphrase: ByteArray): Boolean = try {
        SQLiteDatabase.openDatabase(
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

    private fun discard(databaseFile: File) {
        listOf("", "-journal", "-wal", "-shm").forEach { suffix ->
            File(databaseFile.path + suffix).delete()
        }
    }
}
