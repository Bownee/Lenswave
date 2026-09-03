package com.bownee.lenswave.proton

import me.proton.core.domain.entity.UserId
import java.security.MessageDigest

internal object ProtonWorkNames {
    fun thumbnails(userId: UserId): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(userId.id.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "proton-photo-thumbnails-$digest"
    }
}
