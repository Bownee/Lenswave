package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveOperation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ProtonTimelineRepository
    @Inject
    constructor(
        private val clientProvider: ProtonPhotosClientProvider,
        private val cache: ProtonTimelineCache,
        private val snapshotSync: ProtonSnapshotSync,
        private val tagListings: ProtonTagListingClient,
    ) {
        private val syncMutex = Mutex()
        private val tagMutexes = ProtonMediaTag.entries.associateWith { Mutex() }

        /**
         * Serializes every write to the cached listings and to the state that mirrors them: a
         * sync commit (with its stamp and publish), a favourite toggle and a removal. It is the
         * innermost lock of the hierarchy, taken after [syncMutex] or a tag mutex and never held
         * across an enumeration, so a trash never waits on a network round trip and a favourites
         * sync that enumerated before a trash cannot publish or persist the trashed photo again.
         */
        private val mutationMutex = Mutex()
        private val mutableState = MutableStateFlow(ProtonGalleryState())

        val state: StateFlow<ProtonGalleryState> = mutableState.asStateFlow()

        /**
         * Publishes the cached timeline and tag listings. A library up to
         * [TAGS_WITH_FIRST_PUBLISH_LIMIT] photos hydrates its tags first and publishes once, so
         * re-activating never blanks the Videos and Favorites tabs; the tag files are a fraction
         * of the timeline there. A larger library publishes the grid as soon as the timeline is
         * parsed and fills the tabs right after, keeping whatever this user had published in the
         * meantime rather than an empty map.
         */
        fun loadCached(userId: UserId) {
            val availability = cache.storedRenditions(userId.id)
            val timeline = cache.readTimelineSnapshot(userId.id, availability)
            val photos = timeline.orEmpty()
            val tagsFirst = photos.size <= TAGS_WITH_FIRST_PUBLISH_LIMIT
            emit(
                userId = userId,
                photos = photos,
                hasLoaded = timeline != null,
                syncing = false,
                tags = if (tagsFirst) readTagStates(userId, availability) else previousTags(userId),
            )
            if (tagsFirst) return
            val tagStates = readTagStates(userId, availability)
            mutableState.update { state -> if (state.userId == userId.id) state.copy(tags = tagStates) else state }
        }

        private fun readTagStates(
            userId: UserId,
            availability: ProtonStoredRenditions,
        ): Map<ProtonMediaTag, ProtonTagState> =
            ProtonMediaTag.entries
                .mapNotNull { tag ->
                    cache.readTagSnapshot(userId.id, tag, availability)?.let { photos ->
                        tag to ProtonTagState(photos = photos, hasLoaded = true)
                    }
                }.toMap()

        private fun previousTags(userId: UserId): Map<ProtonMediaTag, ProtonTagState> =
            mutableState.value
                .takeIf { state -> state.userId == userId.id }
                ?.tags
                .orEmpty()

        suspend fun syncMetadata(
            userId: UserId,
            forceRemote: Boolean,
        ) = syncMutex.withLock {
            // The published timeline is the cached one plus every mark since, so a loaded state
            // answers "what is cached" without decrypting and parsing the index again, which the
            // sync policy then often decides was fresh anyway.
            val current = mutableState.value
            val (existing, hasCachedSnapshot) =
                if (current.userId == userId.id && current.hasLoaded) {
                    current.photos to true
                } else {
                    val cached = cache.readTimelineSnapshot(userId.id)
                    cached.orEmpty() to (cached != null)
                }
            snapshotSync.sync(
                userId = userId.id,
                source = ProtonSyncSource.TIMELINE,
                syncKey = ProtonSyncKeys.TIMELINE,
                forceRemote = forceRemote,
                hasSnapshot = hasCachedSnapshot,
                operation = LenswaveOperation.TIMELINE_SYNC.tag,
                publishFresh = { emit(userId, existing, hasLoaded = true, syncing = false) },
                publishSyncing = { emit(userId, existing, hasLoaded = hasCachedSnapshot, syncing = true) },
                enumerate = {
                    val items = clientProvider.get(userId).enumerateTimeline().toList()
                    val availability = cache.storedRenditions(userId.id)
                    items.map { item -> availability.photo(item.nodeUid.value, item.captureTime.epochSecond) }
                },
                commit = { photos ->
                    cache.reconcilePhotos(
                        userId = userId.id,
                        cachedNodeUids = existing.map(ProtonGalleryPhoto::nodeUid),
                        remoteNodeUids = photos.map(ProtonGalleryPhoto::nodeUid),
                    )
                    cache.writeIndex(userId.id, photos)
                    photos
                },
                publishResult = { photos ->
                    val remoteNodeUids = photos.mapTo(mutableSetOf(), ProtonGalleryPhoto::nodeUid)
                    val reconciledTags =
                        mutableState.value.tags.mapValues { (_, tagState) ->
                            tagState.copy(photos = tagState.photos.filter { it.nodeUid in remoteNodeUids })
                        }
                    emit(userId, photos, hasLoaded = true, syncing = false, tags = reconciledTags)
                },
                publishCancelled = { mutableState.value = mutableState.value.copy(syncing = false) },
                publishFailed = {
                    mutableState.value =
                        mutableState.value.copy(
                            syncing = false,
                            refreshFailed = true,
                        )
                },
                commitGate = { commit -> mutationMutex.withLock { commit() } },
            )
        }

        suspend fun syncTagMetadata(
            userId: UserId,
            tag: ProtonMediaTag,
            forceRemote: Boolean,
        ) = tagMutexes.getValue(tag).withLock {
            val current =
                mutableState.value
                    .takeIf { state -> state.userId == userId.id }
                    ?.tags
                    ?.get(tag)
            val (existing, hasCachedSnapshot) =
                if (current?.hasLoaded == true) {
                    current.photos to true
                } else {
                    val cached = cache.readTagSnapshot(userId.id, tag)
                    cached.orEmpty() to (cached != null)
                }
            snapshotSync.sync(
                userId = userId.id,
                source = ProtonSyncSource.TIMELINE,
                syncKey = ProtonSyncKeys.timelineTag(tag),
                forceRemote = forceRemote,
                hasSnapshot = hasCachedSnapshot,
                operation = "tag-sync-${tag.name.lowercase()}",
                publishFresh = { updateTag(tag, ProtonTagState(existing, hasLoaded = true)) },
                publishSyncing = {
                    updateTag(tag, ProtonTagState(existing, hasLoaded = hasCachedSnapshot, syncing = true))
                },
                enumerate = { listTag(userId, tag, existing) },
                commit = { photos ->
                    val retained = retainTimelinePhotos(userId, photos)
                    cache.writeTag(userId.id, tag, retained)
                    retained
                },
                publishResult = { photos -> updateTag(tag, ProtonTagState(photos, hasLoaded = true)) },
                publishCancelled = {
                    updateTag(
                        tag,
                        mutableState.value.tags[tag]?.copy(syncing = false) ?: ProtonTagState(),
                    )
                },
                // Unlike the other listings, a failed tag sync republishes the cached photos rather
                // than copying whatever tag state is currently published.
                publishFailed = {
                    updateTag(
                        tag,
                        ProtonTagState(
                            photos = existing,
                            hasLoaded = hasCachedSnapshot,
                            refreshFailed = true,
                        ),
                    )
                },
                commitGate = { commit -> mutationMutex.withLock { commit() } },
            )
        }

        /**
         * A tag listing narrowed to the photos the published timeline still has. The listing was
         * enumerated outside [mutationMutex], so a photo trashed in the meantime may still be in
         * it; committing that would bring the photo back into the tag file and the tab. A timeline
         * that has not loaded cannot judge, and the listing is kept whole.
         */
        private fun retainTimelinePhotos(
            userId: UserId,
            photos: List<ProtonGalleryPhoto>,
        ): List<ProtonGalleryPhoto> {
            val current = mutableState.value
            if (current.userId != userId.id || !current.hasLoaded) return photos
            val timelineNodeUids =
                current.photos.mapTo(
                    HashSet(current.photos.size * 4 / 3 + 1),
                    ProtonGalleryPhoto::nodeUid,
                )
            return photos.filter { photo -> photo.nodeUid in timelineNodeUids }
        }

        private suspend fun listTag(
            userId: UserId,
            tag: ProtonMediaTag,
            existing: List<ProtonGalleryPhoto>,
        ): List<ProtonGalleryPhoto> {
            val volumeId = volumeId(existing) ?: volumeId(mutableState.value.photos)
            if (volumeId == null && mutableState.value.hasLoaded && mutableState.value.photos.isEmpty()) {
                return emptyList()
            }
            val listed =
                tagListings.list(
                    userId,
                    requireNotNull(volumeId) { "Cannot determine the Proton Photos volume" },
                    tag,
                )
            val availability = cache.storedRenditions(userId.id)
            return listed.map { photo -> photo.copy(hasThumbnail = availability.hasThumbnail(photo.nodeUid)) }
        }

        internal fun markThumbnailsAvailable(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            markThumbnails(userId, nodeUids, available = true)
        }

        internal fun markThumbnailsUnavailable(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            markThumbnails(userId, nodeUids, available = false)
        }

        private fun markThumbnails(
            userId: UserId,
            nodeUids: Set<String>,
            available: Boolean,
        ) {
            if (nodeUids.isEmpty()) return
            mutableState.update { state ->
                if (state.userId != userId.id) return@update state
                val photos = state.photos.withThumbnailAvailability(nodeUids, available)
                // Tag listings that did not change keep their instance, so the tabs' memos hold.
                var tagsChanged = false
                val tags =
                    state.tags.mapValues { (_, tagState) ->
                        tagState.photos.withThumbnailAvailability(nodeUids, available)?.let { updated ->
                            tagsChanged = true
                            tagState.copy(photos = updated)
                        } ?: tagState
                    }
                if (photos == null && !tagsChanged) return@update state
                state.copy(photos = photos ?: state.photos, tags = if (tagsChanged) tags else state.tags)
            }
        }

        private fun List<ProtonGalleryPhoto>.withThumbnailAvailability(
            nodeUids: Set<String>,
            available: Boolean,
        ): List<ProtonGalleryPhoto>? =
            withThumbnailAvailability(
                nodeUids,
                available,
                nodeUid = ProtonGalleryPhoto::nodeUid,
                hasThumbnail = ProtonGalleryPhoto::hasThumbnail,
                copy = { photo, hasThumbnail -> photo.copy(hasThumbnail = hasThumbnail) },
            )

        /** Previews only matter to the timeline queue, so tag listings are left untouched. */
        internal fun markPreviewsAvailable(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            if (nodeUids.isEmpty()) return
            mutableState.update { state ->
                if (state.userId != userId.id) return@update state
                val photos =
                    state.photos.withThumbnailAvailability(
                        nodeUids,
                        available = true,
                        nodeUid = ProtonGalleryPhoto::nodeUid,
                        hasThumbnail = ProtonGalleryPhoto::hasPreview,
                        copy = { photo, hasPreview -> photo.copy(hasPreview = hasPreview) },
                    ) ?: return@update state
                state.copy(photos = photos)
            }
        }

        internal fun setFavorite(
            userId: UserId,
            nodeUids: Set<String>,
            favorite: Boolean,
        ) {
            if (nodeUids.isEmpty() || mutableState.value.userId != userId.id) return
            val current = mutableState.value
            val currentFavoriteState = current.tags[ProtonMediaTag.FAVORITES]
            val currentFavorites = currentFavoriteState?.photos.orEmpty()
            val nextFavorites =
                if (favorite) {
                    val knownPhotos = current.photos.associateBy(ProtonGalleryPhoto::nodeUid)
                    (
                        currentFavorites +
                            nodeUids.map { nodeUid ->
                                knownPhotos[nodeUid] ?: ProtonGalleryPhoto(
                                    nodeUid = nodeUid,
                                    captureTimeEpochSeconds = 0L,
                                    hasThumbnail = cache.thumbnailExists(userId.id, nodeUid),
                                )
                            }
                    ).distinctBy(ProtonGalleryPhoto::nodeUid)
                        .sortedByDescending(ProtonGalleryPhoto::captureTimeEpochSeconds)
                } else {
                    currentFavorites.filterNot { it.nodeUid in nodeUids }
                }
            if (currentFavoriteState?.hasLoaded == true) {
                cache.writeTag(userId.id, ProtonMediaTag.FAVORITES, nextFavorites)
            }
            updateTag(
                ProtonMediaTag.FAVORITES,
                ProtonTagState(
                    photos = nextFavorites,
                    hasLoaded = currentFavoriteState?.hasLoaded == true,
                    refreshFailed = currentFavoriteState?.refreshFailed == true,
                ),
            )
        }

        internal suspend fun removePhotos(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            if (nodeUids.isEmpty()) return
            // The sync mutex keeps a timeline enumeration from committing a listing that still
            // has the photos; the mutation mutex does the same for a tag enumeration in flight.
            syncMutex.withLock {
                mutationMutex.withLock {
                    cache.removePhotos(userId.id, nodeUids)
                    mutableState.value =
                        mutableState.value.copy(
                            tags =
                                mutableState.value.tags.mapValues { (_, tagState) ->
                                    tagState.copy(photos = tagState.photos.filterNot { it.nodeUid in nodeUids })
                                },
                        )
                    emit(
                        userId,
                        mutableState.value.photos.filterNot { it.nodeUid in nodeUids },
                        hasLoaded = true,
                        syncing = false,
                    )
                }
            }
        }

        internal fun reset() {
            mutableState.value = ProtonGalleryState()
        }

        /**
         * Publishes [photos] as the timeline. The published list keeps its previous instance
         * whenever the content is unchanged: the gallery memoizes its assets and its uid index by
         * list identity, so a syncing heartbeat that re-published an equal copy made the Activity
         * compare the whole library on the main thread. Callers hand over lists they built
         * themselves, so no defensive copy is taken. [tags] null keeps the published tag map.
         */
        private fun emit(
            userId: UserId,
            photos: List<ProtonGalleryPhoto>,
            hasLoaded: Boolean,
            syncing: Boolean,
            tags: Map<ProtonMediaTag, ProtonTagState>? = null,
        ) {
            mutableState.update { previous ->
                ProtonGalleryState(
                    userId = userId.id,
                    photos = if (previous.photos == photos) previous.photos else photos,
                    hasLoaded = hasLoaded,
                    syncing = syncing,
                    tags = tags ?: previous.tags,
                )
            }
        }

        private fun updateTag(
            tag: ProtonMediaTag,
            state: ProtonTagState,
        ) {
            mutableState.update { gallery -> gallery.copy(tags = gallery.tags + (tag to state)) }
        }

        private fun volumeId(photos: List<ProtonGalleryPhoto>): String? =
            photos
                .firstOrNull()
                ?.nodeUid
                ?.substringBefore('~')
                ?.takeIf(String::isNotBlank)

        private companion object {
            /** Below this the tag files parse in a few milliseconds, so the first publish can wait for them. */
            const val TAGS_WITH_FIRST_PUBLISH_LIMIT = 5_000
        }
    }
