package com.bownee.lenswave.gallery

import kotlinx.coroutines.CancellationException
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveDispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import com.bownee.lenswave.proton.ProtonGalleryPhoto
import javax.inject.Inject
import javax.inject.Singleton

data class CombinedMatchProgress(
    val matches: Map<String, List<String>> = emptyMap(),
    val checkedCount: Int = 0,
    val checkCount: Int = 0,
    val complete: Boolean = false,
    val errorMessage: String? = null,
)

@Singleton
class CombinedPhotoRepository @Inject constructor(
    private val deviceRepository: DevicePhotoSource,
    private val protonRepository: ProtonDuplicateSource,
    private val cache: CombinedMatchStore,
    private val dispatchers: LenswaveDispatchers,
    private val clock: LenswaveClock,
) : CombinedPhotoMatcher {
    private val resolveMutex = Mutex()

    override suspend fun resolveMatches(
        userId: UserId,
        devicePhotos: List<GalleryAsset>,
        protonPhotos: List<ProtonGalleryPhoto>,
        forceRecheck: Boolean,
        onProgress: suspend (CombinedMatchProgress) -> Unit,
    ) = resolveMutex.withLock { withContext(dispatchers.computation) {
        val remoteNodeUids = protonPhotos.mapTo(mutableSetOf(), ProtonGalleryPhoto::nodeUid)
        val protonPhotosByCaptureTime = CombinedGallery.captureTimeIndex(protonPhotos)
        val captureTimeCandidates = devicePhotos.associate { photo ->
            val captureTimeSeconds = Math.floorDiv(photo.capturedAtEpochMillis, 1_000L)
            photo.stableId to if (photo.capturedAtEpochMillis > 0L) {
                protonPhotosByCaptureTime[captureTimeSeconds].orEmpty()
            } else {
                emptyList()
            }
        }
        val timelineFingerprint = CombinedGallery.timelineFingerprint(remoteNodeUids)
        val snapshot = withContext(dispatchers.io) { cache.read(userId.id) }
        val previousRecords = snapshot.records.associateBy(DevicePhotoMatchRecord::stableId)
        val currentRecords = linkedMapOf<String, DevicePhotoMatchRecord>()
        val matches = linkedMapOf<String, List<String>>()
        val photosToCheck = mutableListOf<GalleryAsset>()

        devicePhotos.forEach { photo ->
            val previous = previousRecords[photo.stableId]?.takeIf { it.matches(photo) }
            if (previous != null) {
                currentRecords[photo.stableId] = previous
                previous.protonNodeUids.filter(remoteNodeUids::contains).takeIf(List<String>::isNotEmpty)?.let {
                    matches[photo.stableId] = it
                }
            }
            if (forceRecheck || CombinedGallery.shouldCheck(photo, previous, timelineFingerprint)) {
                photosToCheck += photo
            }
        }

        onProgress(CombinedMatchProgress(matches.toMap(), checkCount = photosToCheck.size))
        var firstError: Throwable? = null
        var checkedCount = 0
        var consecutiveFailures = 0
        val checkpointRecords = mutableListOf<DevicePhotoMatchRecord>()
        for ((index, photo) in photosToCheck.withIndex()) {
            currentCoroutineContext().ensureActive()
            val previous = currentRecords[photo.stableId]
            var sha1 = previous?.sha1Hex?.hexToBytes()
            val oldMatches = matches[photo.stableId]
            try {
                val remoteMatches = findRemoteMatches(
                    userId = userId,
                    photo = photo,
                    candidatePhotos = captureTimeCandidates[photo.stableId].orEmpty(),
                    remoteNodeUids = remoteNodeUids,
                    generateSha1 = {
                        sha1 ?: deviceRepository.calculateSha1(photo).also { sha1 = it }
                    },
                )
                val record = DevicePhotoMatchRecord(
                    stableId = photo.stableId,
                    displayName = photo.displayName,
                    sizeBytes = photo.sizeBytes,
                    modifiedAtEpochMillis = photo.modifiedAtEpochMillis,
                    checkedTimelineFingerprint = timelineFingerprint,
                    checkedAtEpochMillis = clock.nowMillis(),
                    matchStrategyVersion = CombinedGallery.MATCH_STRATEGY_VERSION,
                    sha1Hex = sha1?.toHex(),
                    protonNodeUids = remoteMatches,
                )
                currentRecords[photo.stableId] = record
                checkpointRecords += record
                if (remoteMatches.isEmpty()) matches.remove(photo.stableId) else matches[photo.stableId] = remoteMatches
                consecutiveFailures = 0
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (firstError == null) firstError = error
                LenswaveDiagnostics.reportFailure("combined-match", error)
                consecutiveFailures++
            }

            checkedCount = index + 1
            if (checkedCount % PROGRESS_INTERVAL == 0) {
                appendCheckpoint(userId, timelineFingerprint, checkpointRecords)
                checkpointRecords.clear()
            }
            if (checkedCount % PROGRESS_INTERVAL == 0 || checkedCount == photosToCheck.size) {
                onProgress(
                    CombinedMatchProgress(
                        matches = matches.toMap(),
                        checkedCount = checkedCount,
                        checkCount = photosToCheck.size,
                        errorMessage = firstError?.let { MATCH_ERROR_MARKER },
                    )
                )
            }
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) break
        }

        currentCoroutineContext().ensureActive()
        appendCheckpoint(userId, timelineFingerprint, checkpointRecords)
        writeSnapshot(userId, timelineFingerprint, currentRecords.values)
        onProgress(
            CombinedMatchProgress(
                matches = matches.toMap(),
                checkedCount = checkedCount,
                checkCount = photosToCheck.size,
                complete = true,
                errorMessage = firstError?.let { MATCH_ERROR_MARKER },
            )
        )
    } }

    override suspend fun clear(userId: UserId) = resolveMutex.withLock {
        withContext(dispatchers.io) { cache.clear(userId.id) }
    }

    private suspend fun findRemoteMatches(
        userId: UserId,
        photo: GalleryAsset,
        candidatePhotos: List<ProtonGalleryPhoto>,
        remoteNodeUids: Set<String>,
        generateSha1: suspend () -> ByteArray,
    ): List<String> {
        val directMatches = protonRepository.findPhotoDuplicates(
            userId,
            photo.displayName,
            generateSha1,
        ).filter(remoteNodeUids::contains)
        if (directMatches.isNotEmpty() || candidatePhotos.isEmpty()) {
            return directMatches.distinct().sorted()
        }

        val fallbackNames = candidatePhotos.mapNotNull { candidate ->
            protonRepository.getOriginalFileName(userId, candidate.nodeUid)
        }.filter { it.isNotBlank() && it != photo.displayName }.distinct()
        return fallbackNames.flatMap { name ->
            protonRepository.findPhotoDuplicates(userId, name, generateSha1)
        }.filter(remoteNodeUids::contains).distinct().sorted()
    }

    private suspend fun writeSnapshot(
        userId: UserId,
        timelineFingerprint: String,
        records: Collection<DevicePhotoMatchRecord>,
    ) {
        withContext(dispatchers.io) {
            cache.write(
                userId.id,
                CombinedMatchSnapshot(
                    timelineFingerprint = timelineFingerprint,
                    records = records.toList(),
                ),
            )
        }
    }

    private suspend fun appendCheckpoint(
        userId: UserId,
        timelineFingerprint: String,
        records: Collection<DevicePhotoMatchRecord>,
    ) {
        if (records.isEmpty()) return
        withContext(dispatchers.io) {
            cache.append(userId.id, timelineFingerprint, records)
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL = 50
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val MATCH_ERROR_MARKER = "duplicate-check-failed"
    }
}
