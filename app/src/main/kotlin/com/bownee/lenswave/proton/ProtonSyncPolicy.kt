package com.bownee.lenswave.proton

internal object ProtonSyncPolicy {
    fun shouldEnumerate(
        lastSuccessfulSyncMillis: Long,
        forceRemote: Boolean,
        hasCachedSnapshot: Boolean,
    ): Boolean {
        if (forceRemote || !hasCachedSnapshot || lastSuccessfulSyncMillis <= 0L) return true
        // Time alone never invalidates a snapshot. Drive events track remote changes.
        return false
    }
}
