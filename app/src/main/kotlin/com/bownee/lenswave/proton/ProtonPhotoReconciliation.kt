package com.bownee.lenswave.proton

internal data class ProtonPhotoChanges(
    val addedNodeUids: Set<String> = emptySet(),
    val removedNodeUids: Set<String> = emptySet(),
)

internal object ProtonPhotoReconciliation {
    fun compare(cachedNodeUids: Collection<String>, remoteNodeUids: Collection<String>): ProtonPhotoChanges {
        val cached = cachedNodeUids.toSet()
        val remote = remoteNodeUids.toSet()
        return ProtonPhotoChanges(
            addedNodeUids = remote - cached,
            removedNodeUids = cached - remote,
        )
    }
}
