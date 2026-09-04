package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveOperation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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
            val tagStates = if (tagsFirst) readTagStates(userId, availability) else null
            emit(userId = userId, photos = photos, hasLoaded = timeline != null, syncing = false) { previous ->
                tagStates ?: if (previous.userId == userId.id) previous.tags else emptyMap()
            }
            if (tagsFirst) return
            val lateTagStates = readTagStates(userId, availability)
            updateState(userId) { state -> state.copy(tags = lateTagStates) }
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
                    val remoteNodeUids = photos.map(ProtonGalleryPhoto::nodeUid)
                    val remote = remoteNodeUids.toHashSet()
                    val removedCount = existing.count { photo -> photo.nodeUid !in remote }
                    // Thrown from the commit, the refusal is reported and published as a failed
                    // refresh like any other, which is what offers the manual refresh.
                    if (!ProtonReconcileSafetyPolicy.mayCommit(existing.size, removedCount, forceRemote)) {
                        throw ProtonSuspiciousListingException(existing.size, removedCount)
                    }
                    cache.reconcilePhotos(
                        userId = userId.id,
                        cachedNodeUids = existing.map(ProtonGalleryPhoto::nodeUid),
                        remoteNodeUids = remoteNodeUids,
                    )
                    cache.writeIndex(userId.id, photos)
                    photos
                },
                publishResult = { photos ->
                    val remoteNodeUids = photos.mapTo(mutableSetOf(), ProtonGalleryPhoto::nodeUid)
                    emit(userId, photos, hasLoaded = true, syncing = false) { previous ->
                        previous.tags.mapValues { (_, tagState) ->
                            tagState.copy(photos = tagState.photos.filter { it.nodeUid in remoteNodeUids })
                        }
                    }
                },
                publishCancelled = { updateState(userId) { state -> state.copy(syncing = false) } },
                publishFailed = { updateState(userId) { state -> state.copy(syncing = false, refreshFailed = true) } },
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
                publishFresh = { updateTag(userId, tag) { ProtonTagState(existing, hasLoaded = true) } },
                publishSyncing = {
                    updateTag(userId, tag) { ProtonTagState(existing, hasLoaded = hasCachedSnapshot, syncing = true) }
                },
                enumerate = { listTag(userId, tag, existing) },
                commit = { photos ->
                    val retained = retainTimelinePhotos(userId, photos)
                    cache.writeTag(userId.id, tag, retained)
                    retained
                },
                publishResult = { photos -> updateTag(userId, tag) { ProtonTagState(photos, hasLoaded = true) } },
                publishCancelled = {
                    updateTag(userId, tag) { current -> current?.copy(syncing = false) ?: ProtonTagState() }
                },
                // Unlike the other listings, a failed tag sync republishes the cached photos rather
                // than copying whatever tag state is currently published.
                publishFailed = {
                    updateTag(userId, tag) {
                        ProtonTagState(
                            photos = existing,
                            hasLoaded = hasCachedSnapshot,
                            refreshFailed = true,
                        )
                    }
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

        /**
         * Applies a favourite toggle Proton has accepted. The favourites tag mutex keeps a
         * favourites sync from committing a listing enumerated before the toggle on top of it,
         * and the mutation mutex keeps a removal out; the next listing is built inside the
         * state update so a thumbnail mark landing at the same time is not lost either.
         */
        internal suspend fun setFavorite(
            userId: UserId,
            nodeUids: Set<String>,
            favorite: Boolean,
        ) {
            if (nodeUids.isEmpty()) return
            tagMutexes.getValue(ProtonMediaTag.FAVORITES).withLock {
                mutationMutex.withLock {
                    // A file stat per toggled photo, taken outside the update so a retried
                    // update never touches the disk twice.
                    val storedThumbnails =
                        if (favorite) {
                            nodeUids.associateWith { nodeUid ->
                                cache.thumbnailExists(userId.id, nodeUid)
                            }
                        } else {
                            emptyMap()
                        }
                    val next =
                        mutableState.updateAndGet { state ->
                            if (state.userId != userId.id) return@updateAndGet state
                            val current = state.tags[ProtonMediaTag.FAVORITES]
                            val favorites = current?.photos.orEmpty()
                            val nextFavorites =
                                if (favorite) {
                                    val knownPhotos = state.photos.associateBy(ProtonGalleryPhoto::nodeUid)
                                    (
                                        favorites +
                                            nodeUids.map { nodeUid ->
                                                knownPhotos[nodeUid] ?: ProtonGalleryPhoto(
                                                    nodeUid = nodeUid,
                                                    captureTimeEpochSeconds = 0L,
                                                    hasThumbnail = storedThumbnails.getValue(nodeUid),
                                                )
                                            }
                                    ).distinctBy(ProtonGalleryPhoto::nodeUid)
                                        .sortedByDescending(ProtonGalleryPhoto::captureTimeEpochSeconds)
                                } else {
                                    favorites.filterNot { it.nodeUid in nodeUids }
                                }
                            val nextState =
                                ProtonTagState(
                                    photos = nextFavorites,
                                    hasLoaded = current?.hasLoaded == true,
                                    refreshFailed = current?.refreshFailed == true,
                                )
                            state.copy(tags = state.tags + (ProtonMediaTag.FAVORITES to nextState))
                        }
                    if (next.userId != userId.id) return
                    val favorites = next.tags.getValue(ProtonMediaTag.FAVORITES)
                    // A listing that was never loaded has no file to keep in step.
                    if (favorites.hasLoaded) cache.writeTag(userId.id, ProtonMediaTag.FAVORITES, favorites.photos)
                }
            }
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
                    updateState(userId) { state ->
                        state.copy(
                            photos = state.photos.filterNot { it.nodeUid in nodeUids },
                            hasLoaded = true,
                            syncing = false,
                            tags =
                                state.tags.mapValues { (_, tagState) ->
                                    tagState.copy(photos = tagState.photos.filterNot { it.nodeUid in nodeUids })
                                },
                        )
                    }
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
         * themselves, so no defensive copy is taken. [tags] derives the tag map from the state
         * being replaced, inside the update, and keeps the published one by default.
         */
        private fun emit(
            userId: UserId,
            photos: List<ProtonGalleryPhoto>,
            hasLoaded: Boolean,
            syncing: Boolean,
            tags: (previous: ProtonGalleryState) -> Map<ProtonMediaTag, ProtonTagState> = { previous ->
                previous.tags
            },
        ) {
            mutableState.update { previous ->
                ProtonGalleryState(
                    userId = userId.id,
                    photos = if (previous.photos == photos) previous.photos else photos,
                    hasLoaded = hasLoaded,
                    syncing = syncing,
                    tags = tags(previous),
                )
            }
        }

        /** Applies [transform] to the published state, but only while it still belongs to [userId]. */
        private inline fun updateState(
            userId: UserId,
            transform: (ProtonGalleryState) -> ProtonGalleryState,
        ) {
            mutableState.update { state -> if (state.userId != userId.id) state else transform(state) }
        }

        /** Replaces one tag's state, derived from the one published at that moment, while the state is [userId]'s. */
        private inline fun updateTag(
            userId: UserId,
            tag: ProtonMediaTag,
            transform: (current: ProtonTagState?) -> ProtonTagState,
        ) {
            updateState(userId) { gallery ->
                gallery.copy(tags = gallery.tags + (tag to transform(gallery.tags[tag])))
            }
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
