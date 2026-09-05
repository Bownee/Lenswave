package com.bownee.lenswave.viewer

import java.io.FileNotFoundException
import java.io.IOException

/**
 * When a playback error means the file behind the player is gone rather than broken. A complete
 * original's plaintext copy lives under a TTL; a long pause can outlast it, and the next read
 * fails with a missing file. That is recoverable by loading the media again, which decrypts a
 * fresh copy, so the viewer reloads once instead of showing the failure panel. Once only: a copy
 * that vanishes again is a failure worth showing, and a reload loop would hide it for good.
 */
internal object ViewerVideoReloadPolicy {
    /** The storage layer's own name for a complete stream whose plaintext file vanished. */
    const val COPY_MISSING_EXCEPTION = "ProtonOriginalCopyMissingException"

    /**
     * Whether [error] or anything in its cause chain says the file is missing: a plain
     * [FileNotFoundException], or the storage layer's [IOException] subclass, matched by name so
     * the viewer does not depend on it.
     */
    fun copyMissing(error: Throwable): Boolean {
        var current: Throwable? = error
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is FileNotFoundException) return true
            if (current is IOException && current.javaClass.simpleName == COPY_MISSING_EXCEPTION) return true
            current = current.cause
        }
        return false
    }

    /**
     * Whether the viewer should load the media again rather than report [error]. Only for a
     * stream that reported complete: a file missing mid-download is the download's failure, not
     * a swept copy; and only once per request.
     */
    fun reloads(
        error: Throwable,
        streamComplete: Boolean,
        alreadyReloaded: Boolean,
    ): Boolean = streamComplete && !alreadyReloaded && copyMissing(error)
}
