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
        val database = context.getDatabasePath("proton-session.db")
        val passphraseFile = java.io.File(context.noBackupFilesDir, "proton-session.key")
        context.deleteDatabase(database.name)
        passphraseFile.delete()
        secureFiles.deleteKey("proton-session-database-key")
        try {
            val room = ProtonCoreDatabase.create(context, DatabasePassphraseStore(context, secureFiles))
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
            secureFiles.deleteKey("proton-session-database-key")
        }
    }
}
