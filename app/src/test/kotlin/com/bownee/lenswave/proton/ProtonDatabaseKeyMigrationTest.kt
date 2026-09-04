package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProtonDatabaseKeyMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val passphrase = ByteArray(32) { (it + 1).toByte() }
    private val legacyPassphrase = ByteArray(32)
    private val reported = mutableListOf<Throwable>()

    private fun database(): File = File(temporaryFolder.root, "session.db")

    private fun migrate(
        database: File,
        probe: FakeProbe,
        passphraseReplaced: Boolean = false,
    ) {
        ProtonDatabaseKeyMigration.rekeyLegacyDatabase(database, passphrase, passphraseReplaced, probe, reported::add)
    }

    @Test
    fun `a missing database earns the marker without a probe`() {
        val database = database()
        val probe = FakeProbe(keyedWith = null)

        migrate(database, probe)

        assertTrue(ProtonDatabaseKeyMigration.keyedMarker(database).isFile)
        assertEquals(0, probe.opens)
    }

    @Test
    fun `a legacy database is rekeyed in place and the marker skips later probes`() {
        val database = database().apply { writeText("db") }
        val probe = FakeProbe(keyedWith = legacyPassphrase)

        migrate(database, probe)

        assertTrue(probe.keyedWith.contentEquals(passphrase))
        assertTrue(ProtonDatabaseKeyMigration.keyedMarker(database).isFile)
        assertTrue(reported.isEmpty())
        val opensBefore = probe.opens
        migrate(database, probe)
        assertEquals(opensBefore, probe.opens)
    }

    @Test
    fun `a database that opens with neither key is discarded and reported`() {
        val database = database().apply { writeText("db") }
        File(database.path + "-wal").writeText("wal")
        val probe = FakeProbe(keyedWith = ByteArray(32) { 9 })

        migrate(database, probe)

        assertFalse(database.exists())
        assertFalse(File(database.path + "-wal").exists())
        assertEquals(1, reported.size)
        assertTrue(ProtonDatabaseKeyMigration.keyedMarker(database).isFile)
    }

    @Test
    fun `a replaced passphrase removes the marker and the probe runs again`() {
        val database = database().apply { writeText("db") }
        val marker = ProtonDatabaseKeyMigration.keyedMarker(database).apply { writeText("") }
        // The database was keyed with a passphrase the store no longer holds.
        val probe = FakeProbe(keyedWith = ByteArray(32) { 7 })

        migrate(database, probe, passphraseReplaced = true)

        assertTrue(probe.opens > 0)
        assertFalse(database.exists())
        assertEquals(1, reported.size)
        // The fresh database Room will create is keyed with the new passphrase, so the marker is truthful again.
        assertTrue(marker.isFile)
    }

    @Test
    fun `a failed rekey discards the database`() {
        val database = database().apply { writeText("db") }
        val probe = FakeProbe(keyedWith = legacyPassphrase, rekeyFails = true)

        migrate(database, probe)

        assertFalse(database.exists())
        assertEquals(1, reported.size)
    }

    private class FakeProbe(
        var keyedWith: ByteArray?,
        private val rekeyFails: Boolean = false,
    ) : DatabaseKeyProbe {
        var opens = 0

        override fun opensWith(
            databaseFile: File,
            passphrase: ByteArray,
        ): Boolean {
            opens++
            return databaseFile.isFile && keyedWith?.contentEquals(passphrase) == true
        }

        override fun rekey(
            databaseFile: File,
            from: ByteArray,
            to: ByteArray,
        ) {
            if (rekeyFails) throw IllegalStateException("file is not a database")
            check(keyedWith?.contentEquals(from) == true)
            keyedWith = to.copyOf()
        }
    }
}
