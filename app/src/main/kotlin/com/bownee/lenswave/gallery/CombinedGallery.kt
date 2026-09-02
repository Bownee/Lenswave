package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonGalleryPhoto
import java.security.MessageDigest

data class DevicePhotoMatchRecord(
    val stableId: String,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedAtEpochMillis: Long,
    val checkedTimelineFingerprint: String = "",
    val checkedAtEpochMillis: Long = 0,
    val matchStrategyVersion: Int = 0,
    val sha1Hex: String? = null,
    val protonNodeUids: List<String> = emptyList(),
) {
    fun matches(photo: GalleryAsset): Boolean =
        stableId == photo.stableId &&
            displayName == photo.displayName &&
            sizeBytes == photo.sizeBytes &&
            modifiedAtEpochMillis == photo.modifiedAtEpochMillis
}

data class CombinedMatchSnapshot(
    val timelineFingerprint: String = "",
    val records: List<DevicePhotoMatchRecord> = emptyList(),
)

object CombinedGallery {
    fun shouldRetry(progress: CombinedMatchProgress): Boolean =
        progress.complete && progress.errorMessage != null

    fun merge(
        devicePhotos: List<GalleryAsset>,
        protonPhotos: List<GalleryAsset>,
        matches: Map<String, List<String>>,
    ): List<GalleryAsset> {
        val uniqueDevicePhotos = devicePhotos.distinctBy(GalleryAsset::stableId)
        val uniqueProtonPhotos = protonPhotos.distinctBy { it.protonNodeUid ?: it.stableId }
        val protonPhotosByNodeUid = uniqueProtonPhotos.associateBy { requireNotNull(it.protonNodeUid) }
        val hiddenProtonNodeUids = mutableSetOf<String>()
        val deviceItems = uniqueDevicePhotos.map { photo ->
            val backingAssets = matches[photo.stableId]
                .orEmpty()
                .mapNotNull(protonPhotosByNodeUid::get)
                .distinct()
                .sortedBy(GalleryAsset::protonNodeUid)
            hiddenProtonNodeUids += backingAssets.mapNotNull(GalleryAsset::protonNodeUid)
            photo.withReplicas(backingAssets.map(GalleryAsset::primaryReplica))
        }
        return deviceItems + uniqueProtonPhotos.filter { it.protonNodeUid !in hiddenProtonNodeUids }
    }

    fun captureTimeCandidates(
        devicePhoto: GalleryAsset,
        protonPhotos: List<ProtonGalleryPhoto>,
    ): List<ProtonGalleryPhoto> {
        if (devicePhoto.capturedAtEpochMillis <= 0) return emptyList()
        val captureTimeEpochSeconds = Math.floorDiv(devicePhoto.capturedAtEpochMillis, 1_000L)
        return protonPhotos
            .filter { it.captureTimeEpochSeconds == captureTimeEpochSeconds }
            .distinctBy(ProtonGalleryPhoto::nodeUid)
    }

    fun captureTimeIndex(
        protonPhotos: List<ProtonGalleryPhoto>,
    ): Map<Long, List<ProtonGalleryPhoto>> = protonPhotos
        .distinctBy(ProtonGalleryPhoto::nodeUid)
        .groupBy(ProtonGalleryPhoto::captureTimeEpochSeconds)

    fun shouldCheck(
        photo: GalleryAsset,
        record: DevicePhotoMatchRecord?,
        timelineFingerprint: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (record == null || !record.matches(photo)) return true
        if (record.checkedTimelineFingerprint != timelineFingerprint) return true
        if (record.protonNodeUids.isNotEmpty()) return false
        if (record.matchStrategyVersion != MATCH_STRATEGY_VERSION) return true
        if (record.checkedAtEpochMillis <= 0) return true
        return nowEpochMillis - record.checkedAtEpochMillis >= NEGATIVE_MATCH_MAX_AGE_MILLIS
    }

    fun timelineFingerprint(protonNodeUids: Collection<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        protonNodeUids.sorted().forEach { nodeUid ->
            digest.update(nodeUid.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().toHex()
    }

    internal const val MATCH_STRATEGY_VERSION = 2
    private const val NEGATIVE_MATCH_MAX_AGE_MILLIS = 15 * 60 * 1_000L
}

internal fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

internal fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0 || any { it.digitToIntOrNull(16) == null }) return null
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
