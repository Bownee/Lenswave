package com.bownee.lenswave.update

import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveDispatchers
import com.bownee.lenswave.LenswaveOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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

        // The startup check runs in this singleton's scope, not the activity's: a rotation while it
        // is in flight recreates the activity, which awaits the same check instead of starting or
        // skipping one.
        private val startupScope = CoroutineScope(SupervisorJob() + dispatchers.io)
        private val startupMutex = Mutex()
        private var startupCheck: Deferred<AvailableUpdate?>? = null

        @Volatile private var startupUpdateShown = false

        /**
         * The result of the process's one startup check. The caller that receives the update owns
         * showing it and calls [markStartupUpdateShown] once it has taken it over; from then on
         * every caller (a recreated activity, once the dialog has been shown or snoozed) gets null.
         * Nothing is marked here: a caller cancelled between receiving the update and storing it
         * would otherwise lose the prompt for the whole process.
         */
        internal suspend fun awaitStartupUpdate(currentVersionName: String): AvailableUpdate? {
            val check =
                startupMutex.withLock {
                    startupCheck
                        ?: startupScope.async { findAvailableUpdate(currentVersionName) }.also { startupCheck = it }
                }
            val update = check.await() ?: return null
            return if (startupUpdateShown) null else update
        }

        /** The update from [awaitStartupUpdate] is in the caller's hands; later callers receive null. */
        internal fun markStartupUpdateShown() {
            startupUpdateShown = true
        }

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
                    // Only a tag that parses is worth keeping; a malformed one is left out of the
                    // store (and its ETag with it, so the next check reads the release afresh).
                    if (SemanticVersion.parse(result.versionName) == null) {
                        state.copy(nextCheckAtMillis = nowMillis + SUCCESS_INTERVAL_MILLIS)
                    } else {
                        state.copy(
                            latestVersionName = result.versionName,
                            etag = result.etag,
                            nextCheckAtMillis = nowMillis + SUCCESS_INTERVAL_MILLIS,
                        )
                    }
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
