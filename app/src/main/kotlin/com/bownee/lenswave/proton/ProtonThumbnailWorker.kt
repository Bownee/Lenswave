package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.domain.entity.UserId
import java.util.concurrent.TimeUnit

class ProtonThumbnailWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val userId = inputData.getString(KEY_USER_ID) ?: return Result.failure()
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            RepositoryEntryPoint::class.java,
        )
        val repository = entryPoint.repository()
        return try {
            val requestedUserId = UserId(userId)
            val session = withTimeoutOrNull(SESSION_READY_TIMEOUT_MILLIS) {
                entryPoint.accountSessionManager().state.first { state ->
                    state.initialized && !state.transitioning
                }
            } ?: return Result.retry()
            if (session.activeUserId != requestedUserId) {
                return Result.failure()
            }
            val finished = withTimeoutOrNull(MAX_RUN_MILLIS) {
                repository.syncThumbnails(
                    requestedUserId,
                    maxThumbnailDownloads = MAX_TIMELINE_DOWNLOADS_PER_RUN,
                )
                repository.syncAlbums(
                    requestedUserId,
                    maxThumbnailDownloads = MAX_ALBUM_DOWNLOADS_PER_RUN,
                )
                true
            } == true
            if (!finished) return Result.retry()
            val complete = repository.state.value.errorMessage == null &&
                repository.albumsState.value.errorMessage == null &&
                repository.state.value.userId == userId &&
                repository.albumsState.value.userId == userId &&
                repository.state.value.photos.all(ProtonGalleryPhoto::hasThumbnail) &&
                repository.albumsState.value.albums.all { album ->
                    album.coverPhotoNodeUid == null || album.hasCoverThumbnail
                }
            when (ProtonThumbnailWorkPolicy.decide(runAttemptCount, complete)) {
                ProtonThumbnailWorkDecision.SUCCESS -> Result.success()
                ProtonThumbnailWorkDecision.RETRY -> Result.retry()
                ProtonThumbnailWorkDecision.FAILURE -> Result.failure()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure("thumbnail-worker", error)
            when (ProtonThumbnailWorkPolicy.decide(runAttemptCount, complete = false)) {
                ProtonThumbnailWorkDecision.RETRY -> Result.retry()
                else -> Result.failure()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RepositoryEntryPoint {
        fun repository(): ProtonPhotoGateway
        fun accountSessionManager(): ProtonAccountSessionManager
    }

    companion object {
        const val KEY_USER_ID = "user-id"
        private const val SESSION_READY_TIMEOUT_MILLIS = 30_000L
        private const val MAX_RUN_MILLIS = 8L * 60L * 1_000L
        private const val MAX_TIMELINE_DOWNLOADS_PER_RUN = 200
        private const val MAX_ALBUM_DOWNLOADS_PER_RUN = 100

        fun request(userId: UserId): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ProtonThumbnailWorker>()
                .setInputData(workDataOf(KEY_USER_ID to userId.id))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()
    }
}

internal enum class ProtonThumbnailWorkDecision {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal object ProtonThumbnailWorkPolicy {
    const val MAX_ATTEMPTS = 5

    fun decide(runAttemptCount: Int, complete: Boolean): ProtonThumbnailWorkDecision = when {
        complete -> ProtonThumbnailWorkDecision.SUCCESS
        runAttemptCount + 1 >= MAX_ATTEMPTS -> ProtonThumbnailWorkDecision.FAILURE
        else -> ProtonThumbnailWorkDecision.RETRY
    }
}
