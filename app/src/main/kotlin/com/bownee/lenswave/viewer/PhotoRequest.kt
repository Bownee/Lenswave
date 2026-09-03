package com.bownee.lenswave.viewer

import android.content.Intent
import android.os.Bundle
import androidx.core.content.IntentCompat
import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.MediaKind
import com.bownee.lenswave.gallery.PhotoTarget

data class PhotoRequest(
    val stableId: String,
    val nodeUid: String,
    val userId: String,
    val capturedAt: Long,
    val displayName: String,
    val mediaKind: MediaKind = MediaKind.IMAGE,
    val isFavorite: Boolean = false,
) {
    fun toPhotoTarget(): PhotoTarget = PhotoTarget(stableId, nodeUid)

    fun withFavorite(favorite: Boolean): PhotoRequest = copy(isFavorite = favorite)

    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(PhotoViewerActivity.EXTRA_PROTON_NODE_UID, nodeUid)
        putExtra(PhotoViewerActivity.EXTRA_USER_ID, userId)
        putExtra(PhotoViewerActivity.EXTRA_CAPTURED_AT, capturedAt)
        putExtra(PhotoViewerActivity.EXTRA_DISPLAY_NAME, displayName)
        putExtra(PhotoViewerActivity.EXTRA_STABLE_ID, stableId)
        putExtra(PhotoViewerActivity.EXTRA_MEDIA_KIND, mediaKind.name)
        putExtra(PhotoViewerActivity.EXTRA_IS_FAVORITE, isFavorite)
    }

    fun toBundle(): Bundle = Bundle().also { value ->
        writeTo(Intent()).extras?.let(value::putAll)
    }

    companion object {
        fun from(photo: GalleryAsset, userId: String): PhotoRequest = PhotoRequest(
            stableId = photo.stableId,
            nodeUid = photo.nodeUid,
            userId = userId,
            capturedAt = photo.capturedAtEpochMillis,
            displayName = photo.displayName,
            mediaKind = photo.mediaKind,
            isFavorite = photo.isFavorite,
        )

        fun from(intent: Intent): PhotoRequest = PhotoRequest(
            stableId = requireNotNull(intent.getStringExtra(PhotoViewerActivity.EXTRA_STABLE_ID)),
            nodeUid = requireNotNull(intent.getStringExtra(PhotoViewerActivity.EXTRA_PROTON_NODE_UID)),
            userId = requireNotNull(intent.getStringExtra(PhotoViewerActivity.EXTRA_USER_ID)),
            capturedAt = intent.getLongExtra(PhotoViewerActivity.EXTRA_CAPTURED_AT, 0L),
            displayName = intent.getStringExtra(PhotoViewerActivity.EXTRA_DISPLAY_NAME).orEmpty(),
            mediaKind = intent.getStringExtra(PhotoViewerActivity.EXTRA_MEDIA_KIND)
                ?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() }
                ?: MediaKind.IMAGE,
            isFavorite = intent.getBooleanExtra(PhotoViewerActivity.EXTRA_IS_FAVORITE, false),
        )

        fun navigationFrom(intent: Intent): List<PhotoRequest> =
            IntentCompat.getParcelableArrayListExtra(intent, PhotoViewerActivity.EXTRA_NAVIGATION, Bundle::class.java)
                .orEmpty()
                .map { value -> from(Intent().putExtras(value)) }
    }
}
