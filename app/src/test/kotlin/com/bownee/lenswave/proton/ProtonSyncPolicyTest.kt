package com.bownee.lenswave.proton

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonSyncPolicyTest {
    @Test fun committedSnapshotsDoNotExpireWithTime() {
        assertFalse(ProtonSyncPolicy.shouldEnumerate(1L, forceRemote = false, hasCachedSnapshot = true))
    }

    @Test fun manualRefreshCanExplicitlyRebuildASnapshot() {
        assertTrue(ProtonSyncPolicy.shouldEnumerate(1L, forceRemote = true, hasCachedSnapshot = true))
    }

    @Test fun aMissingSnapshotOrUncommittedListingMustBeLoaded() {
        assertTrue(ProtonSyncPolicy.shouldEnumerate(1L, forceRemote = false, hasCachedSnapshot = false))
        assertTrue(ProtonSyncPolicy.shouldEnumerate(0L, forceRemote = false, hasCachedSnapshot = true))
    }
}
