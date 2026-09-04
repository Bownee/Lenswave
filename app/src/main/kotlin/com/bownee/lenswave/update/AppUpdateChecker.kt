package com.bownee.lenswave.update

import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveDispatchers
import com.bownee.lenswave.LenswaveOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

internal data class AvailableUpdate(
    val versionName: String,
)

@Singleton
class AppUpdateChecker
    @Inject
    internal constructor(
        private val client: LatestReleaseClient,
        private val store: AppUpdateStateStore,
        private val clock: LenswaveClock,
        private val dispatchers: LenswaveDispatchers,
    ) {
        private val checkMutex = Mutex()

        internal suspend fun findAvailableUpdate(currentVersionName: String): AvailableUpdate? =
            try {
                withContext(dispatchers.io) {
                    checkMutex.withLock {
                        val currentVersion = SemanticVersion.parse(currentVersionName) ?: return@withLock null
                        val nowMillis = clock.nowMillis()
                        var state = store.read()
                        if (nowMillis >= state.nextCheckAtMillis) {
                            state = refresh(state, nowMillis)
                            store.write(state)
                        }
                        availableUpdate(state, currentVersion, nowMillis)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LenswaveDiagnostics.reportFailure(LenswaveOperation.APP_UPDATE_EVALUATION, error)
                null
            }

        internal suspend fun snooze(versionName: String) {
            try {
                withContext(dispatchers.io) {
                    checkMutex.withLock {
                        val state = store.read()
                        store.write(
                            state.copy(
                                snoozedVersionName = versionName,
                                snoozedUntilMillis = clock.nowMillis() + SNOOZE_MILLIS,
                            ),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LenswaveDiagnostics.reportFailure(LenswaveOperation.APP_UPDATE_SNOOZE, error)
            }
        }

        private fun refresh(
            state: AppUpdateState,
            nowMillis: Long,
        ): AppUpdateState =
            when (
                val result = client.fetch(state.etag)
            ) {
                is LatestReleaseResult.Modified -> {
                    state.copy(
                        latestVersionName = result.versionName,
                        etag = result.etag,
                        nextCheckAtMillis = nowMillis + SUCCESS_INTERVAL_MILLIS,
                    )
                }

                LatestReleaseResult.NotModified -> {
                    state.copy(
                        nextCheckAtMillis = nowMillis + SUCCESS_INTERVAL_MILLIS,
                    )
                }

                LatestReleaseResult.Unavailable -> {
                    state.copy(
                        nextCheckAtMillis = nowMillis + FAILURE_RETRY_MILLIS,
                    )
                }
            }

        private fun availableUpdate(
            state: AppUpdateState,
            currentVersion: SemanticVersion,
            nowMillis: Long,
        ): AvailableUpdate? {
            val latestVersionName = state.latestVersionName ?: return null
            val latestVersion = SemanticVersion.parse(latestVersionName) ?: return null
            if (latestVersion <= currentVersion) return null
            if (state.snoozedVersionName == latestVersionName && nowMillis < state.snoozedUntilMillis) {
                return null
            }
            return AvailableUpdate(latestVersionName)
        }

        private companion object {
            const val HOUR_MILLIS = 60L * 60L * 1_000L
            const val SUCCESS_INTERVAL_MILLIS = 24L * HOUR_MILLIS
            const val FAILURE_RETRY_MILLIS = HOUR_MILLIS
            const val SNOOZE_MILLIS = 7L * 24L * HOUR_MILLIS
        }
    }
