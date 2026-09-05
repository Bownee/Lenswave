package com.bownee.lenswave.viewer

import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.GalleryAssetMemo
import com.bownee.lenswave.gallery.GalleryDestination
import com.bownee.lenswave.proton.ProtonAccountSessionManager
import com.bownee.lenswave.proton.ProtonAccountSessionState
import com.bownee.lenswave.proton.ProtonGalleryReader
import com.bownee.lenswave.proton.ProtonSessionChangedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rebuilds the gallery list a viewer was opened from after a process death, when the in-process
 * [PhotoNavigationSources] entry is gone. The destination names the page; its photos come from
 * the reader's cache (loaded here if the gallery has not loaded them yet, since after a process
 * death the viewer is restored before the gallery) and are ordered exactly as the gallery
 * orders them, so the viewer's window lines up with what the grid would show.
 */
@Singleton
class PhotoNavigationSourceProvider internal constructor(
    private val reader: ProtonGalleryReader,
    private val accountSession: StateFlow<ProtonAccountSessionState>,
) {
    @Inject
    internal constructor(
        reader: ProtonGalleryReader,
        accountSessionManager: ProtonAccountSessionManager,
    ) : this(reader, accountSessionManager.state)

    private val memo = GalleryAssetMemo()

    /** The page's photos, newest first; null when the page cannot be rebuilt for [userId]. */
    internal suspend fun load(
        destination: GalleryDestination,
        userId: UserId,
    ): List<GalleryAsset>? {
        if (destination == GalleryDestination.Library) return null
        if (!awaitSession(userId)) return null
        return try {
            when (destination) {
                GalleryDestination.Timeline -> {
                    if (!timelineLoaded(userId)) reader.syncTimelineMetadata(userId)
                    val state = reader.state.value
                    memo.photos(state.photos, memo.tagIndex(state.tags)).assets
                }

                is GalleryDestination.Tag -> {
                    // As the gallery does: the tag's photos hang off the timeline's cache.
                    if (!timelineLoaded(userId)) reader.syncTimelineMetadata(userId)
                    val tagLoaded =
                        reader.state.value.tags[destination.tag]
                            ?.hasLoaded == true
                    if (!tagLoaded) reader.syncTagMetadata(userId, destination.tag)
                    val state = reader.state.value
                    val tagState = state.tags[destination.tag] ?: return null
                    memo.photos(tagState.photos, memo.tagIndex(state.tags)).assets
                }

                is GalleryDestination.AlbumPhotos -> {
                    val cached = reader.albumPhotosState.value
                    val albumLoaded =
                        cached.userId == userId.id && cached.albumUid == destination.album.nodeUid && cached.hasLoaded
                    if (!albumLoaded) reader.loadCachedAlbum(userId, destination.album)
                    val state = reader.albumPhotosState.value
                    if (state.albumUid != destination.album.nodeUid) return null
                    memo.photos(state.photos, memo.tagIndex(reader.state.value.tags)).assets
                }

                GalleryDestination.Library -> {
                    null
                }
            }
        } catch (_: ProtonSessionChangedException) {
            // The account went away underneath the read; a cancellation subtype, but not ours.
            null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    private fun timelineLoaded(userId: UserId): Boolean {
        val state = reader.state.value
        return state.userId == userId.id && state.hasLoaded
    }

    /** The reader needs the account's session active; after a process death that takes a moment. */
    private suspend fun awaitSession(userId: UserId): Boolean =
        withTimeoutOrNull(SESSION_WAIT_MILLIS) {
            accountSession.first { state -> state.initialized && !state.transitioning && state.activeUserId == userId }
        } != null

    private companion object {
        const val SESSION_WAIT_MILLIS = 10_000L
    }
}
