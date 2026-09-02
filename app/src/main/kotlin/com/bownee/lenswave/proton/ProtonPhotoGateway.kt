package com.bownee.lenswave.proton

import java.io.File
import com.bownee.lenswave.gallery.ProtonDuplicateSource
import com.bownee.lenswave.gallery.ProtonGalleryReader
import com.bownee.lenswave.gallery.ProtonSessionLifecycle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.NodeResultPair
import me.proton.drive.sdk.entity.NodeUid

/**
 * Application gateway for Proton Photos capabilities. Focused repositories own synchronization,
 * albums, trash, and downloads; this class enforces the active-session boundary around them.
 */
@Singleton
class ProtonPhotoGateway @Inject internal constructor(
    private val timeline: ProtonTimelineRepository,
    private val albums: ProtonAlbumRepository,
    private val trash: ProtonTrashRepository,
    private val downloads: ProtonDownloadRepository,
    private val clientProvider: ProtonPhotosClientProvider,
    private val cache: ProtonSessionCache,
    private val sessionGuard: ProtonSessionGuard,
) : ProtonGalleryReader, ProtonSessionLifecycle, ProtonDuplicateSource {
    private val metadataSyncMutex = Mutex()
    private val mutableMetadataState = MutableStateFlow(ProtonMetadataState())

    override val state: StateFlow<ProtonGalleryState> = timeline.state
    override val metadataState: StateFlow<ProtonMetadataState> = mutableMetadataState.asStateFlow()
    override val albumsState: StateFlow<ProtonAlbumsState> = albums.albumsState
    override val albumPhotosState: StateFlow<ProtonAlbumPhotosState> = albums.albumPhotosState
    override val trashState: StateFlow<ProtonTrashState> = trash.state

    override suspend fun activate(userId: UserId) {
        withContext(Dispatchers.IO) { sessionGuard.activate(userId) { previousUserId ->
            previousUserId?.let { previous ->
                clientProvider.disconnect(previous)
                cache.clearUser(previous.id)
            }
            timeline.reset()
            albums.reset()
            trash.reset()
            timeline.loadCached(userId)
            albums.loadCached(userId)
            trash.loadCached(userId)
            mutableMetadataState.value = metadataSnapshot(userId, isLoading = false)
            cache.trimUser(userId.id)
        } }
    }

    override suspend fun syncMetadata(userId: UserId, forceRemote: Boolean) {
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) {
                metadataSyncMutex.withLock {
                    updateThumbnailWorkStatus(null)
                    mutableMetadataState.value = metadataSnapshot(userId, isLoading = true)
                    try {
                        coroutineScope {
                            listOf(
                                async { timeline.syncMetadata(userId, forceRemote) },
                                async { albums.syncMetadata(userId, forceRemote) },
                                async { trash.syncMetadata(userId, forceRemote) },
                            ).awaitAll()
                        }
                    } finally {
                        mutableMetadataState.value = metadataSnapshot(userId, isLoading = false)
                    }
                }
            }
        }
    }

    override suspend fun loadCachedAlbum(userId: UserId, album: ProtonAlbumReference) {
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) { albums.loadCachedAlbum(userId, album) }
        }
    }

    override suspend fun syncAlbumPhotoMetadata(
        userId: UserId,
        album: ProtonAlbumReference,
        forceRemote: Boolean,
    ) {
        withContext(Dispatchers.IO) { sessionGuard.withActiveSession(userId) {
            albums.syncAlbumPhotoMetadata(userId, album, forceRemote)
        } }
    }

    override suspend fun hydrateAlbumPhotoThumbnails(userId: UserId, album: ProtonAlbumReference) {
        withContext(Dispatchers.IO) { sessionGuard.withActiveSession(userId) {
            albums.hydrateAlbumPhotoThumbnails(userId, album)
        } }
    }

    internal suspend fun hydrateThumbnails(
        userId: UserId,
        timelineLimit: Int,
        albumCoverLimit: Int,
        trashLimit: Int,
    ) {
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) {
                val failures = mutableListOf<Throwable>()
                suspend fun attempt(block: suspend () -> Unit) {
                    try {
                        block()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        failures += error
                    }
                }
                attempt { timeline.hydrateThumbnails(userId, timelineLimit) }
                attempt { albums.hydrateCoverThumbnails(userId, albumCoverLimit) }
                attempt { trash.hydrateThumbnails(userId, trashLimit) }
                failures.firstOrNull()?.let { throw it }
            }
        }
    }

    suspend fun downloadOriginal(userId: UserId, nodeUid: String): File =
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) { downloads.downloadOriginal(userId, nodeUid) }
        }

    override suspend fun getOriginalFileName(userId: UserId, nodeUid: String): String? =
        withContext(Dispatchers.IO) {
            sessionGuard.withActiveSession(userId) { downloads.getOriginalFileName(userId, nodeUid) }
        }

    fun readThumbnail(userId: UserId, nodeUid: String): ByteArray? =
        downloads.readThumbnail(userId, nodeUid)

    override suspend fun findPhotoDuplicates(
        userId: UserId,
        name: String,
        generateSha1: suspend () -> ByteArray,
    ): List<String> = withContext(Dispatchers.IO) {
        sessionGuard.withActiveSession(userId) {
            downloads.findPhotoDuplicates(userId, name, generateSha1)
        }
    }

    suspend fun trashPhotos(userId: UserId, nodeUids: Collection<String>): ProtonTrashResult {
        return withContext(Dispatchers.IO) { sessionGuard.withActiveSession(userId) {
            val requested = nodeUids.distinct()
            if (requested.isEmpty()) return@withActiveSession ProtonTrashResult()
            val results = clientProvider.get(userId).trashNodes(requested.map(::NodeUid)).toList()
            val successful = results.filterIsInstance<NodeResultPair.Success>()
                .map { it.nodeUid.value }
                .toSet()
            if (successful.isNotEmpty()) {
                timeline.removePhotos(userId, successful)
                albums.removePhotos(userId, successful)
                cache.writeLastSuccessfulSync(userId.id, TRASH_SYNC_KEY, 0L)
            }
            ProtonTrashResult(
                trashedCount = successful.size,
                failedCount = results.count { it is NodeResultPair.Failure },
            )
        } }
    }

    suspend fun deletePhotosPermanently(
        userId: UserId,
        nodeUids: Collection<String>,
    ): ProtonDeleteResult = withContext(Dispatchers.IO) {
        sessionGuard.withActiveSession(userId) { trash.deletePermanently(userId, nodeUids) }
    }

    override suspend fun disconnect(userId: UserId) {
        withContext(Dispatchers.IO) { sessionGuard.disconnect(userId) { wasActive ->
            clientProvider.disconnect(userId)
            cache.clearUser(userId.id)
            if (wasActive) {
                timeline.reset()
                albums.reset()
                trash.reset()
                mutableMetadataState.value = ProtonMetadataState()
            }
        } }
    }

    fun isActive(userId: UserId): Boolean = sessionGuard.isActive(userId)

    internal fun updateThumbnailWorkStatus(status: ProtonThumbnailWorkStatus?) {
        timeline.updateThumbnailWorkStatus(status)
    }

    internal fun hasCompleteMetadata(userId: UserId): Boolean =
        metadataState.value.userId == userId.id && metadataState.value.hasLoaded

    private fun metadataSnapshot(userId: UserId, isLoading: Boolean): ProtonMetadataState {
        val timelineState = timeline.state.value
        val albumsState = albums.albumsState.value
        val trashState = trash.state.value
        val hasLoaded = timelineState.userId == userId.id && timelineState.hasLoaded &&
            albumsState.userId == userId.id && albumsState.hasLoaded &&
            trashState.userId == userId.id && trashState.hasLoaded
        return ProtonMetadataState(
            userId = userId.id,
            isLoading = isLoading,
            hasLoaded = hasLoaded,
            errorMessage = listOfNotNull(
                timelineState.errorMessage,
                albumsState.errorMessage,
                trashState.errorMessage,
            ).firstOrNull(),
        )
    }

    private companion object {
        const val TRASH_SYNC_KEY = "trash"
    }
}
