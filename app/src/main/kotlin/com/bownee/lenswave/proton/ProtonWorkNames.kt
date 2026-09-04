package com.bownee.lenswave.proton

import com.bownee.lenswave.storage.AtomicFileStore
import me.proton.core.domain.entity.UserId

internal object ProtonWorkNames {
    fun thumbnails(userId: UserId): String = "proton-photo-thumbnails-${AtomicFileStore.safeName(userId.id)}"

    /** The run that waits for the charger to download previews. */
    fun thumbnailsWhileCharging(userId: UserId): String = "${thumbnails(userId)}-charging"
}
