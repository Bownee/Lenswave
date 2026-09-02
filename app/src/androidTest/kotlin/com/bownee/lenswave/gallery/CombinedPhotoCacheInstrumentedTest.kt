package com.bownee.lenswave.gallery

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bownee.lenswave.storage.SecureFileStore
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CombinedPhotoCacheInstrumentedTest {
    @Test
    fun snapshotAndJournalAreEncryptedMergedAndCorruptionSafe() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val userId = "cache-${UUID.randomUUID()}"
        val cache = CombinedPhotoCache(context, SecureFileStore())
        val first = record("device:secret-one", "proton-one")
        val second = record("device:secret-two", "proton-two")

        try {
            cache.write(userId, CombinedMatchSnapshot("timeline", listOf(first)))
            cache.append(userId, "timeline", listOf(second))
            assertEquals(listOf(first, second), cache.read(userId).records)

            val files = File(context.filesDir, "combined-photo-cache").listFiles().orEmpty()
                .filter { it.name.startsWith(com.bownee.lenswave.storage.AtomicFileStore.safeName(userId)) }
            assertTrue(files.size >= 2)
            files.forEach { file ->
                val raw = file.readText(Charsets.ISO_8859_1)
                assertFalse(raw.contains("device:secret"))
                assertFalse(raw.contains("proton-one"))
            }

            cache.write(userId, CombinedMatchSnapshot("timeline", listOf(first)))
            val snapshot = files.firstOrNull { it.extension == "json" }
                ?: File(context.filesDir, "combined-photo-cache/${com.bownee.lenswave.storage.AtomicFileStore.safeName(userId)}.json")
            val bytes = snapshot.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            snapshot.writeBytes(bytes)
            assertTrue(cache.read(userId).records.isEmpty())
            assertFalse(snapshot.exists())
        } finally {
            cache.clear(userId)
        }
    }

    private fun record(stableId: String, nodeUid: String) = DevicePhotoMatchRecord(
        stableId = stableId,
        displayName = "private.jpg",
        sizeBytes = 42,
        modifiedAtEpochMillis = 84,
        checkedTimelineFingerprint = "timeline",
        checkedAtEpochMillis = 123,
        matchStrategyVersion = CombinedGallery.MATCH_STRATEGY_VERSION,
        sha1Hex = "00",
        protonNodeUids = listOf(nodeUid),
    )
}
