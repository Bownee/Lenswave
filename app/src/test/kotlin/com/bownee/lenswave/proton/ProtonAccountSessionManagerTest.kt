package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveOperation
import com.bownee.lenswave.gallery.ProtonSessionLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.proton.core.account.domain.entity.Account
import me.proton.core.account.domain.entity.AccountDetails
import me.proton.core.account.domain.entity.AccountState
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the manager over a hand-fed primary-account flow and the real transition coordinator
 * with fake collaborators, on the test scheduler so retry delays are virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtonAccountSessionManagerTest {
    private val accounts = MutableStateFlow<Account?>(null)
    private val events = mutableListOf<String>()
    private val failures = mutableListOf<LenswaveOperation>()
    private var failRetains = 0

    @Test
    fun `a cache cleaner that throws once is retried and the account ends up active`() =
        runTest {
            failRetains = 1
            val manager = manager(backgroundScope)
            accounts.value = readyAccount("a")

            manager.start()
            runCurrent()

            assertTrue(manager.state.value.transitioning)
            assertNull(manager.state.value.activeUserId)
            assertEquals(listOf(LenswaveOperation.ACCOUNT_TRANSITION), failures)

            advanceTimeBy(1_000L)
            runCurrent()

            val state = manager.state.value
            assertFalse(state.transitioning)
            assertTrue(state.initialized)
            assertEquals(UserId("a"), state.activeUserId)
            // The second attempt repeats the whole barrier, so nothing is left half done.
            assertEquals(
                listOf("activate:a", "retain:a", "activate:a", "retain:a", "enqueue:a"),
                events,
            )
        }

    @Test
    fun `signing out tears the account down in order and publishes no active account`() =
        runTest {
            val manager = manager(backgroundScope)
            accounts.value = readyAccount("a")
            manager.start()
            runCurrent()
            events.clear()

            accounts.value = null
            runCurrent()

            assertEquals(listOf("cancel:a", "disconnect:a", "retain:null"), events)
            val state = manager.state.value
            assertNull(state.activeUserId)
            assertTrue(state.initialized)
            assertFalse(state.transitioning)
        }

    @Test
    fun `start is idempotent`() =
        runTest {
            val manager = manager(backgroundScope)
            accounts.value = readyAccount("a")

            manager.start()
            manager.start()
            runCurrent()

            assertEquals(listOf("activate:a", "retain:a", "enqueue:a"), events)
        }

    @Test
    fun `a launch without an account still sweeps the residue of the previous sign-out`() =
        runTest {
            val manager = manager(backgroundScope)

            manager.start()
            runCurrent()

            assertEquals(listOf("retain:null"), events)
            assertTrue(manager.state.value.initialized)
            assertFalse(manager.state.value.transitioning)

            accounts.value = null
            runCurrent()

            assertEquals(listOf("retain:null"), events)
        }

    @Test
    fun `an account that is not ready reads as signed out`() =
        runTest {
            val manager = manager(backgroundScope)
            accounts.value = readyAccount("a").copy(state = AccountState.Disabled)

            manager.start()
            runCurrent()

            assertNull(manager.state.value.activeUserId)
            assertTrue(manager.state.value.initialized)
            assertTrue(events.none { it.startsWith("activate") })
        }

    private fun manager(scope: CoroutineScope) =
        ProtonAccountSessionManager(
            primaryAccount = accounts,
            transitionCoordinator =
                ProtonAccountTransitionCoordinator(
                    sessionLifecycle = FakeSessionLifecycle(events),
                    cacheCleaner =
                        ProtonAccountCacheCleaner { userId ->
                            events += "retain:$userId"
                            if (failRetains-- > 0) error("cache directory is busy")
                        },
                    thumbnailScheduler = FakeThumbnailScheduler(events),
                ),
            reportFailure = { operation, _ -> failures += operation },
            scope = scope,
        )

    private fun readyAccount(userId: String) =
        Account(
            userId = UserId(userId),
            username = userId,
            email = null,
            state = AccountState.Ready,
            sessionId = null,
            sessionState = null,
            details = AccountDetails(null, null),
        )

    private class FakeThumbnailScheduler(
        private val events: MutableList<String>,
    ) : ProtonThumbnailScheduler {
        override fun enqueue(userId: UserId) {
            events += "enqueue:${userId.id}"
        }

        override fun enqueueWhileCharging(userId: UserId) {
            events += "enqueue-charging:${userId.id}"
        }

        override suspend fun resume(userId: UserId) {
            events += "resume:${userId.id}"
        }

        override suspend fun restart(userId: UserId) {
            events += "restart:${userId.id}"
        }

        override suspend fun cancelAndAwait(userId: UserId) {
            events += "cancel:${userId.id}"
        }
    }

    private class FakeSessionLifecycle(
        private val events: MutableList<String>,
    ) : ProtonSessionLifecycle {
        override suspend fun activate(userId: UserId) {
            events += "activate:${userId.id}"
        }

        override suspend fun disconnect(userId: UserId) {
            events += "disconnect:${userId.id}"
        }
    }
}
