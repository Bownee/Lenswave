package com.bownee.lenswave.proton

/** On-device locations, key scopes, and retention shared by the Proton media caches. */
internal object ProtonStorageLayout {
    /** Under `filesDir`: metadata indexes, sync stamps, the download queues, thumbnails, and previews. */
    const val METADATA_DIRECTORY = "proton-photo-cache"

    /** Under `cacheDir`: encrypted originals. */
    const val ORIGINALS_DIRECTORY = "proton-originals"

    /** Under `cacheDir`: short-lived plaintext copies of originals. */
    const val DECRYPTED_DIRECTORY = "proton-decrypted"

    /** Per-user subdirectory of [METADATA_DIRECTORY] holding thumbnails. */
    const val THUMBNAILS_DIRECTORY = "thumbnails"

    /** Per-user subdirectory of [METADATA_DIRECTORY] holding screen-sized previews. */
    const val PREVIEWS_DIRECTORY = "previews"

    /** Partial downloads older than this are abandoned. */
    const val STALE_PART_TTL_MILLIS = 24L * 60L * 60L * 1_000L

    /** Plaintext originals are removed this long after they were last used. */
    const val DECRYPTED_TTL_MILLIS = 30L * 60L * 1_000L

    /** Keystore scope protecting every cached file of one user. */
    fun mediaScope(userId: String): String = "proton-media:$userId"
}
