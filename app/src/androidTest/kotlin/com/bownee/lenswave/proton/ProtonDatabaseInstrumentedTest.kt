package com.bownee.lenswave.proton

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bownee.lenswave.storage.DatabasePassphraseStore
import com.bownee.lenswave.storage.SecureFileStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtonDatabaseInstrumentedTest {
    @Test
    fun roomDatabaseIsEncrypted() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val secureFiles = SecureFileStore(context)
        // Its own file and key scope: the app under test owns the real session database and may
        // open it on a background thread at any time.
        val database = context.getDatabasePath(DATABASE_NAME)
        val passphraseFile = java.io.File(context.noBackupFilesDir, KEY_FILE_NAME)
        context.deleteDatabase(database.name)
        passphraseFile.delete()
        secureFiles.deleteKey(KEY_SCOPE)
        try {
            val passphrases = DatabasePassphraseStore(context, secureFiles, KEY_FILE_NAME, KEY_SCOPE)
            val room = ProtonCoreDatabase.create(context, passphrases, DATABASE_NAME)
            try {
                room.openHelper.writableDatabase
            } finally {
                room.close()
            }
            val header = database.inputStream().use { input -> ByteArray(16).also(input::read) }
            assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
            assertTrue(database.length() > 0L)
        } finally {
            context.deleteDatabase(database.name)
            passphraseFile.delete()
            secureFiles.deleteKey(KEY_SCOPE)
        }
    }

    private companion object {
        const val DATABASE_NAME = "instrumentation-session.db"
        const val KEY_FILE_NAME = "instrumentation-session.key"
        const val KEY_SCOPE = "instrumentation-session-database-key"
    }
}
