package com.bownee.lenswave.update

/**
 * Which `ETag` response headers are worth persisting. The value is stored in preferences and sent
 * back verbatim as `If-None-Match`, so an unexpected server (or a response tampered with on the
 * way) must not be able to park an arbitrary blob in the app's storage or in its next request:
 * the tag is capped in length and restricted to the characters an entity tag is made of.
 */
internal object ReleaseEtagPolicy {
    const val MAX_LENGTH = 128

    /** RFC 9110 etagc plus the quotes and the weak prefix's slash, without whitespace or control characters. */
    private val ALLOWED = Regex("""^[A-Za-z0-9"/!#-~]+$""")

    /** [etag] when it is a plausible entity tag, null otherwise (nothing is stored and the next check reads afresh). */
    fun accept(etag: String?): String? {
        if (etag == null || etag.isEmpty() || etag.length > MAX_LENGTH) return null
        return etag.takeIf(ALLOWED::matches)
    }
}
