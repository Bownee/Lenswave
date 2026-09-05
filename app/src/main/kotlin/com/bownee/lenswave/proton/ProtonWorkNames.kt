package com.bownee.lenswave.proton

import com.bownee.lenswave.storage.AtomicFileStore
import me.proton.core.domain.entity.UserId

internal object ProtonWorkNames {
    /**
     * The one unique work name every thumbnail run of the user shares. WorkManager only
     * de-duplicates within a name, so the charging follow-up runs under this name as well
     * (see [ProtonThumbnailWorkScheduler]); a second name would let two runs drain the same
     * queues at once.
     */
    fun thumbnails(userId: UserId): String = "proton-photo-thumbnails-${AtomicFileStore.safeName(userId.id)}"

    /**
     * The name earlier versions gave the charging run. It is only ever cancelled: a request
     * left under it by an upgrade would otherwise run beside the one under [thumbnails].
     */
    fun legacyThumbnailsWhileCharging(userId: UserId): String = "${thumbnails(userId)}-charging"
}
