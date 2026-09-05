package com.bownee.lenswave.proton

import android.graphics.Bitmap
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File

/**
 * Snapshot readers return null when no valid listing is stored, so one read answers both "what
 * is cached" and "is anything cached"; a corrupt file is discarded and reads as absent, while a
 * file that merely could not be read right now (a transient crypto or I/O failure) is kept and
 * reads as absent for this call only.
 * Every hydrated photo carries its rendition availability from [storedRenditions], which is
 * memoized per user until a rendition changes; callers that already hold one pass it explicitly.
 */
internal interface ProtonTimelineCache {
    /** Memoized per user; a fresh listing only after a rendition was written, removed or swept. */
    fun storedRenditions(userId: String): ProtonStoredRenditions

    fun readTimelineSnapshot(
        userId: String,
        availability: ProtonStoredRenditions = storedRenditions(userId),
    ): List<ProtonGalleryPhoto>?

    fun writeIndex(
        userId: String,
        photos: List<ProtonGalleryPhoto>,
    )

    fun readTagSnapshot(
        userId: String,
        tag: ProtonMediaTag,
        availability: ProtonStoredRenditions = storedRenditions(userId),
    ): List<ProtonGalleryPhoto>?

    fun writeTag(
        userId: String,
        tag: ProtonMediaTag,
        photos: List<ProtonGalleryPhoto>,
    )

    fun thumbnailExists(
        userId: String,
        nodeUid: String,
    ): Boolean

    /** Drops cached media and tag entries for nodes that vanished from the remote timeline. */
    fun reconcilePhotos(
        userId: String,
        cachedNodeUids: Collection<String>,
        remoteNodeUids: Collection<String>,
    )

    /** Drops [nodeUids] from the timeline and tag indexes and deletes their renditions; album indexes are [ProtonAlbumCache]'s. */
    fun removePhotos(
        userId: String,
        nodeUids: Collection<String>,
    )
}

internal interface ProtonAlbumCache {
    fun storedRenditions(userId: String): ProtonStoredRenditions

    fun readAlbumsSnapshot(
        userId: String,
        availability: ProtonStoredRenditions = storedRenditions(userId),
    ): List<ProtonAlbum>?

    fun writeAlbums(
        userId: String,
        albums: List<ProtonAlbum>,
    )

    fun readAlbumPhotosSnapshot(
        userId: String,
        albumUid: String,
        availability: ProtonStoredRenditions = storedRenditions(userId),
    ): List<ProtonGalleryPhoto>?

    fun writeAlbumPhotos(
        userId: String,
        albumUid: String,
        photos: List<ProtonGalleryPhoto>,
    )

    fun reconcileAlbums(
        userId: String,
        remoteAlbumUids: Collection<String>,
    )

    /** Drops [nodeUids] from every album-photo index and refreshes the counts in the albums index. */
    fun removeAlbumPhotos(
        userId: String,
        nodeUids: Collection<String>,
    )

    fun thumbnailExists(
        userId: String,
        nodeUid: String,
    ): Boolean
}

interface ProtonMediaCache {
    /** A cheap file stat; use it to skip work, never as proof that the bytes decode. */
    fun thumbnailExists(
        userId: String,
        nodeUid: String,
    ): Boolean

    /** [isActive] false aborts the load with a CancellationException before the costly decode. */
    fun loadThumbnail(
        userId: String,
        nodeUid: String,
        isActive: () -> Boolean = { true },
    ): Bitmap?

    /** In-memory thumbnail only; never reads disk, so it is safe on the main thread. */
    fun peekThumbnail(
        userId: String,
        nodeUid: String,
    ): Bitmap?

    fun writeThumbnail(
        userId: String,
        nodeUid: String,
        bytes: ByteArray,
    )

    fun removeThumbnail(
        userId: String,
        nodeUid: String,
    )

    fun thumbnailCount(userId: String): Int

    /** The stored thumbnail exactly as Proton delivered it; null when absent or unreadable. */
    fun readThumbnailBytes(
        userId: String,
        nodeUid: String,
    ): ByteArray?

    fun previewExists(
        userId: String,
        nodeUid: String,
    ): Boolean

    /** Stores a preview exactly as delivered; throws when the bytes are not a decodable image. */
    fun writePreview(
        userId: String,
        nodeUid: String,
        bytes: ByteArray,
    )

    /** Decodes the stored preview so its longer edge covers [targetLongEdge] pixels; null when absent. */
    fun loadPreview(
        userId: String,
        nodeUid: String,
        targetLongEdge: Int,
    ): Bitmap?

    fun removePreview(
        userId: String,
        nodeUid: String,
    )

    fun previewCount(userId: String): Int

    /**
     * The cached original decrypted to a plaintext file; null when not cached. [shouldContinue]
     * is asked between decrypt chunks and a false answer aborts with a cancellation exception.
     */
    fun readOriginal(
        userId: String,
        nodeUid: String,
        shouldContinue: () -> Boolean = { true },
    ): File?

    /** A private plaintext file for a download to write and the encrypted original it commits to; see [ProtonOriginalTarget]. */
    fun createOriginalTarget(
        userId: String,
        nodeUid: String,
    ): ProtonOriginalTarget

    /**
     * Encrypts the download into its original and moves the plaintext to the shared path; see
     * [ProtonOriginalCommit] for what the viewer reads and whether the original landed. Throws
     * [ProtonOriginalRemovedException], with the plaintext deleted, when the photo was removed
     * since [createOriginalTarget]; a cache that cannot keep the original reports that itself.
     */
    fun commitOriginal(
        userId: String,
        nodeUid: String,
        download: ProtonOriginalTarget,
    ): ProtonOriginalCommit

    /** Accounts for an original [commitOriginal] stored, trimming the oldest past the size cap. */
    fun onOriginalStored(
        userId: String,
        target: File,
    )
}

interface ProtonSessionCache {
    /** Never throws: files that resist deletion are reported and swept on the next account transition. */
    fun clearUser(userId: String)

    /** Expiry and size-cap housekeeping; safe to run while the session is in use. */
    fun trimUser(userId: String)

    /** Deletes every plaintext copy past its TTL, for any user; run when the app leaves the screen. */
    fun sweepExpiredDecryptedCopies()
}

/** Persistence for the download queues; each [ProtonQueueName] maps to its own file per user. */
internal interface ProtonThumbnailQueueStore {
    fun readQueue(
        userId: String,
        queue: ProtonQueueName,
    ): List<ProtonThumbnailQueueEntry>

    fun writeQueue(
        userId: String,
        queue: ProtonQueueName,
        entries: List<ProtonThumbnailQueueEntry>,
    )
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonCacheModule {
    @Binds abstract fun bindTimelineCache(implementation: ProtonPhotoCache): ProtonTimelineCache

    @Binds abstract fun bindAlbumCache(implementation: ProtonPhotoCache): ProtonAlbumCache

    @Binds abstract fun bindMediaCache(implementation: ProtonPhotoCache): ProtonMediaCache

    @Binds abstract fun bindSessionCache(implementation: ProtonPhotoCache): ProtonSessionCache

    @Binds abstract fun bindThumbnailQueueStore(implementation: ProtonPhotoCache): ProtonThumbnailQueueStore
}
