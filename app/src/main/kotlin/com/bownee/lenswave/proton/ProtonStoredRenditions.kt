package com.bownee.lenswave.proton

import com.bownee.lenswave.storage.AtomicFileStore

/**
 * Which nodes have a stored thumbnail or preview, answered from one directory listing per
 * rendition instead of a stat per node.
 *
 * Hydrating a cached index asks this question for every photo, and a large library holds tens of
 * thousands of them. Probing the filesystem per photo cost four syscalls and four hashes each and
 * dominated the time between opening the app and seeing the cached grid; a listing costs one
 * syscall per directory and one hash per photo.
 */
internal class ProtonStoredRenditions(
    private val thumbnailNames: Set<String>,
    private val previewNames: Set<String>,
    private val fileNameOf: (nodeUid: String) -> String = AtomicFileStore::safeName,
) {
    /** How many thumbnails are stored; a cached listing that cannot be read is judged against this. */
    val thumbnailCount: Int get() = thumbnailNames.size

    fun hasThumbnail(nodeUid: String): Boolean = fileNameOf(nodeUid) in thumbnailNames

    fun hasPreview(nodeUid: String): Boolean = fileNameOf(nodeUid) in previewNames

    /** Both answers for one node with the file name derived once. */
    fun photo(
        nodeUid: String,
        captureTimeEpochSeconds: Long,
    ): ProtonGalleryPhoto {
        val name = fileNameOf(nodeUid)
        return ProtonGalleryPhoto(
            nodeUid = nodeUid,
            captureTimeEpochSeconds = captureTimeEpochSeconds,
            hasThumbnail = name in thumbnailNames,
            hasPreview = name in previewNames,
        )
    }

    companion object {
        val NONE = ProtonStoredRenditions(emptySet(), emptySet())
    }
}
