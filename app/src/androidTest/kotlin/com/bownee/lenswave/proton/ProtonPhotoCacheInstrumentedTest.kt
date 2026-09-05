package com.bownee.lenswave.proton

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.storage.AtomicFileStore
import com.bownee.lenswave.storage.SecureFileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProtonPhotoCacheInstrumentedTest {
    @Test fun encryptedIndexCorruptionIsInvalidatedAndOriginalsOutliveTheirDecryptedCopies() {
        val context = isolatedContext()
        val userId = "cache-${UUID.randomUUID()}"
        val clock = FakeClock(System.currentTimeMillis())
        val cache = createCache(context, SecureFileStore(File(context.filesDir, "secure-keys")), clock)
        val index =
            File(
                context.filesDir,
                "proton-photo-cache/${AtomicFileStore.safeName(userId)}/index.json",
            )
        try {
            cache.writeIndex(userId, listOf(ProtonGalleryPhoto("node", 123L, false)))
            assertEquals(listOf("node"), cache.readTimelineSnapshot(userId)?.map(ProtonGalleryPhoto::nodeUid))
            val raw = index.readText(Charsets.ISO_8859_1)
            assertFalse(raw.contains("node"))

            val bytes = index.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            index.writeBytes(bytes)
            // A corrupt index reads as absent and is discarded, so the next read is a plain miss.
            assertNull(cache.readTimelineSnapshot(userId))
            assertFalse(index.exists())

            // A download in flight survives reconciliation while its photo is still listed and
            // is dropped with everything else once the photo has left the timeline.
            // A download in flight survives reconciliation while its photo is still listed; once
            // the photo has left the timeline its commit is refused and takes the plaintext with it.
            val active = cache.createOriginalTarget(userId, "active")
            active.plaintext.writeText("in progress")
            cache.reconcilePhotos(userId, emptyList(), listOf("active"))
            assertTrue(active.plaintext.exists())
            // A reconcile that changes nothing sweeps nothing; the removal has to be visible to it.
            cache.reconcilePhotos(userId, cachedNodeUids = listOf("active"), remoteNodeUids = emptyList())
            assertThrows(ProtonOriginalRemovedException::class.java) { cache.commitOriginal(userId, "active", active) }
            assertFalse(active.plaintext.exists())
            assertFalse(active.encrypted.exists())
            assertNull(cache.readOriginal(userId, "active"))

            val bounded = cache.createOriginalTarget(userId, "bounded")
            bounded.plaintext.writeText("decrypted private content")
            val (plaintext, encrypted) = bounded
            assertTrue(cache.commitOriginal(userId, "bounded", bounded).plaintext.isFile)
            // The download's own file moved to the shared plaintext path.
            assertFalse(plaintext.exists())
            // The decrypted copy expires after 30 minutes; the encrypted original stays until the
            // size cap or a disconnect removes it, so a later read simply decrypts it again.
            clock.value += 61L * 60L * 1_000L
            assertEquals("decrypted private content", cache.readOriginal(userId, "bounded")?.readText())
            assertTrue(encrypted.exists())
        } finally {
            cache.clearUser(userId)
            context.testRoot.deleteRecursively()
        }
    }

    @Test fun expiredDecryptedCopiesOfEveryUserAreSweptWhileFreshOnesAndOriginalsStay() {
        val context = isolatedContext()
        val clock = FakeClock(System.currentTimeMillis())
        val secureFiles = SecureFileStore(File(context.filesDir, "secure-keys"))
        val openCopies = ProtonDecryptedCopyRegistry()
        val store = ProtonOriginalStore(context, secureFiles, clock, openCopies)
        val staleUser = "stale-${UUID.randomUUID()}"
        val freshUser = "fresh-${UUID.randomUUID()}"
        try {
            val staleDownload = store.createTarget(staleUser, "old")
            staleDownload.plaintext.writeText("old plaintext")
            val stalePlaintext = store.commit(staleUser, "old", staleDownload).plaintext
            val staleEncrypted = staleDownload.encrypted
            clock.value += 31L * 60L * 1_000L
            val freshDownload = store.createTarget(freshUser, "new")
            freshDownload.plaintext.writeText("new plaintext")
            val freshPlaintext = store.commit(freshUser, "new", freshDownload).plaintext
            val freshEncrypted = freshDownload.encrypted

            // A copy a player holds open outlives its TTL until the reader closes it.
            val playing = ProtonOriginalStream(stalePlaintext, openCopies).apply { complete() }
            playing.readerOpened()
            store.sweepExpiredDecryptedCopies()
            assertTrue(stalePlaintext.exists())
            assertEquals(stalePlaintext, store.read(staleUser, "old"))
            playing.readerClosed()

            store.sweepExpiredDecryptedCopies()

            // Only the copy past its TTL goes, whichever user it belongs to; the fresh copy and
            // both encrypted originals stay, so the stale one simply decrypts again on read.
            assertFalse(stalePlaintext.exists())
            assertTrue(freshPlaintext.exists())
            assertTrue(staleEncrypted.exists())
            assertTrue(freshEncrypted.exists())
            assertEquals("old plaintext", store.read(staleUser, "old")?.readText())
        } finally {
            store.clear(staleUser)
            store.clear(freshUser)
            secureFiles.deleteKey(ProtonStorageLayout.mediaScope(staleUser))
            secureFiles.deleteKey(ProtonStorageLayout.mediaScope(freshUser))
            context.testRoot.deleteRecursively()
        }
    }

    @Test fun aDecryptThatOutlivesTheRemovalOfItsPhotoCommitsNothing() {
        val context = isolatedContext()
        val clock = FakeClock(System.currentTimeMillis())
        val secureFiles = SecureFileStore(File(context.filesDir, "secure-keys"))
        val store = ProtonOriginalStore(context, secureFiles, clock, ProtonDecryptedCopyRegistry())
        val userId = "trash-${UUID.randomUUID()}"
        try {
            val download = store.createTarget(userId, "video")
            download.plaintext.writeBytes(ByteArray(3 * 1_024 * 1_024) { position -> position.toByte() })
            val copy = store.commit(userId, "video", download).plaintext
            assertTrue(download.encrypted.isFile)
            // The copy expires, so the next read decrypts again; the photo is trashed while the
            // first segment is being written. The decrypt finishes, but nothing of it lands.
            clock.value += 31L * 60L * 1_000L
            var removedDuringDecrypt = false
            val result =
                store.materialize(
                    userId,
                    "video",
                    shouldContinue = { true },
                    onStarted = { _, _ -> },
                    onBytesWritten = { _ ->
                        if (!removedDuringDecrypt) {
                            removedDuringDecrypt = true
                            store.remove(userId, "video")
                        }
                    },
                )

            assertTrue(removedDuringDecrypt)
            assertNull(result)
            assertFalse(copy.exists())
            assertFalse(download.encrypted.isFile)
            assertEquals(
                emptyList<File>(),
                copy.parentFile
                    ?.listFiles()
                    ?.toList()
                    .orEmpty(),
            )
            assertNull(store.read(userId, "video"))
        } finally {
            store.clear(userId)
            secureFiles.deleteKey(ProtonStorageLayout.mediaScope(userId))
            context.testRoot.deleteRecursively()
        }
    }

    @Test fun thumbnailDatasetSurvivesMaintenanceAndAndroidCacheClearing() {
        val context = isolatedContext()
        val userId = "retention-${UUID.randomUUID()}"
        val clock = FakeClock(System.currentTimeMillis())
        val cache = createCache(context, SecureFileStore(File(context.filesDir, "secure-keys")), clock)
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

            // Hydration answers availability from one directory listing, not a probe per photo.
            cache.writeIndex(
                userId,
                listOf(
                    ProtonGalleryPhoto("old", 3L, false),
                    ProtonGalleryPhoto("current", 2L, false),
                    ProtonGalleryPhoto("missing", 1L, true),
                ),
            )
            assertEquals(
                listOf("old" to true, "current" to true, "missing" to false),
                cache.readTimelineSnapshot(userId)?.map { photo -> photo.nodeUid to photo.hasThumbnail },
            )
        } finally {
            cache.clearUser(userId)
            context.testRoot.deleteRecursively()
        }
    }

    @Test fun thumbnailValidationRequiresACompletePixelDecode() {
        val context = isolatedContext()
        val userId = "decode-${UUID.randomUUID()}"
        val nodeUid = "truncated"
        val secureFiles = SecureFileStore(File(context.filesDir, "secure-keys"))
        val cache = createCache(context, secureFiles, FakeClock(System.currentTimeMillis()))
        val truncatedThumbnail = validThumbnail().copyOf(33)
        val thumbnailFile =
            File(
                context.filesDir,
                "proton-photo-cache/${AtomicFileStore.safeName(userId)}/thumbnails/" +
                    "${AtomicFileStore.safeName(nodeUid)}.thumb",
            )
        try {
            assertFalse(
                runCatching {
                    cache.writeThumbnail(userId, nodeUid, truncatedThumbnail)
                }.isSuccess,
            )

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
        val secureFiles = SecureFileStore(File(context.filesDir, "secure-keys"))
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
        val cache =
            createCache(
                context,
                SecureFileStore(File(context.filesDir, "secure-keys")),
                FakeClock(System.currentTimeMillis()),
            )
        val entries =
            listOf(
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
            cache.writeQueue(userId, ProtonQueueName.THUMBNAILS, entries)
            cache.writeQueue(userId, ProtonQueueName.PREVIEWS, entries.take(1))

            assertEquals(entries, cache.readQueue(userId, ProtonQueueName.THUMBNAILS))
            assertEquals(entries.take(1), cache.readQueue(userId, ProtonQueueName.PREVIEWS))
        } finally {
            cache.clearUser(userId)
            context.testRoot.deleteRecursively()
        }
    }

    @Test fun tagIndexesAreEncryptedPersistedAndReconciledWithTheTimeline() {
        val context = isolatedContext()
        val userId = "tags-${UUID.randomUUID()}"
        val cache =
            createCache(
                context,
                SecureFileStore(File(context.filesDir, "secure-keys")),
                FakeClock(System.currentTimeMillis()),
            )
        val retained = ProtonGalleryPhoto("volume~retained", 200L, false)
        val removed = ProtonGalleryPhoto("volume~removed", 100L, false)
        try {
            cache.writeIndex(userId, listOf(retained, removed))
            cache.writeTag(userId, ProtonMediaTag.VIDEOS, listOf(retained, removed))

            assertNull(cache.readTagSnapshot(userId, ProtonMediaTag.FAVORITES))
            assertEquals(listOf(retained, removed), cache.readTagSnapshot(userId, ProtonMediaTag.VIDEOS))

            cache.reconcilePhotos(
                userId,
                cachedNodeUids = listOf(retained.nodeUid, removed.nodeUid),
                remoteNodeUids = listOf(retained.nodeUid),
            )

            assertEquals(listOf(retained), cache.readTagSnapshot(userId, ProtonMediaTag.VIDEOS))
        } finally {
            cache.clearUser(userId)
            context.testRoot.deleteRecursively()
        }
    }

    @Test fun anUnreadableAlbumListingKeepsEveryRenditionThroughTheReconcile() {
        val context = isolatedContext()
        val userId = "albums-${UUID.randomUUID()}"
        val cache =
            createCache(
                context,
                SecureFileStore(File(context.filesDir, "secure-keys")),
                FakeClock(System.currentTimeMillis()),
            )
        val albumsIndex =
            File(
                context.filesDir,
                "proton-photo-cache/${AtomicFileStore.safeName(userId)}/albums.json",
            )
        val inTimeline = ProtonGalleryPhoto("volume~timeline", 200L, false)
        val albumOnly = ProtonGalleryPhoto("volume~album-only", 100L, false)
        try {
            cache.writeIndex(userId, listOf(inTimeline, albumOnly))
            cache.writeAlbums(
                userId,
                listOf(ProtonAlbum("album", "Album", 1L, albumOnly.nodeUid, 1L, 1L, false, false)),
            )
            cache.writeThumbnail(userId, albumOnly.nodeUid, validThumbnail())
            cache.writeThumbnail(userId, inTimeline.nodeUid, validThumbnail())
            val bytes = albumsIndex.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            albumsIndex.writeBytes(bytes)

            // The album listing cannot be read, so nobody knows which photos the albums still
            // show: the timeline listing is what the sync rewrites, and no rendition goes.
            cache.reconcilePhotos(
                userId,
                cachedNodeUids = listOf(inTimeline.nodeUid, albumOnly.nodeUid),
                remoteNodeUids = listOf(inTimeline.nodeUid),
            )

            assertTrue(cache.thumbnailExists(userId, albumOnly.nodeUid))
            assertTrue(cache.thumbnailExists(userId, inTimeline.nodeUid))
            assertFalse(albumsIndex.exists())

            // With no album listing left at all there is nothing to keep the photo for.
            cache.reconcilePhotos(
                userId,
                cachedNodeUids = listOf(inTimeline.nodeUid, albumOnly.nodeUid),
                remoteNodeUids = listOf(inTimeline.nodeUid),
            )
            assertFalse(cache.thumbnailExists(userId, albumOnly.nodeUid))
        } finally {
            cache.clearUser(userId)
            context.testRoot.deleteRecursively()
        }
    }

    private fun validThumbnail(): ByteArray =
        ByteArrayOutputStream().use { output ->
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
    ): ProtonPhotoCache =
        ProtonPhotoCache(
            context,
            secureFiles,
            clock,
            ProtonThumbnailStore(context, secureFiles, clock),
            ProtonPreviewStore(context, secureFiles, clock),
            ProtonOriginalStore(context, secureFiles, clock, ProtonDecryptedCopyRegistry()),
        )

    private class IsolatedCacheContext(
        context: Context,
        val testRoot: File,
    ) : ContextWrapper(context) {
        private val testFiles = File(testRoot, "files").apply { mkdirs() }
        private val testCache = File(testRoot, "cache").apply { mkdirs() }

        override fun getFilesDir(): File = testFiles

        override fun getCacheDir(): File = testCache
    }

    private class FakeClock(
        var value: Long,
    ) : LenswaveClock {
        override fun nowMillis(): Long = value
    }
}
