package com.bownee.lenswave.proton

import com.bownee.lenswave.storage.CorruptEnvelopeException
import com.bownee.lenswave.storage.SecureFileStore
import org.json.JSONException
import javax.crypto.AEADBadTagException

/**
 * Whether a failed cached-file read (a snapshot, a thumbnail, a preview or an original) means the
 * file itself is bad.
 *
 * Only a file that is provably corrupt is worth discarding: malformed JSON, an envelope whose
 * bytes do not fit the format ([CorruptEnvelopeException]), or an authentication tag that no
 * longer verifies. Any other [IllegalArgumentException] is an argument check that failed, a bug
 * somewhere in the caller, and says nothing about the file. Anything else, an I/O error or a
 * Keystore that refuses to unwrap the data key for a moment, is transient; deleting on those
 * would wipe the timeline, the albums, the download queue or every stored rendition over a
 * hiccup. A data key that cannot be produced at all is a fault of the whole scope, so it is
 * never corruption of the one file being read, whatever the Keystore threw underneath.
 */
internal object ProtonSnapshotCorruptionPolicy {
    fun isCorrupt(error: Throwable): Boolean {
        val causes = generateSequence(error, Throwable::cause).take(MAX_CAUSE_DEPTH).toList()
        if (causes.any { cause -> cause is SecureFileStore.DataKeyUnavailableException }) return false
        return causes.any { cause ->
            cause is JSONException || cause is AEADBadTagException || cause is CorruptEnvelopeException
        }
    }

    private const val MAX_CAUSE_DEPTH = 4
}
