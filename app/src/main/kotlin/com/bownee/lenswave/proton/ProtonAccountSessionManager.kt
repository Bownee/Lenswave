package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.proton.core.account.domain.entity.Account
import me.proton.core.account.domain.entity.isReady
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.accountmanager.domain.getPrimaryAccount
import me.proton.core.domain.entity.UserId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class ProtonAccountSessionState(
    val account: Account? = null,
    val activeUserId: UserId? = null,
    val initialized: Boolean = false,
    val transitioning: Boolean = false,
)

/** Owns every process-wide Proton account transition, including failure recovery. */
@Singleton
class ProtonAccountSessionManager internal constructor(
    private val primaryAccount: Flow<Account?>,
    private val transitionCoordinator: ProtonAccountTransitionCoordinator,
    private val reportFailure: (LenswaveOperation, Throwable) -> Unit,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        accountManager: AccountManager,
        transitionCoordinator: ProtonAccountTransitionCoordinator,
    ) : this(
        primaryAccount = accountManager.getPrimaryAccount(),
        transitionCoordinator = transitionCoordinator,
        reportFailure = { operation, error -> LenswaveDiagnostics.reportFailure(operation, error) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val started = AtomicBoolean()
    private val mutableState = MutableStateFlow(ProtonAccountSessionState())
    private var observedUserId: UserId? = null

    /** Whether the last observation that reached the coordinator saw no account at all. */
    private var observedAccountAbsent = false

    val state: StateFlow<ProtonAccountSessionState> = mutableState.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { observeAccounts() }
    }

    private suspend fun observeAccounts() {
        var retryDelayMillis = INITIAL_RETRY_MILLIS
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                primaryAccount.collectLatest(::transitionTo)
                retryDelayMillis = INITIAL_RETRY_MILLIS
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportFailure(LenswaveOperation.ACCOUNT_OBSERVER, error)
                delay(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
            }
        }
    }

    /**
     * The first observation always reaches the coordinator, even when it names the account
     * already observed (none): that is where the residue of an account signed out in a previous
     * process is swept. Later observations of the same account only refresh the details.
     *
     * An account that is present but not ready (still loading, or waiting for a second factor)
     * reads as no active account, but it is not absent: the coordinator is told the difference
     * so it does not sweep that account's caches as if it had been signed out.
     */
    private suspend fun transitionTo(account: Account?) {
        val nextUserId = account?.takeIf(Account::isReady)?.userId
        val accountAbsent = account == null
        // A loading account and no account both read as no active user, but only the latter
        // sweeps, so an observation that moves between the two still reaches the coordinator.
        val unchanged = nextUserId != null || accountAbsent == observedAccountAbsent
        if (observedUserId == nextUserId && unchanged && mutableState.value.initialized) {
            mutableState.value =
                ProtonAccountSessionState(
                    account = account,
                    activeUserId = nextUserId,
                    initialized = true,
                )
            return
        }

        mutableState.value =
            ProtonAccountSessionState(
                account = account,
                initialized = mutableState.value.initialized,
                transitioning = true,
            )
        var retryDelayMillis = INITIAL_RETRY_MILLIS
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                transitionCoordinator.transition(observedUserId, nextUserId, accountAbsent)
                observedUserId = nextUserId
                observedAccountAbsent = accountAbsent
                mutableState.value =
                    ProtonAccountSessionState(
                        account = account,
                        activeUserId = nextUserId,
                        initialized = true,
                    )
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportFailure(LenswaveOperation.ACCOUNT_TRANSITION, error)
                mutableState.value = mutableState.value.copy(transitioning = true)
                delay(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
            }
        }
    }

    private companion object {
        const val INITIAL_RETRY_MILLIS = 500L
        const val MAX_RETRY_MILLIS = 30_000L
    }
}
