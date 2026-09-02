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
        val attempt = runAttemptCount + 1
        var statusPublished = false
        return try {
            val requestedUserId = UserId(userId)
            val session = withTimeoutOrNull(SESSION_READY_TIMEOUT_MILLIS) {
                entryPoint.accountSessionManager().state.first { state ->
                    state.initialized && !state.transitioning
                }
            } ?: return resolve(repository, ProtonThumbnailWorkIssue.TIMEOUT, publishStatus = false)
            if (session.activeUserId != requestedUserId) {
                return Result.failure()
            }
            repository.updateThumbnailWorkStatus(
                ProtonThumbnailWorkStatus.Running(attempt, ProtonThumbnailWorkPolicy.MAX_ATTEMPTS)
            )
            statusPublished = true
            val finished = withTimeoutOrNull(MAX_RUN_MILLIS) {
                refreshMetadata(repository, requestedUserId)
                hydrateThumbnails(repository, requestedUserId)
                true
            } == true
            if (!finished) return resolve(repository, ProtonThumbnailWorkIssue.TIMEOUT)
            resolve(repository, workIssue(repository, userId))
        } catch (error: CancellationException) {
            if (statusPublished) {
                repository.updateThumbnailWorkStatus(
                    ProtonThumbnailWorkPolicy.retryStatus(
                        runAttemptCount,
                        ProtonThumbnailWorkIssue.INTERRUPTED,
                    )
                )
                reportState("interrupted")
            }
            throw error
        } catch (error: Throwable) {
            LenswaveDiagnostics.reportFailure("thumbnail-worker", error)
            resolve(repository, ProtonThumbnailWorkIssue.ERROR, publishStatus = statusPublished)
        }
    }

    private suspend fun refreshMetadata(repository: ProtonPhotoGateway, userId: UserId) {
        repository.syncAlbums(userId, maxThumbnailDownloads = METADATA_ONLY_THUMBNAIL_LIMIT)
        repository.syncTrash(userId, maxThumbnailDownloads = METADATA_ONLY_THUMBNAIL_LIMIT)
        repository.syncThumbnails(userId, maxThumbnailDownloads = METADATA_ONLY_THUMBNAIL_LIMIT)
    }

    private suspend fun hydrateThumbnails(repository: ProtonPhotoGateway, userId: UserId) {
        repository.syncThumbnails(userId, maxThumbnailDownloads = MAX_TIMELINE_DOWNLOADS_PER_RUN)
        repository.syncAlbums(userId, maxThumbnailDownloads = MAX_ALBUM_DOWNLOADS_PER_RUN)
        repository.syncTrash(userId, maxThumbnailDownloads = MAX_TRASH_DOWNLOADS_PER_RUN)
    }

    private fun workIssue(repository: ProtonPhotoGateway, userId: String): ProtonThumbnailWorkIssue? = when {
        repository.state.value.errorMessage != null ||
            repository.albumsState.value.errorMessage != null ||
            repository.trashState.value.errorMessage != null -> ProtonThumbnailWorkIssue.ERROR
        repository.state.value.userId != userId ||
            repository.albumsState.value.userId != userId ||
            repository.trashState.value.userId != userId ||
            !repository.albumsState.value.hasLoaded ||
            !repository.trashState.value.hasLoaded -> ProtonThumbnailWorkIssue.INCOMPLETE
        repository.state.value.photos.any { photo -> !photo.hasThumbnail } -> ProtonThumbnailWorkIssue.INCOMPLETE
        repository.albumsState.value.albums.any { album ->
            album.coverPhotoNodeUid != null && !album.hasCoverThumbnail
        } -> ProtonThumbnailWorkIssue.INCOMPLETE
        repository.trashState.value.photos.any { photo -> !photo.hasThumbnail } ->
            ProtonThumbnailWorkIssue.INCOMPLETE
        else -> null
    }

    private fun resolve(
        repository: ProtonPhotoGateway,
        issue: ProtonThumbnailWorkIssue? = null,
        publishStatus: Boolean = true,
    ): Result {
        val resolution = ProtonThumbnailWorkPolicy.resolve(runAttemptCount, issue)
        if (publishStatus) repository.updateThumbnailWorkStatus(resolution.status)
        reportState(resolution.diagnosticState)
        return when (resolution.decision) {
            ProtonThumbnailWorkDecision.SUCCESS -> Result.success()
            ProtonThumbnailWorkDecision.RETRY -> Result.retry()
            ProtonThumbnailWorkDecision.FAILURE -> Result.failure()
        }
    }

    private fun reportState(state: String) {
        LenswaveDiagnostics.reportState(
            operation = "thumbnail-worker",
            state = state,
            attempt = runAttemptCount + 1,
            maximumAttempts = ProtonThumbnailWorkPolicy.MAX_ATTEMPTS,
        )
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
        private const val MAX_RUN_MILLIS = 3L * 60L * 1_000L + 30_000L
        private const val METADATA_ONLY_THUMBNAIL_LIMIT = 0
        private const val MAX_TIMELINE_DOWNLOADS_PER_RUN = 60
        private const val MAX_ALBUM_DOWNLOADS_PER_RUN = 20
        private const val MAX_TRASH_DOWNLOADS_PER_RUN = 40

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
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30L, TimeUnit.SECONDS)
                .build()
    }
}

internal enum class ProtonThumbnailWorkDecision {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal data class ProtonThumbnailWorkResolution(
    val decision: ProtonThumbnailWorkDecision,
    val status: ProtonThumbnailWorkStatus?,
    val diagnosticState: String,
)

internal object ProtonThumbnailWorkPolicy {
    const val MAX_ATTEMPTS = 25

    fun resolve(
        runAttemptCount: Int,
        issue: ProtonThumbnailWorkIssue?,
    ): ProtonThumbnailWorkResolution {
        val attempt = runAttemptCount + 1
        if (issue == null) {
            return ProtonThumbnailWorkResolution(
                ProtonThumbnailWorkDecision.SUCCESS,
                status = null,
                diagnosticState = "complete",
            )
        }
        if (attempt >= MAX_ATTEMPTS) {
            return ProtonThumbnailWorkResolution(
                ProtonThumbnailWorkDecision.FAILURE,
                ProtonThumbnailWorkStatus.Stopped(attempt, MAX_ATTEMPTS, issue),
                diagnosticState = "stopped-${issue.diagnosticName()}",
            )
        }
        return ProtonThumbnailWorkResolution(
            ProtonThumbnailWorkDecision.RETRY,
            retryStatus(runAttemptCount, issue),
            diagnosticState = "retry-${issue.diagnosticName()}",
        )
    }

    fun retryStatus(
        runAttemptCount: Int,
        issue: ProtonThumbnailWorkIssue,
    ): ProtonThumbnailWorkStatus.RetryScheduled = ProtonThumbnailWorkStatus.RetryScheduled(
        attempt = (runAttemptCount + 2).coerceAtMost(MAX_ATTEMPTS),
        maximumAttempts = MAX_ATTEMPTS,
        issue = issue,
    )

    private fun ProtonThumbnailWorkIssue.diagnosticName(): String = name.lowercase()
}
