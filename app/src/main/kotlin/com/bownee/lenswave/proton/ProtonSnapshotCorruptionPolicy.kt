package com.bownee.lenswave.proton

import org.json.JSONException
import javax.crypto.AEADBadTagException

/**
 * Whether a failed cached-snapshot read means the file itself is bad.
 *
 * Only a file that is provably corrupt is worth discarding: malformed JSON, a broken envelope
 * header, or an authentication tag that no longer verifies. Anything else, an I/O error or a
 * Keystore that refuses to unwrap the data key for a moment, is transient; deleting on those
 * would wipe the timeline, the albums or the download queue over a hiccup.
 */
internal object ProtonSnapshotCorruptionPolicy {
    fun isCorrupt(error: Throwable): Boolean =
        generateSequence(error, Throwable::cause)
            .take(MAX_CAUSE_DEPTH)
            .any { cause ->
                cause is JSONException || cause is AEADBadTagException || cause is IllegalArgumentException
            }

    private const val MAX_CAUSE_DEPTH = 4
}
