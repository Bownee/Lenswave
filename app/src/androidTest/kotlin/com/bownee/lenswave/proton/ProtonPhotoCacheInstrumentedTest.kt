package com.bownee.lenswave.proton

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.storage.AtomicFileStore
import com.bownee.lenswave.storage.SecureFileStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtonPhotoCacheInstrumentedTest {
    @Test fun encryptedIndexCorruptionIsInvalidatedAndActivePartsArePreserved() {
        val context = isolatedContext()
        val userId = "cache-${UUID.randomUUID()}"
        val clock = FakeClock(System.currentTimeMillis())
        val cache = createCache(context, SecureFileStore(), clock)
        val index = File(
            context.filesDir,
            "proton-photo-cache/${AtomicFileStore.safeName(userId)}/index.json",
        )
        try {
            cache.writeIndex(userId, listOf(ProtonGalleryPhoto("node", 123L, false)))
            assertEquals(listOf("node"), cache.readIndex(userId).map(ProtonGalleryPhoto::nodeUid))
            val raw = index.readText(Charsets.ISO_8859_1)
            assertFalse(raw.contains("node"))

            val bytes = index.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            index.writeBytes(bytes)
            assertTrue(cache.readIndex(userId).isEmpty())
            assertFalse(cache.hasTimelineSnapshot(userId))

            val activePart = cache.createOriginalTarget(userId, "active").first
            activePart.writeText("in progress")
            cache.reconcilePhotos(userId, emptyList(), emptyList())
            assertTrue(activePart.exists())
            clock.value += 25L * 60L * 60L * 1_000L
            cache.reconcilePhotos(userId, emptyList(), emptyList())
            assertFalse(activePart.exists())

            val (plaintext, encrypted) = cache.createOriginalTarget(userId, "bounded")
            plaintext.writeText("decrypted private content")
            assertTrue(cache.commitOriginal(userId, "bounded", plaintext, encrypted).isFile)
            clock.value += 61L * 60L * 1_000L
            assertEquals(null, cache.readOriginal(userId, "bounded"))
            assertFalse(encrypted.exists())
        } finally {
            cache.clearUser(userId)
            context.testRoot.deleteRecursively()
        }
    }

    @Test fun thumbnailDatasetSurvivesMaintenanceAndAndroidCacheClearing() {
        val context = isolatedContext()
        val userId = "retention-${UUID.randomUUID()}"
        val clock = FakeClock(System.currentTimeMillis())
        val cache = createCache(context, SecureFileStore(), clock)
        val thumbnail = validThumbnail()
        try {
            cache.writeThumbnail(userId, "old", thumbnail)
            // Filesystems may round last-modified values, so retain a wide deterministic gap.
            clock.value += 60_000
            cache.writeThumbnail(userId, "current", thumbnail)

            clock.value += 8L * 24L * 60L * 60L * 1_000L
            context.cacheDir.deleteRecursively()
            context.cacheDir.mkdirs()
            cache.trimUser(userId)

            assertTrue(cache.thumbnailExists(userId, "old"))
            assertTrue(cache.thumbnailExists(userId, "current"))
            assertEquals(2, cache.thumbnailCount(userId))
            assertTrue(cache.loadThumbnail(userId, "old") != null)
        } finally {
            cache.clearUser(userId)
            context.testRoot.deleteRecursively()
        }
    }

    @Test fun thumbnailValidationRequiresACompletePixelDecode() {
        val context = isolatedContext()
        val userId = "decode-${UUID.randomUUID()}"
        val nodeUid = "truncated"
        val secureFiles = SecureFileStore()
        val cache = createCache(context, secureFiles, FakeClock(System.currentTimeMillis()))
        val truncatedThumbnail = validThumbnail().copyOf(33)
        val thumbnailFile = File(
            context.filesDir,
            "proton-photo-cache/${AtomicFileStore.safeName(userId)}/thumbnails/" +
                "${AtomicFileStore.safeName(nodeUid)}.thumb",
        )
        try {
            assertFalse(runCatching {
                cache.writeThumbnail(userId, nodeUid, truncatedThumbnail)
            }.isSuccess)

            secureFiles.write(
                "proton-media:$userId",
                thumbnailFile,
                truncatedThumbnail,
                "Could not write test thumbnail",
            )
            assertTrue(cache.thumbnailExists(userId, nodeUid))
            assertNull(cache.loadThumbnail(userId, nodeUid))
            assertFalse(thumbnailFile.exists())
        } finally {
            cache.clearUser(userId)
            context.testRoot.deleteRecursively()
        }
    }

    @Test fun decodedThumbnailsAreSizedOnceAndSharedAcrossConsumers() {
        val context = isolatedContext()
        val userId = "decoded-${UUID.randomUUID()}"
        val secureFiles = SecureFileStore()
        val clock = FakeClock(System.currentTimeMillis())
        val cache = createCache(context, secureFiles, clock)
        val output = ByteArrayOutputStream()
        val source = Bitmap.createBitmap(1_200, 600, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
            cache.writeThumbnail(userId, "large", output.toByteArray())

            val first = cache.loadThumbnail(userId, "large")
            val second = cache.loadThumbnail(userId, "large")

            assertEquals(480, first?.width)
            assertEquals(240, first?.height)
            assertSame(first, second)

            val restoredStore = ProtonThumbnailStore(context, secureFiles, clock)
            val restored = restoredStore.load(userId, "large")
            assertEquals(480, restored?.width)
            assertEquals(240, restored?.height)
            assertSame(restored, restoredStore.load(userId, "large"))
        } finally {
            source.recycle()
            cache.clearUser(userId)
            context.testRoot.deleteRecursively()
        }
    }

    @Test fun thumbnailQueueCaptureTimesSurviveProcessRestoration() {
        val context = isolatedContext()
        val userId = "queue-${UUID.randomUUID()}"
        val cache = createCache(
            context,
            SecureFileStore(),
            FakeClock(System.currentTimeMillis()),
        )
        val entries = listOf(
            ProtonThumbnailQueueEntry(
                nodeUid = "newest",
                sourceCaptureTimes = mapOf("timeline" to 300L, "album:one" to 300L),
            ),
            ProtonThumbnailQueueEntry(
                nodeUid = "older",
                sourceCaptureTimes = mapOf("timeline" to 100L),
                retryCount = 2,
                retryAtMillis = 4_000L,
            ),
        )
        try {
            cache.writeThumbnailQueue(userId, entries)

            assertEquals(entries, cache.readThumbnailQueue(userId))
        } finally {
            cache.clearUser(userId)
            context.testRoot.deleteRecursively()
        }
    }

    private fun validThumbnail(): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        } finally {
            bitmap.recycle()
        }
        output.toByteArray()
    }

    private fun isolatedContext(): IsolatedCacheContext {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "proton-cache-test-${UUID.randomUUID()}").apply { mkdirs() }
        return IsolatedCacheContext(context, root)
    }

    private fun createCache(
        context: Context,
        secureFiles: SecureFileStore,
        clock: LenswaveClock,
    ): ProtonPhotoCache = ProtonPhotoCache(
        context,
        secureFiles,
        clock,
        ProtonThumbnailStore(context, secureFiles, clock),
    )

    private class IsolatedCacheContext(context: Context, val testRoot: File) : ContextWrapper(context) {
        private val testFiles = File(testRoot, "files").apply { mkdirs() }
        private val testCache = File(testRoot, "cache").apply { mkdirs() }

        override fun getFilesDir(): File = testFiles

        override fun getCacheDir(): File = testCache
    }

    private class FakeClock(var value: Long) : LenswaveClock {
        override fun nowMillis(): Long = value
    }
}
