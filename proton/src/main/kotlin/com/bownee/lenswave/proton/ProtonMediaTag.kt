package com.bownee.lenswave.proton

import androidx.annotation.StringRes
import com.bownee.lenswave.proton.R
import me.proton.drive.sdk.entity.PhotoTag

enum class ProtonMediaTag(
    val apiValue: Long,
    @param:StringRes val labelRes: Int,
    internal val sdkTag: PhotoTag,
) {
    FAVORITES(0L, R.string.proton_tag_favorites, PhotoTag.Favorite),
    SCREENSHOTS(1L, R.string.proton_tag_screenshots, PhotoTag.Screenshot),
    VIDEOS(2L, R.string.proton_tag_videos, PhotoTag.Video),
    LIVE_PHOTOS(3L, R.string.proton_tag_live_photos, PhotoTag.LivePhoto),
    MOTION_PHOTOS(4L, R.string.proton_tag_motion_photos, PhotoTag.MotionPhoto),
    SELFIES(5L, R.string.proton_tag_selfies, PhotoTag.Selfie),
    PORTRAITS(6L, R.string.proton_tag_portraits, PhotoTag.Portrait),
    BURSTS(7L, R.string.proton_tag_bursts, PhotoTag.Burst),
    PANORAMAS(8L, R.string.proton_tag_panoramas, PhotoTag.Panorama),
    RAW(9L, R.string.proton_tag_raw, PhotoTag.Raw),
}
