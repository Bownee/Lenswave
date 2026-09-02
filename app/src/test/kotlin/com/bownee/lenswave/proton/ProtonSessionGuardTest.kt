package com.bownee.lenswave.proton

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonSessionGuardTest {
    @Test
    fun accountTransitionWaitsForThePreviousUsersActiveOperation() = runBlocking {
        val guard = ProtonSessionGuard()
        val userA = UserId("a")
        val userB = UserId("b")
        guard.activate(userA) { }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = async(Dispatchers.Default) {
            guard.withActiveSession(userA) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val transitioned = CompletableDeferred<Unit>()
        val transition = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            guard.activate(userB) { transitioned.complete(Unit) }
        }

        assertFalse(transitioned.isCompleted)
        release.complete(Unit)
        operation.await()
        transition.await()
        assertTrue(transitioned.isCompleted)
        assertTrue(guard.isActive(userB))
    }

    @Test
    fun activationAndDisconnectEnforceSessionOwnership() = runBlocking {
        val guard = ProtonSessionGuard()
        val userA = UserId("a")
        val userB = UserId("b")

        guard.activate(userA) { previous -> assertEquals(null, previous) }
        assertEquals("a", guard.withActiveSession(userA) { "a" })

        guard.activate(userB) { previous -> assertEquals(userA, previous) }
        assertFalse(guard.isActive(userA))
        assertTrue(guard.isActive(userB))
        assertThrows(ProtonSessionChangedException::class.java) {
            runBlocking { guard.withActiveSession(userA) { error("must not run") } }
        }

        guard.disconnect(userB) { wasActive -> assertTrue(wasActive) }
        assertFalse(guard.isActive(userB))
    }

    @Test
    fun failedActivationLeavesASafeDisconnectedStateAndCanRetry() = runBlocking {
        val guard = ProtonSessionGuard()
        val userA = UserId("a")
        val userB = UserId("b")
        guard.activate(userA) { }

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                guard.activate(userB) { error("transition failed") }
            }
        }
        assertFalse(guard.isActive(userA))
        assertFalse(guard.isActive(userB))
        assertThrows(ProtonSessionChangedException::class.java) {
            runBlocking { guard.withActiveSession(userA) { error("must not run") } }
        }

        var retries = 0
        guard.activate(userB) { previous ->
            assertEquals(null, previous)
            retries++
        }
        assertEquals(1, retries)
        assertTrue(guard.isActive(userB))
    }

    @Test
    fun workNamesDoNotShareJavaHashCollisions() {
        val first = ProtonWorkNames.thumbnails(UserId("Aa"))
        val second = ProtonWorkNames.thumbnails(UserId("BB"))

        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertFalse(first == second)
    }
}
