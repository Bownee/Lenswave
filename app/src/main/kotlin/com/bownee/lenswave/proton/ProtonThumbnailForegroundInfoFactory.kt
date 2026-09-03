package com.bownee.lenswave.proton

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.bownee.lenswave.GalleryActivity
import com.bownee.lenswave.R
import java.util.UUID

internal data class ProtonThumbnailNotificationProgress(
    val downloaded: Int,
    val total: Int,
) {
    init {
        require(downloaded >= 0) { "Downloaded thumbnail count cannot be negative" }
        require(total >= 0) { "Total thumbnail count cannot be negative" }
        require(total == 0 || downloaded <= total) {
            "Downloaded thumbnail count cannot exceed the total"
        }
    }

    val isDeterminate: Boolean get() = total > 0
}

internal class ProtonThumbnailNotificationProgressTracker(initialRemaining: Int) {
    private var previousRemaining = initialRemaining
    private var total = initialRemaining

    init {
        require(initialRemaining > 0) { "Initial pending thumbnail count must be positive" }
    }

    val current: ProtonThumbnailNotificationProgress
        get() = progress(previousRemaining)

    fun update(remaining: Int): ProtonThumbnailNotificationProgress {
        require(remaining >= 0) { "Pending thumbnail count cannot be negative" }
        if (remaining > previousRemaining) total += remaining - previousRemaining
        previousRemaining = remaining
        return progress(remaining)
    }

    private fun progress(remaining: Int) = ProtonThumbnailNotificationProgress(
        downloaded = (total - remaining).coerceAtLeast(0),
        total = total,
    )
}

internal class ProtonThumbnailForegroundInfoFactory(
    private val context: Context,
) {
    fun create(workerId: UUID, progress: ProtonThumbnailNotificationProgress): ForegroundInfo {
        createNotificationChannel()
        val openApp = PendingIntent.getActivity(
            context,
            OPEN_APP_REQUEST_CODE,
            Intent(context, GalleryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelWork = WorkManager.getInstance(context).createCancelPendingIntent(workerId)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle(context.getString(R.string.thumbnail_download_notification_title))
            .setContentText(progress.contentText())
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(progress.total, progress.downloaded, !progress.isDeterminate)
            .addAction(R.drawable.ic_close, context.getString(R.string.cancel), cancelWork)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ProtonThumbnailNotificationProgress.contentText(): String =
        if (isDeterminate) {
            context.getString(R.string.thumbnail_download_notification_progress, downloaded, total)
        } else {
            context.getString(R.string.thumbnail_download_notification_detail)
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
