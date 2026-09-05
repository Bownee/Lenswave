package com.bownee.lenswave.proton

import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProtonWorkNamesTest {
    @Test
    fun thumbnailWorkNameIsAStableDigestOfTheUserId() {
        // SHA-256("abc"); a changed digest would orphan the unique work already enqueued on devices.
        assertEquals(
            "proton-photo-thumbnails-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ProtonWorkNames.thumbnails(UserId("abc")),
        )
        assertEquals(ProtonWorkNames.thumbnails(UserId("abc")), ProtonWorkNames.thumbnails(UserId("abc")))
    }

    @Test
    fun differentUsersGetDifferentWorkNames() {
        assertNotEquals(ProtonWorkNames.thumbnails(UserId("user-a")), ProtonWorkNames.thumbnails(UserId("user-b")))
    }

    @Test
    fun theLegacyChargingNameIsTheOneOlderVersionsUsed() {
        // Cancelling anything left under it after an upgrade needs the exact old spelling.
        assertEquals(
            "${ProtonWorkNames.thumbnails(UserId("abc"))}-charging",
            ProtonWorkNames.legacyThumbnailsWhileCharging(UserId("abc")),
        )
    }
}
