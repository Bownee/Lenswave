package com.bownee.lenswave.viewer

import com.bownee.lenswave.gallery.GalleryAsset
import java.util.concurrent.atomic.AtomicLong

/**
 * Hands the gallery's full asset list to the viewer within the process, so the intent only has
 * to carry a small window of it. The list is the gallery's own immutable snapshot, so holding it
 * costs nothing extra; the viewer lets go of it when it finishes. After a process death the
 * token no longer resolves and the viewer keeps the window its intent carried.
 *
 * Entries are keyed by token: a viewer that is still alive (recreating, say) must find its own
 * list even after the gallery published a newer one for a second launch. A few are kept; the
 * oldest goes when a viewer never got to clear its entry.
 */
internal object PhotoNavigationSources {
    class Source(
        val token: Long,
        val userId: String,
        val assets: List<GalleryAsset>,
    )

    /** The token of a [Source] rebuilt by the viewer itself rather than published by the gallery. */
    const val NO_TOKEN = -1L

    private const val RETAINED_SOURCES = 4

    private val tokens = AtomicLong()
    private val sources = LinkedHashMap<Long, Source>()

    fun publish(
        userId: String,
        assets: List<GalleryAsset>,
    ): Long {
        val source = Source(tokens.incrementAndGet(), userId, assets)
        synchronized(sources) {
            sources[source.token] = source
            while (sources.size > RETAINED_SOURCES) sources.remove(sources.keys.first())
        }
        return source.token
    }

    fun find(token: Long): Source? = synchronized(sources) { sources[token] }

    fun clear(token: Long) {
        synchronized(sources) { sources.remove(token) }
    }
}
