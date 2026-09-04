package com.bownee.lenswave.viewer

import com.bownee.lenswave.gallery.GalleryAsset
import java.util.concurrent.atomic.AtomicLong

/**
 * Hands the gallery's full asset list to the viewer within the process, so the intent only has
 * to carry a small window of it. The list is the gallery's own immutable snapshot, so holding it
 * costs nothing extra; the viewer lets go of it when it finishes. After a process death the
 * token no longer resolves and the viewer keeps the window its intent carried.
 */
internal object PhotoNavigationSources {
    class Source(
        val token: Long,
        val userId: String,
        val assets: List<GalleryAsset>,
    )

    private val tokens = AtomicLong()

    @Volatile
    private var latest: Source? = null

    fun publish(
        userId: String,
        assets: List<GalleryAsset>,
    ): Long {
        val source = Source(tokens.incrementAndGet(), userId, assets)
        latest = source
        return source.token
    }

    fun find(token: Long): Source? = latest?.takeIf { it.token == token }

    fun clear(token: Long) {
        if (latest?.token == token) latest = null
    }
}
