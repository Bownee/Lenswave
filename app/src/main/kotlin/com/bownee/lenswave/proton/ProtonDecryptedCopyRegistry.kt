package com.bownee.lenswave.proton

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a [ProtonOriginalStream] reports the plaintext file its readers hold open, so the store's
 * TTL sweep leaves that copy alone: a video that has been playing or paused for longer than the
 * TTL would otherwise lose its file under the player.
 */
interface ProtonOriginalReaders {
    fun opened(file: File)

    fun closed(file: File)

    /** For a stream nobody sweeps behind: a bare download target in a test, say. */
    object None : ProtonOriginalReaders {
        override fun opened(file: File) = Unit

        override fun closed(file: File) = Unit
    }
}

/** The plaintext copies currently open in a reader, counted per path; the sweep asks [isInUse]. */
@Singleton
internal class ProtonDecryptedCopyRegistry
    @Inject
    constructor() : ProtonOriginalReaders {
        private val readers = ConcurrentHashMap<String, Int>()

        override fun opened(file: File) {
            readers.merge(file.absolutePath, 1, Int::plus)
        }

        override fun closed(file: File) {
            readers.computeIfPresent(file.absolutePath) { _, count -> (count - 1).takeIf { it > 0 } }
        }

        fun isInUse(file: File): Boolean = readers.containsKey(file.absolutePath)
    }
