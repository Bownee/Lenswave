package com.bownee.lenswave.proton

/**
 * Decides how many bytes an ExoPlayer open on a still-downloading original may read. The SDK's
 * progress total describes the encrypted transfer, not the decrypted file, so it never bounds a
 * read: while the download runs the length is unknown, and only the finished file's real length
 * is trusted. Null means the requested position lies beyond the finished file.
 */
internal object ProtonProgressiveReadPolicy {
    const val LENGTH_UNKNOWN = -1L

    fun bytesRemaining(
        position: Long,
        requestedLength: Long,
        availableBytes: Long,
        complete: Boolean,
    ): Long? {
        if (complete && position > availableBytes) return null
        if (requestedLength != LENGTH_UNKNOWN) return requestedLength.takeIf { it >= 0L }
        if (!complete) return LENGTH_UNKNOWN
        return availableBytes - position
    }
}
