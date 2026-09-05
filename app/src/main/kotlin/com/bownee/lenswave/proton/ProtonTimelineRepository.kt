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
        /**
         * One timeline sync per account at a time, without a lock: a second sync asked for
         * while one enumerates waits for that one's outcome instead of enumerating the library
         * again (see [ProtonCoalescedRuns]); only the commit takes [mutationMutex].
         */
        private val timelineSyncs = ProtonCoalescedRuns<String>()

        /**
         * One sync per tag at a time, held across the tag's enumeration: [setFavorite] takes the
         * favourites mutex so a favourites listing enumerated before the toggle cannot be
         * committed on top of it, which is why the tag syncs are not coalesced like the timeline.
         */
        private val tagMutexes = ProtonMediaTag.entries.associateWith { Mutex() }

        /**
         * Serializes every write to the cached listings and to the state that mirrors them: a
         * sync commit (with its stamp and publish), a favourite toggle and a removal. It is the
         * innermost lock of the hierarchy, taken after a tag mutex where one is held and never
         * across an enumeration, and a removal takes nothing else, so a trash never waits on a
         * network round trip. A sync that enumerated before the trash subtracts it at commit
         * time instead (see [removals]), so neither the timeline nor a tag can publish or persist
         * the trashed photo again.
         */
        private val mutationMutex = Mutex()

        /**
         * Every removal since each sync in flight began, recorded under [mutationMutex]. The
         * commit subtracts exactly those, rather than diffing the published listing against the
         * one it started from: that diff read a listing that could not be read for a moment as
         * the removal of everything.
         */
        private val removals = ProtonRemovalLog()
        private val mutableState = MutableStateFlow(ProtonGalleryState())

        /** Uid lookups over the published timeline and tag listings, memoized per list instance; see [ProtonNodeUidIndex]. */
        private val timelineIndex = ProtonNodeUidIndex(ProtonGalleryPhoto::nodeUid)
        private val tagIndexes = ProtonMediaTag.entries.associateWith { photoIndex() }

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
        ) = timelineSyncs.run(userId.id, forced = forceRemote) {
            // Opened before the listing is read, so a removal between the two is subtracted too.
            val removalSnapshot = removals.openSnapshot()
            try {
                syncTimeline(userId, forceRemote, removalSnapshot)
            } finally {
                removals.closeSnapshot(removalSnapshot)
            }
        }

        private suspend fun syncTimeline(
            userId: UserId,
            forceRemote: Boolean,
            removalSnapshot: Long,
        ) {
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
                publishSyncing = {
                    // A refresh the user asked for is trusted with whatever Proton answers, so
                    // the refusal it is about to resolve stops showing as it starts.
                    emit(
                        userId,
                        existing,
                        hasLoaded = hasCachedSnapshot,
                        syncing = true,
                        listingRefused = if (forceRemote) false else null,
                    )
                },
                enumerate = {
                    val items = clientProvider.get(userId).enumerateTimeline().toList()
                    val availability = cache.storedRenditions(userId.id)
                    items.map { item -> availability.photo(item.nodeUid.value, item.captureTime.epochSecond) }
                },
                commit = { photos ->
                    // A photo trashed while the timeline was enumerating has left the cache and
                    // the screen; the enumerated listing must not bring it back.
                    val retained = removals.retain(userId.id, removalSnapshot, photos, ProtonGalleryPhoto::nodeUid)
                    if (hasCachedSnapshot) {
                        ProtonReconcileSafetyPolicy.requireCommit(
                            listing = "timeline",
                            existing = existing,
                            remoteNodeUids =
                                retained.mapTo(
                                    HashSet(retained.size * 4 / 3 + 1),
                                    ProtonGalleryPhoto::nodeUid,
                                ),
                            forceRemote = forceRemote,
                            nodeUid = ProtonGalleryPhoto::nodeUid,
                        )
                    } else {
                        // No listing could be read, so nothing is known to be removed; the stored
                        // thumbnails say how much the cache holds and keep a transient read failure
                        // from turning a truncated enumeration into a wipe.
                        ProtonReconcileSafetyPolicy.requireCommitOverStoredThumbnails(
                            listing = "timeline",
                            storedThumbnailCount = cache.storedRenditions(userId.id).thumbnailCount,
                            remoteStoredThumbnailCount = retained.count(ProtonGalleryPhoto::hasThumbnail),
                            forceRemote = forceRemote,
                        )
                    }
                    // The new listing lands before anything is deleted: a crash between the two
                    // then leaves stray renditions for the next reconcile, not a listing that
                    // points at renditions which are gone.
                    cache.writeIndex(userId.id, retained)
                    cache.reconcilePhotos(
                        userId = userId.id,
                        cachedNodeUids = existing.map(ProtonGalleryPhoto::nodeUid),
                        remoteNodeUids = retained.map(ProtonGalleryPhoto::nodeUid),
                    )
                    retained
                },
                publishResult = { photos ->
                    val remoteNodeUids = photos.mapTo(mutableSetOf(), ProtonGalleryPhoto::nodeUid)
                    emit(userId, photos, hasLoaded = true, syncing = false, listingRefused = false) { previous ->
                        previous.tags.mapValues { (_, tagState) ->
                            tagState.copy(photos = tagState.photos.filter { it.nodeUid in remoteNodeUids })
                        }
                    }
                },
                publishCancelled = { updateState(userId) { state -> state.copy(syncing = false) } },
                publishFailed = { error ->
                    updateState(userId) { state ->
                        state.copy(
                            syncing = false,
                            refreshFailed = true,
                            listingRefused = state.listingRefused || error.isListingRefusal(),
                        )
                    }
                },
                commitGate = { commit -> mutationMutex.withLock { commit() } },
            )
        }

        suspend fun syncTagMetadata(
            userId: UserId,
            tag: ProtonMediaTag,
            forceRemote: Boolean,
        ) = tagMutexes.getValue(tag).withLock {
            val removalSnapshot = removals.openSnapshot()
            try {
                syncTag(userId, tag, forceRemote, removalSnapshot)
            } finally {
                removals.closeSnapshot(removalSnapshot)
            }
        }

        private suspend fun syncTag(
            userId: UserId,
            tag: ProtonMediaTag,
            forceRemote: Boolean,
            removalSnapshot: Long,
        ) {
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
                publishFresh = {
                    updateTag(userId, tag) { current ->
                        ProtonTagState(existing, hasLoaded = true, listingRefused = current.isRefused())
                    }
                },
                publishSyncing = {
                    updateTag(userId, tag) { current ->
                        ProtonTagState(
                            existing,
                            hasLoaded = hasCachedSnapshot,
                            syncing = true,
                            listingRefused = !forceRemote && current.isRefused(),
                        )
                    }
                },
                enumerate = { listTag(userId, tag, existing) },
                commit = { photos ->
                    val retained =
                        retainTimelinePhotos(
                            userId,
                            removals.retain(userId.id, removalSnapshot, photos, ProtonGalleryPhoto::nodeUid),
                        )
                    ProtonReconcileSafetyPolicy.requireCommit(
                        listing = "tag-${tag.name.lowercase()}",
                        existing = existing,
                        remoteNodeUids = retained.mapTo(HashSet(), ProtonGalleryPhoto::nodeUid),
                        forceRemote = forceRemote,
                        nodeUid = ProtonGalleryPhoto::nodeUid,
                    )
                    cache.writeTag(userId.id, tag, retained)
                    retained
                },
                publishResult = { photos -> updateTag(userId, tag) { ProtonTagState(photos, hasLoaded = true) } },
                publishCancelled = {
                    updateTag(userId, tag) { current -> current?.copy(syncing = false) ?: ProtonTagState() }
                },
                // Unlike the other listings, a failed tag sync republishes the cached photos rather
                // than copying whatever tag state is currently published.
                publishFailed = { error ->
                    updateTag(userId, tag) { current ->
                        ProtonTagState(
                            photos = existing,
                            hasLoaded = hasCachedSnapshot,
                            refreshFailed = true,
                            listingRefused = current.isRefused() || error.isListingRefusal(),
                        )
                    }
                },
                commitGate = { commit -> mutationMutex.withLock { commit() } },
            )
        }

        /**
         * A tag listing narrowed to the photos the published timeline still has: Proton's tag index
         * may still name a photo the timeline no longer lists, and the tab must not show what the
         * grid does not. A timeline that has not loaded cannot judge, and the listing is kept whole.
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

        /**
         * Only the marked positions are replaced (see [withThumbnailAvailability]), and a mark for
         * photos no listing shows skips the state update altogether.
         */
        private fun markThumbnails(
            userId: UserId,
            nodeUids: Set<String>,
            available: Boolean,
        ) {
            if (nodeUids.isEmpty()) return
            val current = mutableState.value
            if (current.userId != userId.id) return
            val shown =
                current.photos.containsAnyNodeUid(nodeUids, timelineIndex) ||
                    current.tags.any { (tag, tagState) ->
                        tagState.photos.containsAnyNodeUid(nodeUids, tagIndexes.getValue(tag))
                    }
            if (!shown) return
            mutableState.update { state ->
                if (state.userId != userId.id) return@update state
                val photos = state.photos.withThumbnailAvailability(nodeUids, available, timelineIndex)
                // Tag listings that did not change keep their instance, so the tabs' memos hold.
                var tagsChanged = false
                val tags =
                    state.tags.mapValues { (tag, tagState) ->
                        tagState.photos
                            .withThumbnailAvailability(nodeUids, available, tagIndexes.getValue(tag))
                            ?.let { updated ->
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
            index: ProtonNodeUidIndex<ProtonGalleryPhoto>,
        ): List<ProtonGalleryPhoto>? =
            withThumbnailAvailability(
                nodeUids,
                available,
                index,
                hasThumbnail = ProtonGalleryPhoto::hasThumbnail,
                copy = { photo, hasThumbnail -> photo.copy(hasThumbnail = hasThumbnail) },
            )

        /** Previews only matter to the timeline queue, so tag listings are left untouched. */
        internal fun markPreviewsAvailable(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            markPreviews(userId, nodeUids, available = true)
        }

        /** A stored preview that no longer decodes is dropped and its photo shown as lacking one, see [markPreviewsAvailable]. */
        internal fun markPreviewsUnavailable(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            markPreviews(userId, nodeUids, available = false)
        }

        private fun markPreviews(
            userId: UserId,
            nodeUids: Set<String>,
            available: Boolean,
        ) {
            if (nodeUids.isEmpty()) return
            val current = mutableState.value
            if (current.userId != userId.id || !current.photos.containsAnyNodeUid(nodeUids, timelineIndex)) return
            mutableState.update { state ->
                if (state.userId != userId.id) return@update state
                val photos =
                    state.photos.withThumbnailAvailability(
                        nodeUids,
                        available,
                        timelineIndex,
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
            captureTimeOf: (nodeUid: String) -> Long? = { null },
        ) {
            if (nodeUids.isEmpty()) return
            tagMutexes.getValue(ProtonMediaTag.FAVORITES).withLock {
                mutationMutex.withLock {
                    // The photos to add are resolved once, outside the update: the timeline
                    // answers from its uid index, and only a photo it does not know (favourited
                    // from an album, say) costs a file stat, so a retried update never touches
                    // the disk twice.
                    val additions =
                        if (favorite) favoriteEntries(userId, nodeUids, captureTimeOf) ?: return else emptyList()
                    val next =
                        mutableState.updateAndGet { state ->
                            if (state.userId != userId.id) return@updateAndGet state
                            val current = state.tags[ProtonMediaTag.FAVORITES]
                            val favorites = current?.photos.orEmpty()
                            val nextFavorites =
                                if (favorite) {
                                    ProtonNewestFirstListing.insert(favorites, additions)
                                } else {
                                    favorites.filterNot { it.nodeUid in nodeUids }
                                }
                            val nextState =
                                ProtonTagState(
                                    photos = nextFavorites,
                                    hasLoaded = current?.hasLoaded == true,
                                    refreshFailed = current?.refreshFailed == true,
                                    listingRefused = current.isRefused(),
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

        /**
         * The favourites entries for [nodeUids], or null when the published state is not
         * [userId]'s. A photo the timeline does not list takes its capture time from
         * [captureTimeOf] (the album on screen, typically); one nobody knows keeps capture time
         * zero, which sorts it last, and it leaves the tab again when the tag next syncs against
         * the timeline.
         */
        private fun favoriteEntries(
            userId: UserId,
            nodeUids: Set<String>,
            captureTimeOf: (nodeUid: String) -> Long?,
        ): List<ProtonGalleryPhoto>? {
            val state = mutableState.value.takeIf { it.userId == userId.id } ?: return null
            return nodeUids.map { nodeUid ->
                timelineIndex.find(state.photos, nodeUid) ?: ProtonGalleryPhoto(
                    nodeUid = nodeUid,
                    captureTimeEpochSeconds = captureTimeOf(nodeUid) ?: 0L,
                    hasThumbnail = cache.thumbnailExists(userId.id, nodeUid),
                )
            }
        }

        internal suspend fun removePhotos(
            userId: UserId,
            nodeUids: Set<String>,
        ) {
            if (nodeUids.isEmpty()) return
            // Only the mutation mutex: an enumeration in flight keeps running and narrows its
            // listing when it commits, so the trash never waits on the network.
            mutationMutex.withLock {
                cache.removePhotos(userId.id, nodeUids)
                removals.record(userId.id, nodeUids)
                updateState(userId) { state ->
                    state.copy(
                        photos = state.photos.filterNot { it.nodeUid in nodeUids },
                        tags =
                            state.tags.mapValues { (_, tagState) ->
                                tagState.copy(photos = tagState.photos.filterNot { it.nodeUid in nodeUids })
                            },
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
         * themselves, so no defensive copy is taken. [tags] derives the tag map from the state
         * being replaced, inside the update, and keeps the published one by default; so does
         * [listingRefused] while the state stays this user's, unless a value is given.
         */
        private fun emit(
            userId: UserId,
            photos: List<ProtonGalleryPhoto>,
            hasLoaded: Boolean,
            syncing: Boolean,
            listingRefused: Boolean? = null,
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
                    listingRefused = listingRefused ?: (previous.userId == userId.id && previous.listingRefused),
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

        private fun ProtonTagState?.isRefused(): Boolean = this?.listingRefused == true

        private fun volumeId(photos: List<ProtonGalleryPhoto>): String? =
            photos
                .firstOrNull()
                ?.nodeUid
                ?.substringBefore('~')
                ?.takeIf(String::isNotBlank)

        private companion object {
            /** Below this the tag files parse in a few milliseconds, so the first publish can wait for them. */
            const val TAGS_WITH_FIRST_PUBLISH_LIMIT = 5_000

            fun photoIndex() = ProtonNodeUidIndex(ProtonGalleryPhoto::nodeUid)
        }
    }
