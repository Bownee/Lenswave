package com.bownee.lenswave

import android.content.Intent
import android.os.Bundle
import com.bownee.lenswave.gallery.GalleryAsset
import com.bownee.lenswave.gallery.PhotoSource
import com.bownee.lenswave.gallery.PhotoTarget

sealed interface PhotoRequest {
    val stableId: String
    val source: PhotoSource
    val capturedAt: Long
    val displayName: String
    val isTrashed: Boolean

    data class Device(
        override val stableId: String,
        val uri: String,
        val protonBackingNodeUids: List<String>,
        override val capturedAt: Long,
        override val displayName: String,
        override val isTrashed: Boolean,
    ) : PhotoRequest {
        override val source = PhotoSource.DEVICE
    }

    data class Proton(
        override val stableId: String,
        val protonNodeUid: String,
        val userId: String,
        override val capturedAt: Long,
        override val displayName: String,
        override val isTrashed: Boolean,
    ) : PhotoRequest {
        override val source = PhotoSource.PROTON
    }

    fun toPhotoTarget(): PhotoTarget = when (this) {
        is Device -> PhotoTarget.Device(stableId, uri, isTrashed)
        is Proton -> PhotoTarget.Proton(stableId, protonNodeUid, isTrashed)
    }

    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(PhotoViewerActivity.EXTRA_SOURCE, source.name)
        when (this@PhotoRequest) {
            is Device -> {
                putExtra(PhotoViewerActivity.EXTRA_URI, uri)
                putStringArrayListExtra(
                    PhotoViewerActivity.EXTRA_PROTON_BACKING_NODE_UIDS,
                    ArrayList(protonBackingNodeUids),
                )
                removeExtra(PhotoViewerActivity.EXTRA_PROTON_NODE_UID)
                removeExtra(PhotoViewerActivity.EXTRA_USER_ID)
            }
            is Proton -> {
                putExtra(PhotoViewerActivity.EXTRA_PROTON_NODE_UID, protonNodeUid)
                putExtra(PhotoViewerActivity.EXTRA_USER_ID, userId)
                removeExtra(PhotoViewerActivity.EXTRA_URI)
                removeExtra(PhotoViewerActivity.EXTRA_PROTON_BACKING_NODE_UIDS)
            }
        }
        putExtra(PhotoViewerActivity.EXTRA_CAPTURED_AT, capturedAt)
        putExtra(PhotoViewerActivity.EXTRA_DISPLAY_NAME, displayName)
        putExtra(PhotoViewerActivity.EXTRA_STABLE_ID, stableId)
        putExtra(PhotoViewerActivity.EXTRA_IS_TRASHED, isTrashed)
    }

    fun toBundle(): Bundle = Bundle().also { value ->
        writeTo(Intent()).extras?.let(value::putAll)
    }

    companion object {
        fun from(photo: GalleryAsset, userId: String?): PhotoRequest = when (photo.source) {
            PhotoSource.DEVICE -> Device(
                stableId = photo.stableId,
                uri = requireNotNull(photo.uri),
                protonBackingNodeUids = photo.protonBackingNodeUids,
                capturedAt = photo.capturedAtEpochMillis,
                displayName = photo.displayName,
                isTrashed = photo.isTrashed,
            )
            PhotoSource.PROTON -> Proton(
                stableId = photo.stableId,
                protonNodeUid = requireNotNull(photo.protonNodeUid),
                userId = requireNotNull(userId),
                capturedAt = photo.capturedAtEpochMillis,
                displayName = photo.displayName,
                isTrashed = photo.isTrashed,
            )
        }

        fun from(intent: Intent): PhotoRequest {
            val stableId = requireNotNull(intent.getStringExtra(PhotoViewerActivity.EXTRA_STABLE_ID))
            val capturedAt = intent.getLongExtra(PhotoViewerActivity.EXTRA_CAPTURED_AT, 0L)
            val displayName = intent.getStringExtra(PhotoViewerActivity.EXTRA_DISPLAY_NAME).orEmpty()
            val isTrashed = intent.getBooleanExtra(PhotoViewerActivity.EXTRA_IS_TRASHED, false)
            return when (PhotoSource.valueOf(requireNotNull(intent.getStringExtra(PhotoViewerActivity.EXTRA_SOURCE)))) {
                PhotoSource.DEVICE -> Device(
                    stableId = stableId,
                    uri = requireNotNull(intent.getStringExtra(PhotoViewerActivity.EXTRA_URI)),
                    protonBackingNodeUids = intent.getStringArrayListExtra(
                        PhotoViewerActivity.EXTRA_PROTON_BACKING_NODE_UIDS,
                    ).orEmpty(),
                    capturedAt = capturedAt,
                    displayName = displayName,
                    isTrashed = isTrashed,
                )
                PhotoSource.PROTON -> Proton(
                    stableId = stableId,
                    protonNodeUid = requireNotNull(
                        intent.getStringExtra(PhotoViewerActivity.EXTRA_PROTON_NODE_UID),
                    ),
                    userId = requireNotNull(intent.getStringExtra(PhotoViewerActivity.EXTRA_USER_ID)),
                    capturedAt = capturedAt,
                    displayName = displayName,
                    isTrashed = isTrashed,
                )
            }
        }

        @Suppress("DEPRECATION")
        fun navigationFrom(intent: Intent): List<PhotoRequest> =
            intent.getParcelableArrayListExtra<Bundle>(PhotoViewerActivity.EXTRA_NAVIGATION)
                .orEmpty()
                .map { value -> from(Intent().putExtras(value)) }
    }
}
