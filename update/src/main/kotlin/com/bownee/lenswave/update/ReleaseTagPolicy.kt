package com.bownee.lenswave.update

/**
 * What a release tag read from the network may look like before it is looked at. A tag is a
 * short version string; anything longer is not a release of ours and is not worth parsing,
 * storing or logging.
 */
internal object ReleaseTagPolicy {
    const val MAX_TAG_LENGTH = 64

    /** [tag] when it is a plausible release tag, else null. */
    fun accept(tag: String?): String? = tag?.takeIf { it.isNotEmpty() && it.length <= MAX_TAG_LENGTH }
}
