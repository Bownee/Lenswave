package com.bownee.lenswave.proton

import com.bownee.lenswave.storage.SecureFileStore
import org.json.JSONException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException

class ProtonSnapshotCorruptionPolicyTest {
    @Test
    fun malformedContentIsCorrupt() {
        assertTrue(ProtonSnapshotCorruptionPolicy.isCorrupt(JSONException("unterminated array")))
        assertTrue(ProtonSnapshotCorruptionPolicy.isCorrupt(AEADBadTagException("tag mismatch")))
        assertTrue(ProtonSnapshotCorruptionPolicy.isCorrupt(IllegalArgumentException("Encrypted file is truncated")))
        assertTrue(ProtonSnapshotCorruptionPolicy.isCorrupt(NumberFormatException("captureTime")))
    }

    @Test
    fun corruptionIsFoundThroughWrappingExceptions() {
        assertTrue(ProtonSnapshotCorruptionPolicy.isCorrupt(RuntimeException("wrapped", JSONException("bad"))))
        assertTrue(
            ProtonSnapshotCorruptionPolicy.isCorrupt(
                IllegalStateException("outer", RuntimeException("middle", AEADBadTagException("inner"))),
            ),
        )
    }

    @Test
    fun transientFailuresAreNotCorrupt() {
        assertFalse(ProtonSnapshotCorruptionPolicy.isCorrupt(IOException("disk busy")))
        assertFalse(ProtonSnapshotCorruptionPolicy.isCorrupt(KeyStoreException("keystore unavailable")))
        assertFalse(ProtonSnapshotCorruptionPolicy.isCorrupt(ProviderException("keymaster timed out")))
        assertFalse(ProtonSnapshotCorruptionPolicy.isCorrupt(IllegalStateException("Could not store data key")))
        assertFalse(ProtonSnapshotCorruptionPolicy.isCorrupt(RuntimeException("wrapped", IOException("disk busy"))))
    }

    @Test
    fun anUnavailableDataKeyIsNeverCorruptionOfTheFileBeingRead() {
        val unavailable =
            SecureFileStore.DataKeyUnavailableException(
                IllegalArgumentException("Wrapped data key is invalid"),
            )

        assertFalse(ProtonSnapshotCorruptionPolicy.isCorrupt(unavailable))
        assertFalse(ProtonSnapshotCorruptionPolicy.isCorrupt(IllegalStateException("read failed", unavailable)))
        assertFalse(
            ProtonSnapshotCorruptionPolicy.isCorrupt(
                SecureFileStore.DataKeyUnavailableException(ProviderException("keymaster timed out")),
            ),
        )
    }

    @Test
    fun causeChainsAreOnlyFollowedSoFar() {
        var error: Throwable = JSONException("deep")
        repeat(6) { error = RuntimeException("layer", error) }

        assertFalse(ProtonSnapshotCorruptionPolicy.isCorrupt(error))
    }
}
