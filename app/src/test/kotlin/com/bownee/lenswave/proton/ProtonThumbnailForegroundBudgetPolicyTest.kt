package com.bownee.lenswave.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonThumbnailForegroundBudgetPolicyTest {
    private val hour = 60L * 60L * 1_000L
    private val window = ProtonThumbnailForegroundBudgetPolicy.WINDOW_MILLIS
    private val now = 100L * hour

    @Test
    fun `runs outside the window are pruned and the rest sum up`() {
        val runs =
            listOf(
                ProtonForegroundRun(endedAtMillis = now - window - 1L, durationMillis = hour),
                ProtonForegroundRun(endedAtMillis = now - window + 1L, durationMillis = 2 * hour),
                ProtonForegroundRun(endedAtMillis = now - hour, durationMillis = hour),
                // A run from a clock that jumped forward is not counted against today.
                ProtonForegroundRun(endedAtMillis = now + hour, durationMillis = hour),
            )

        assertEquals(3 * hour, ProtonThumbnailForegroundBudgetPolicy.usedMillis(runs, now))
        assertEquals(
            listOf(now - window + 1L, now - hour),
            ProtonThumbnailForegroundBudgetPolicy.prune(runs, now).map(ProtonForegroundRun::endedAtMillis),
        )
    }

    @Test
    fun `only the newest runs are remembered`() {
        val many =
            (1..(ProtonThumbnailForegroundBudgetPolicy.MAX_RECORDED_RUNS + 10)).map { index ->
                ProtonForegroundRun(endedAtMillis = now - index * 1_000L, durationMillis = 1L)
            }

        val kept = ProtonThumbnailForegroundBudgetPolicy.record(many, ProtonForegroundRun(now, 5L), now)

        assertEquals(ProtonThumbnailForegroundBudgetPolicy.MAX_RECORDED_RUNS, kept.size)
        assertEquals(now, kept.last().endedAtMillis)
        assertEquals(
            now - (ProtonThumbnailForegroundBudgetPolicy.MAX_RECORDED_RUNS - 1) * 1_000L,
            kept.first().endedAtMillis,
        )
    }

    @Test
    fun `a run that fits the budget needs no delay`() {
        val runs = listOf(ProtonForegroundRun(endedAtMillis = now - hour, durationMillis = 2 * hour))

        assertEquals(0L, ProtonThumbnailForegroundBudgetPolicy.delayUntilAffordableMillis(runs, now))
        assertEquals(0L, ProtonThumbnailForegroundBudgetPolicy.delayUntilAffordableMillis(emptyList(), now))
    }

    @Test
    fun `a run that would overspend waits for the oldest runs to leave the window`() {
        val runs =
            listOf(
                ProtonForegroundRun(endedAtMillis = now - 20 * hour, durationMillis = 2 * hour),
                ProtonForegroundRun(endedAtMillis = now - 10 * hour, durationMillis = 2 * hour),
            )

        // Four hours used; the next two-hour run fits once the run from twenty hours ago is gone.
        assertEquals(4 * hour, ProtonThumbnailForegroundBudgetPolicy.delayUntilAffordableMillis(runs, now))
        // A half-hour run fits already once either run is out; that is still the oldest.
        assertEquals(
            4 * hour,
            ProtonThumbnailForegroundBudgetPolicy.delayUntilAffordableMillis(runs, now, nextRunMillis = hour / 2),
        )
        // Three runs of two hours: two of them have to leave first.
        val three = runs + ProtonForegroundRun(endedAtMillis = now - hour, durationMillis = 2 * hour)
        assertEquals(14 * hour, ProtonThumbnailForegroundBudgetPolicy.delayUntilAffordableMillis(three, now))
    }

    @Test
    fun `a run longer than the whole budget waits for the window to empty`() {
        val runs = listOf(ProtonForegroundRun(endedAtMillis = now - hour, durationMillis = hour))

        assertEquals(
            23 * hour,
            ProtonThumbnailForegroundBudgetPolicy.delayUntilAffordableMillis(
                runs,
                now,
                nextRunMillis = ProtonThumbnailForegroundBudgetPolicy.BUDGET_MILLIS + 1L,
            ),
        )
    }

    @Test
    fun `runs survive a round trip through the stored form and damage reads as nothing`() {
        val runs =
            listOf(
                ProtonForegroundRun(endedAtMillis = 1_000L, durationMillis = 5L),
                ProtonForegroundRun(endedAtMillis = 2_000L, durationMillis = 0L),
            )

        val encoded = ProtonThumbnailForegroundBudgetPolicy.encode(runs)

        assertEquals("1000:5;2000:0", encoded)
        assertEquals(runs, ProtonThumbnailForegroundBudgetPolicy.decode(encoded))
        assertEquals(emptyList<ProtonForegroundRun>(), ProtonThumbnailForegroundBudgetPolicy.decode(null))
        assertEquals(emptyList<ProtonForegroundRun>(), ProtonThumbnailForegroundBudgetPolicy.decode(""))
        assertEquals(runs.take(1), ProtonThumbnailForegroundBudgetPolicy.decode("1000:5;garbage;2:-1;3:4:5"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a run cannot have a negative duration`() {
        ProtonForegroundRun(endedAtMillis = 0L, durationMillis = -1L)
    }

    @Test
    fun `the limits leave room under the platform's allowance`() {
        assertTrue(ProtonThumbnailForegroundBudgetPolicy.BUDGET_MILLIS < 6 * hour)
        assertTrue(ProtonThumbnailForegroundBudgetPolicy.BUDGET_MILLIS >= 2 * ProtonThumbnailWorkPolicy.MAX_RUN_MILLIS)
        assertTrue(ProtonThumbnailForegroundBudgetPolicy.BACKGROUND_ONLY_RUN_MILLIS < 10L * 60L * 1_000L)
    }
}
