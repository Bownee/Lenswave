package com.bownee.lenswave.proton

import com.bownee.lenswave.storage.AtomicFileStore
import me.proton.core.domain.entity.UserId

internal object ProtonWorkNames {
    fun thumbnails(userId: UserId): String = "proton-photo-thumbnails-${AtomicFileStore.safeName(userId.id)}"
}
