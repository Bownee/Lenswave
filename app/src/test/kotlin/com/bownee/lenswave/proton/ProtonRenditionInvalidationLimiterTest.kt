package com.bownee.lenswave.proton

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonRenditionInvalidationLimiterTest {
    @Test
    fun `a node may be queued again a bounded number of times`() {
        val limiter = ProtonRenditionInvalidationLimiter(maxRequeues = 2)

        assertTrue(limiter.allowsRequeue("u", "n"))
        assertTrue(limiter.allowsRequeue("u", "n"))
        assertFalse(limiter.allowsRequeue("u", "n"))
        assertFalse(limiter.allowsRequeue("u", "n"))
        // Other nodes, and the same node of another user, count on their own.
        assertTrue(limiter.allowsRequeue("u", "other"))
        assertTrue(limiter.allowsRequeue("v", "n"))
    }

    @Test
    fun `forgetting a user starts its nodes over and leaves the others`() {
        val limiter = ProtonRenditionInvalidationLimiter(maxRequeues = 1)
        assertTrue(limiter.allowsRequeue("u", "n"))
        assertTrue(limiter.allowsRequeue("v", "n"))

        limiter.forget("u")

        assertTrue(limiter.allowsRequeue("u", "n"))
        assertFalse(limiter.allowsRequeue("v", "n"))
    }

    @Test
    fun `the count is bounded in nodes, least recently invalidated first out`() {
        val limiter = ProtonRenditionInvalidationLimiter(maxRequeues = 1, maxTracked = 2)
        assertTrue(limiter.allowsRequeue("u", "a"))
        assertTrue(limiter.allowsRequeue("u", "b"))
        // "a" is touched again and stays; "b" is the eldest when "c" arrives.
        assertFalse(limiter.allowsRequeue("u", "a"))
        assertTrue(limiter.allowsRequeue("u", "c"))

        assertTrue(limiter.allowsRequeue("u", "b"))
        assertFalse(limiter.allowsRequeue("u", "c"))
    }
}
