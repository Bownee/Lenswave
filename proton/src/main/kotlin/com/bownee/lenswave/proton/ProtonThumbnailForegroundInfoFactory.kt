package com.bownee.lenswave.proton

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.bownee.lenswave.proton.R
import me.proton.core.domain.entity.UserId
import com.bownee.lenswave.core.R as CoreR

/** Which rendition the background worker is currently downloading. */
internal enum class ProtonDownloadPhase {
    THUMBNAILS,
    PREVIEWS,
}

internal data class ProtonThumbnailNotificationProgress(
    val downloaded: Int,
    val total: Int,
    val phase: ProtonDownloadPhase = ProtonDownloadPhase.THUMBNAILS,
) {
    init {
        require(downloaded >= 0) { "Downloaded thumbnail count cannot be negative" }
        require(total >= 0) { "Total thumbnail count cannot be negative" }
        require(total == 0 || downloaded <= total) {
            "Downloaded thumbnail count cannot exceed the total"
        }
    }

    val isDeterminate: Boolean get() = total > 0
    val remaining: Int get() = total - downloaded
}

/**
 * Counts for both queues. Thumbnails are reported while any is pending; the notification only
 * switches to previews once every thumbnail is stored.
 */
internal data class ProtonThumbnailWorkProgress(
    val stored: Int,
    val pending: Int,
    val previewsStored: Int = 0,
    val previewsPending: Int = 0,
) {
    init {
        require(stored >= 0) { "Stored thumbnail count cannot be negative" }
        require(pending >= 0) { "Pending thumbnail count cannot be negative" }
        require(previewsStored >= 0) { "Stored preview count cannot be negative" }
        require(previewsPending >= 0) { "Pending preview count cannot be negative" }
    }

    val hasPendingWork: Boolean get() = pending > 0 || previewsPending > 0

    fun notificationProgress() =
        if (ProtonThumbnailNotificationPolicy.phase(pending, previewsPending) == ProtonDownloadPhase.PREVIEWS) {
            ProtonThumbnailNotificationProgress(
                downloaded = previewsStored,
                total = previewsStored + previewsPending,
                phase = ProtonDownloadPhase.PREVIEWS,
            )
        } else {
            ProtonThumbnailNotificationProgress(
                downloaded = stored,
                total = stored + pending,
            )
        }
}

internal object ProtonThumbnailNotificationPolicy {
    /** Previews are only announced once no thumbnail is left; thumbnails always come first. */
    fun phase(
        thumbnailsPending: Int,
        previewsPending: Int,
    ): ProtonDownloadPhase =
        if (thumbnailsPending == 0 && previewsPending > 0) {
            ProtonDownloadPhase.PREVIEWS
        } else {
            ProtonDownloadPhase.THUMBNAILS
        }
}

/**
 * Builds the worker's progress notification. One instance lives for one worker run and is asked
 * every 1.5 s, so the channel is created once and both PendingIntents are memoized: neither
 * depends on the progress, and registering PendingIntents is a binder call each time.
 *
 * The action is a pause, not WorkManager's cancel: cancelling one worker id stopped that run
 * only, and the next resume started downloads again. The pause is remembered for the user
 * ([ProtonThumbnailPauseStore]) until a manual refresh lifts it.
 */
internal class ProtonThumbnailForegroundInfoFactory(
    private val context: Context,
    userId: UserId,
) {
    private var channelCreated = false
    private val openApp by lazy {
        PendingIntent.getActivity(
            context,
            OPEN_APP_REQUEST_CODE,
            requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) {
                "Lenswave declares a launcher activity"
            }.apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private val pauseDownloads by lazy { ProtonThumbnailPauseReceiver.pendingIntent(context, userId) }

    fun create(progress: ProtonThumbnailNotificationProgress): ForegroundInfo {
        if (!channelCreated) {
            createNotificationChannel()
            channelCreated = true
        }
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(CoreR.drawable.ic_cloud)
                .setContentTitle(context.getString(R.string.thumbnail_download_notification_title))
                .setContentText(progress.contentText())
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(progress.total, progress.downloaded, !progress.isDeterminate)
                .addAction(
                    CoreR.drawable.ic_close,
                    context.getString(R.string.thumbnail_download_notification_pause),
                    pauseDownloads,
                ).build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ProtonThumbnailNotificationProgress.contentText(): String =
        when {
            !isDeterminate -> {
                context.getString(R.string.thumbnail_download_notification_detail)
            }

            phase == ProtonDownloadPhase.PREVIEWS -> {
                context.getString(R.string.preview_download_notification_progress, downloaded, total)
            }

            else -> {
                context.getString(R.string.thumbnail_download_notification_progress, downloaded, remaining)
            }
        }

    private fun createNotificationChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.thumbnail_download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.thumbnail_download_channel_description)
                setShowBadge(false)
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "proton-thumbnail-downloads"
        const val NOTIFICATION_ID = 1_204
        const val OPEN_APP_REQUEST_CODE = 1_205
    }
}
