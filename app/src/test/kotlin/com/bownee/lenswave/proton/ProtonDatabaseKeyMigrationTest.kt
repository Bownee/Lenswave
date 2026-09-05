package com.bownee.lenswave.proton

import com.bownee.lenswave.proton.ProtonDatabaseKeyMigration.DEFAULT_KDF_ITERATIONS
import com.bownee.lenswave.proton.ProtonDatabaseKeyMigration.FAST_KDF_ITERATIONS
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
    private val zeroPassphrase = ByteArray(32)
    private val reported = mutableListOf<Throwable>()

    private fun database(): File = File(temporaryFolder.root, "session.db")

    private fun prepare(
        database: File,
        probe: FakeProbe,
        passphraseReplaced: Boolean = false,
    ): Int = ProtonDatabaseKeyMigration.prepare(database, passphrase, passphraseReplaced, probe, reported::add)

    @Test
    fun `a missing database earns the marker without a probe`() {
        val database = database()
        val probe = FakeProbe()

        assertEquals(FAST_KDF_ITERATIONS, prepare(database, probe))

        assertTrue(ProtonDatabaseKeyMigration.keyedMarker(database).isFile)
        assertEquals(0, probe.opens)
    }

    @Test
    fun `a zero-keyed database is re-encrypted with the passphrase and the marker skips later probes`() {
        val database = FakeProbe.write(database(), zeroPassphrase, DEFAULT_KDF_ITERATIONS)
        val probe = FakeProbe()

        assertEquals(FAST_KDF_ITERATIONS, prepare(database, probe))

        assertTrue(probe.opensWith(database, passphrase, FAST_KDF_ITERATIONS))
        assertTrue(ProtonDatabaseKeyMigration.keyedMarker(database).isFile)
        assertTrue(reported.isEmpty())
        val opensBefore = probe.opens
        assertEquals(FAST_KDF_ITERATIONS, prepare(database, probe))
        assertEquals(opensBefore, probe.opens)
    }

    @Test
    fun `a database keyed at the default round count is re-encrypted at the fast one`() {
        val database = FakeProbe.write(database(), passphrase, DEFAULT_KDF_ITERATIONS)
        File(database.path + "-wal").writeText("wal")
        val probe = FakeProbe()

        assertEquals(FAST_KDF_ITERATIONS, prepare(database, probe))

        assertTrue(probe.opensWith(database, passphrase, FAST_KDF_ITERATIONS))
        assertFalse(File(database.path + "-wal").exists())
        assertFalse(File(database.path + ".migrating").exists())
        assertTrue(ProtonDatabaseKeyMigration.keyedMarker(database).isFile)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `a database already at the fast round count needs no export`() {
        val database = FakeProbe.write(database(), passphrase, FAST_KDF_ITERATIONS)
        val probe = FakeProbe()

        assertEquals(FAST_KDF_ITERATIONS, prepare(database, probe))

        assertEquals(1, probe.opens)
        assertEquals(0, probe.exports)
        assertTrue(ProtonDatabaseKeyMigration.keyedMarker(database).isFile)
    }

    @Test
    fun `the previous generation's marker does not skip the probe and is removed`() {
        val database = FakeProbe.write(database(), passphrase, DEFAULT_KDF_ITERATIONS)
        val legacyMarker = File(database.path + ".keyed").apply { writeText("") }
        val probe = FakeProbe()

        prepare(database, probe)

        assertEquals(1, probe.exports)
        assertFalse(legacyMarker.exists())
        assertTrue(ProtonDatabaseKeyMigration.keyedMarker(database).isFile)
    }

    @Test
    fun `a database that opens with neither key is discarded and reported`() {
        val database = FakeProbe.write(database(), ByteArray(32) { 9 }, DEFAULT_KDF_ITERATIONS)
        File(database.path + "-wal").writeText("wal")
        val probe = FakeProbe()

        assertEquals(FAST_KDF_ITERATIONS, prepare(database, probe))

        assertFalse(database.exists())
        assertFalse(File(database.path + "-wal").exists())
        assertEquals(1, reported.size)
        assertTrue(ProtonDatabaseKeyMigration.keyedMarker(database).isFile)
    }

    @Test
    fun `a replaced passphrase removes the marker and the probe runs again`() {
        val database = FakeProbe.write(database(), ByteArray(32) { 7 }, FAST_KDF_ITERATIONS)
        val marker = ProtonDatabaseKeyMigration.keyedMarker(database).apply { writeText("") }
        // The database was keyed with a passphrase the store no longer holds.
        val probe = FakeProbe()

        prepare(database, probe, passphraseReplaced = true)

        assertTrue(probe.opens > 0)
        assertFalse(database.exists())
        assertEquals(1, reported.size)
        // The fresh database Room will create is keyed the fast way, so the marker is truthful again.
        assertTrue(marker.isFile)
    }

    @Test
    fun `a marker that cannot be written is reported and the process still probes only once`() {
        val database = FakeProbe.write(database(), passphrase, FAST_KDF_ITERATIONS)
        // A directory where the marker file should go: createNewFile cannot succeed.
        val marker = ProtonDatabaseKeyMigration.keyedMarker(database).apply { mkdirs() }
        val probe = FakeProbe()

        prepare(database, probe)
        val opensAfterFirst = probe.opens
        prepare(database, probe)

        assertTrue(marker.isDirectory)
        assertEquals(1, reported.size)
        assertTrue(opensAfterFirst > 0)
        assertEquals(opensAfterFirst, probe.opens)
    }

    @Test
    fun `a failed export keeps a passphrase-keyed database and opens it the slow way`() {
        val database = FakeProbe.write(database(), passphrase, DEFAULT_KDF_ITERATIONS)
        val probe = FakeProbe(exportFails = true)

        assertEquals(DEFAULT_KDF_ITERATIONS, prepare(database, probe))

        assertTrue(probe.opensWith(database, passphrase, DEFAULT_KDF_ITERATIONS))
        assertFalse(File(database.path + ".migrating").exists())
        assertFalse(ProtonDatabaseKeyMigration.keyedMarker(database).exists())
        assertEquals(1, reported.size)
        // Nothing recorded, so the next launch tries the export again.
        prepare(database, probe)
        assertEquals(2, probe.exports)
    }

    @Test
    fun `an export that does not open keeps the old database`() {
        val database = FakeProbe.write(database(), passphrase, DEFAULT_KDF_ITERATIONS)
        val probe = FakeProbe(exportGarbage = true)

        assertEquals(DEFAULT_KDF_ITERATIONS, prepare(database, probe))

        assertTrue(probe.opensWith(database, passphrase, DEFAULT_KDF_ITERATIONS))
        assertFalse(File(database.path + ".migrating").exists())
        assertEquals(1, reported.size)
    }

    @Test
    fun `a failed export of a zero-keyed database discards it`() {
        val database = FakeProbe.write(database(), zeroPassphrase, DEFAULT_KDF_ITERATIONS)
        val probe = FakeProbe(exportFails = true)

        assertEquals(FAST_KDF_ITERATIONS, prepare(database, probe))

        assertFalse(database.exists())
        assertEquals(1, reported.size)
        assertTrue(ProtonDatabaseKeyMigration.keyedMarker(database).isFile)
    }

    /** A "database" file whose text records the key and round count it opens with. */
    private class FakeProbe(
        private val exportFails: Boolean = false,
        private val exportGarbage: Boolean = false,
    ) : DatabaseKeyProbe {
        var opens = 0
        var exports = 0

        override fun opensWith(
            databaseFile: File,
            passphrase: ByteArray,
            kdfIterations: Int,
        ): Boolean {
            opens++
            return databaseFile.isFile && databaseFile.readText() == contents(passphrase, kdfIterations)
        }

        override fun reencrypt(
            databaseFile: File,
            from: ByteArray,
            fromIterations: Int,
            to: ByteArray,
            toIterations: Int,
            target: File,
        ) {
            exports++
            if (exportFails) throw IllegalStateException("file is not a database")
            check(databaseFile.readText() == contents(from, fromIterations))
            target.writeText(if (exportGarbage) "garbage" else contents(to, toIterations))
        }

        companion object {
            fun write(
                databaseFile: File,
                passphrase: ByteArray,
                kdfIterations: Int,
            ): File = databaseFile.apply { writeText(contents(passphrase, kdfIterations)) }

            private fun contents(
                passphrase: ByteArray,
                kdfIterations: Int,
            ): String = "key=${passphrase.joinToString(",")};kdf=$kdfIterations"
        }
    }
}
