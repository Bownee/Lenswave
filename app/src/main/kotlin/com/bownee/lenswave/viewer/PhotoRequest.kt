package com.bownee.lenswave.viewer

import android.content.Intent
import android.os.Parcel
import android.os.Parcelable
import androidx.core.content.IntentCompat
import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.MediaKind
import com.bownee.lenswave.gallery.PhotoTarget

/**
 * One entry of the viewer's navigation list. Parcelled directly: the navigation window used to
 * go through a throwaway Intent and Bundle per entry on every open, then be parsed back through
 * as many Intents again.
 */
data class PhotoRequest(
    val stableId: String,
    val nodeUid: String,
    val userId: String,
    val capturedAt: Long,
    val displayName: String,
    val mediaKind: MediaKind = MediaKind.IMAGE,
    val isFavorite: Boolean = false,
) : Parcelable {
    fun toPhotoTarget(): PhotoTarget = PhotoTarget(stableId, nodeUid)

    fun withFavorite(favorite: Boolean): PhotoRequest = copy(isFavorite = favorite)

    fun writeTo(intent: Intent): Intent =
        intent.apply {
            putExtra(PhotoViewerActivity.EXTRA_PROTON_NODE_UID, nodeUid)
            putExtra(PhotoViewerActivity.EXTRA_USER_ID, userId)
            putExtra(PhotoViewerActivity.EXTRA_CAPTURED_AT, capturedAt)
            putExtra(PhotoViewerActivity.EXTRA_DISPLAY_NAME, displayName)
            putExtra(PhotoViewerActivity.EXTRA_STABLE_ID, stableId)
            putExtra(PhotoViewerActivity.EXTRA_MEDIA_KIND, mediaKind.name)
            putExtra(PhotoViewerActivity.EXTRA_IS_FAVORITE, isFavorite)
        }

    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeString(stableId)
        dest.writeString(nodeUid)
        dest.writeString(userId)
        dest.writeLong(capturedAt)
        dest.writeString(displayName)
        dest.writeString(mediaKind.name)
        dest.writeInt(if (isFavorite) 1 else 0)
    }

    companion object {
        fun from(
            photo: GalleryAsset,
            userId: String,
        ): PhotoRequest =
            PhotoRequest(
                stableId = photo.stableId,
                nodeUid = photo.nodeUid,
                userId = userId,
                capturedAt = photo.capturedAtEpochMillis,
                displayName = photo.displayName,
                mediaKind = photo.mediaKind,
                isFavorite = photo.isFavorite,
            )

        fun from(intent: Intent): PhotoRequest =
            PhotoRequest(
                stableId = requireNotNull(intent.getStringExtra(PhotoViewerActivity.EXTRA_STABLE_ID)),
                nodeUid = requireNotNull(intent.getStringExtra(PhotoViewerActivity.EXTRA_PROTON_NODE_UID)),
                userId = requireNotNull(intent.getStringExtra(PhotoViewerActivity.EXTRA_USER_ID)),
                capturedAt = intent.getLongExtra(PhotoViewerActivity.EXTRA_CAPTURED_AT, 0L),
                displayName = intent.getStringExtra(PhotoViewerActivity.EXTRA_DISPLAY_NAME).orEmpty(),
                mediaKind = mediaKind(intent.getStringExtra(PhotoViewerActivity.EXTRA_MEDIA_KIND)),
                isFavorite = intent.getBooleanExtra(PhotoViewerActivity.EXTRA_IS_FAVORITE, false),
            )

        fun navigationFrom(intent: Intent): List<PhotoRequest> =
            IntentCompat
                .getParcelableArrayListExtra(intent, PhotoViewerActivity.EXTRA_NAVIGATION, PhotoRequest::class.java)
                .orEmpty()

        private fun mediaKind(name: String?): MediaKind =
            name?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() } ?: MediaKind.IMAGE

        @JvmField
        val CREATOR =
            object : Parcelable.Creator<PhotoRequest> {
                override fun createFromParcel(source: Parcel): PhotoRequest =
                    PhotoRequest(
                        stableId = requireNotNull(source.readString()),
                        nodeUid = requireNotNull(source.readString()),
                        userId = requireNotNull(source.readString()),
                        capturedAt = source.readLong(),
                        displayName = source.readString().orEmpty(),
                        mediaKind = mediaKind(source.readString()),
                        isFavorite = source.readInt() != 0,
                    )

                override fun newArray(size: Int): Array<PhotoRequest?> = arrayOfNulls(size)
            }
    }
}
