package com.bownee.lenswave.gallery

import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.LenswaveDispatchers
import com.bownee.lenswave.proton.ProtonGalleryPhoto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedPhotoRepositoryTest {
    @Test
    fun clearWaitsForAnActiveResolutionAndRunsAfterItsFinalWrite() = runBlocking {
        val duplicateStarted = CompletableDeferred<Unit>()
        val releaseDuplicate = CompletableDeferred<Unit>()
        val store = FakeStore()
        val repository = repository(store, duplicateStarted, releaseDuplicate)

        val resolution = async {
            repository.resolveMatches(USER, listOf(DEVICE_PHOTO), listOf(PROTON_PHOTO), false) { }
        }
        duplicateStarted.await()
        val clear = async { repository.clear(USER) }
        assertFalse(clear.isCompleted)

        releaseDuplicate.complete(Unit)
        resolution.await()
        clear.await()

        assertEquals(listOf("write", "clear"), store.events.filter { it == "write" || it == "clear" })
    }

    @Test
    fun cancellationPreventsCheckpointAndSnapshotWritesBeforeClear() = runBlocking {
        val duplicateStarted = CompletableDeferred<Unit>()
        val releaseDuplicate = CompletableDeferred<Unit>()
        val store = FakeStore()
        val repository = repository(store, duplicateStarted, releaseDuplicate)
        val resolution = async {
            repository.resolveMatches(USER, listOf(DEVICE_PHOTO), listOf(PROTON_PHOTO), false) { }
        }
        duplicateStarted.await()

        resolution.cancelAndJoin()
        repository.clear(USER)

        assertTrue("write" !in store.events)
        assertTrue("append" !in store.events)
        assertEquals("clear", store.events.last())
    }

    private fun repository(
        store: FakeStore,
        duplicateStarted: CompletableDeferred<Unit>,
        releaseDuplicate: CompletableDeferred<Unit>,
    ) = CombinedPhotoRepository(
        deviceRepository = FakeDeviceSource(),
        protonRepository = object : ProtonDuplicateSource {
            override suspend fun getOriginalFileName(userId: UserId, nodeUid: String) = null

            override suspend fun findPhotoDuplicates(
                userId: UserId,
                name: String,
                generateSha1: suspend () -> ByteArray,
            ): List<String> {
                duplicateStarted.complete(Unit)
                releaseDuplicate.await()
                generateSha1()
                return listOf(PROTON_PHOTO.nodeUid)
            }
        },
        cache = store,
        dispatchers = object : LenswaveDispatchers {
            override val io = Dispatchers.Default
            override val computation = Dispatchers.Default
        },
        clock = object : LenswaveClock {
            override fun nowMillis() = 1_000L
        },
    )

    private class FakeDeviceSource : DevicePhotoSource {
        override suspend fun loadPhotos() = listOf(DEVICE_PHOTO)
        override suspend fun loadTrashedPhotos() = emptyList<GalleryAsset>()
        override suspend fun calculateSha1(photo: GalleryAsset) = byteArrayOf(1, 2, 3)
    }

    private class FakeStore : CombinedMatchStore {
        val events = mutableListOf<String>()
        override fun read(userId: String) = CombinedMatchSnapshot()
        override fun write(userId: String, snapshot: CombinedMatchSnapshot) {
            events += "write"
        }
        override fun append(
            userId: String,
            timelineFingerprint: String,
            records: Collection<DevicePhotoMatchRecord>,
        ) {
            events += "append"
        }
        override fun clear(userId: String) {
            events += "clear"
        }
    }

    private companion object {
        val USER = UserId("user")
        val DEVICE_PHOTO = GalleryAsset.device(
            stableId = "device:1",
            capturedAtEpochMillis = 1_000,
            displayName = "IMG.jpg",
            uri = "content://device/1",
            collection = DeviceCollection.CAMERA,
            sizeBytes = 100,
            modifiedAtEpochMillis = 2_000,
        )
        val PROTON_PHOTO = ProtonGalleryPhoto("node", 1, true)
    }
}
