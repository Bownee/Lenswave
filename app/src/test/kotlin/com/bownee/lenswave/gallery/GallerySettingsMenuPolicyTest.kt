package com.bownee.lenswave.gallery

import com.bownee.lenswave.R
import com.bownee.lenswave.gallery.GallerySettingsMenuPolicy.Entry
import com.bownee.lenswave.gallery.GallerySettingsMenuPolicy.Item
import org.junit.Assert.assertEquals
import org.junit.Test

class GallerySettingsMenuPolicyTest {
    @Test
    fun aSignedOutSessionOffersToConnect() {
        assertEquals(
            listOf(
                Entry(Item.CONNECT_PROTON),
                Entry(Item.PRIVACY),
                Entry(Item.BLOCK_SCREENSHOTS, checked = false),
                Entry(Item.VERSION, enabled = false),
            ),
            GallerySettingsMenuPolicy.entries(connected = false, blockScreenshots = false),
        )
    }

    @Test
    fun aSignedInSessionOffersToDisconnectAndTheToggleShowsItsState() {
        assertEquals(
            listOf(
                Entry(Item.DISCONNECT_PROTON),
                Entry(Item.PRIVACY),
                Entry(Item.BLOCK_SCREENSHOTS, checked = true),
                Entry(Item.VERSION, enabled = false),
            ),
            GallerySettingsMenuPolicy.entries(connected = true, blockScreenshots = true),
        )
    }

    @Test
    fun everyItemHasItsOwnTitle() {
        assertEquals(
            Item.entries.size,
            Item.entries
                .map { it.titleRes }
                .toSet()
                .size,
        )
        assertEquals(R.string.app_version, Item.VERSION.titleRes)
    }

    @Test
    fun theTelemetryWriteIsReportedAsSavedOrFailed() {
        assertEquals(R.string.privacy_setting_saved, GallerySettingsMenuPolicy.telemetryOutcomeText(saved = true))
        assertEquals(R.string.privacy_setting_failed, GallerySettingsMenuPolicy.telemetryOutcomeText(saved = false))
    }
}
