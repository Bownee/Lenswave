package com.bownee.lenswave.proton

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.bownee.lenswave.storage.AtomicFileStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import me.proton.core.domain.entity.UserId

/**
 * The target of the notification's pause action. WorkManager's own cancel intent only cancels
 * one worker id, and nothing remembers that the user asked; this one records the pause in
 * [ProtonThumbnailPauseStore] first and then cancels the unique work, so the run stops now and
 * the next resume or refresh does not start it again. Not exported: only this app's
 * notification holds the PendingIntent.
 */
class ProtonThumbnailPauseReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_PAUSE) return
        val userId = intent.getStringExtra(EXTRA_USER_ID)?.let(::UserId) ?: return
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, PauseEntryPoint::class.java)
        entryPoint.pauseStore().setPaused(userId, paused = true)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(ProtonWorkNames.thumbnails(userId))
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface PauseEntryPoint {
        fun pauseStore(): ProtonThumbnailPauseStore
    }

    companion object {
        private const val ACTION_PAUSE = "com.bownee.lenswave.action.PAUSE_THUMBNAIL_DOWNLOADS"
        private const val EXTRA_USER_ID = "user-id"

        /**
         * The request code is what tells two PendingIntents with the same action apart: with a
         * constant code and FLAG_UPDATE_CURRENT, the pause action of one account overwrote the
         * extras of another's, so the notification paused whichever account registered last.
         */
        fun requestCode(userId: UserId): Int = AtomicFileStore.safeName(userId.id).hashCode()

        fun pendingIntent(
            context: Context,
            userId: UserId,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode(userId),
                Intent(context, ProtonThumbnailPauseReceiver::class.java)
                    .setAction(ACTION_PAUSE)
                    .putExtra(EXTRA_USER_ID, userId.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
