package com.bownee.lenswave.gallery

import androidx.annotation.StringRes
import com.bownee.lenswave.R

/** What the settings popup lists for the session on screen, and how the telemetry write is reported. */
internal object GallerySettingsMenuPolicy {
    enum class Item(
        @param:StringRes val titleRes: Int,
    ) {
        CONNECT_PROTON(R.string.connect_proton),
        DISCONNECT_PROTON(R.string.disconnect_proton),
        PRIVACY(R.string.privacy_and_data),
        BLOCK_SCREENSHOTS(R.string.block_screenshots),

        /** The app version, listed for reference only. */
        VERSION(R.string.app_version),
    }

    data class Entry(
        val item: Item,
        /** Null for a plain action; the checkbox state for a toggle. */
        val checked: Boolean? = null,
        val enabled: Boolean = true,
    )

    /**
     * Connect or disconnect according to whether an account is signed in, then privacy, the
     * screenshot toggle with its current state, and the version as a disabled footer.
     */
    fun entries(
        connected: Boolean,
        blockScreenshots: Boolean,
    ): List<Entry> =
        listOf(
            Entry(if (connected) Item.DISCONNECT_PROTON else Item.CONNECT_PROTON),
            Entry(Item.PRIVACY),
            Entry(Item.BLOCK_SCREENSHOTS, checked = blockScreenshots),
            Entry(Item.VERSION, enabled = false),
        )

    /** The toast for the view model's telemetry write (see [GalleryMutationEvent.TelemetryPreferenceSaved]). */
    @StringRes
    fun telemetryOutcomeText(saved: Boolean): Int =
        if (saved) R.string.privacy_setting_saved else R.string.privacy_setting_failed
}
