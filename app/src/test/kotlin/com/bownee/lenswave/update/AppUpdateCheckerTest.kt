package com.bownee.lenswave.update

import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.LenswaveDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateCheckerTest {
    @Test fun newerReleaseIsReturnedAndSuccessfulChecksAreThrottled() =
        runBlocking {
            val fixture = Fixture(LatestReleaseResult.Modified("0.20.0", "release-etag"))

            assertEquals("0.20.0", fixture.checker.findAvailableUpdate("0.19.4")?.versionName)
            assertEquals("release-etag", fixture.store.state.etag)
            assertEquals(1, fixture.client.calls)

            assertEquals("0.20.0", fixture.checker.findAvailableUpdate("0.19.4")?.versionName)
            assertEquals(1, fixture.client.calls)
        }

    @Test fun equalOlderAndMalformedReleasesAreIgnored() =
        runBlocking {
            listOf("0.19.4", "0.19.3", "not-a-version").forEach { remoteVersion ->
                val fixture = Fixture(LatestReleaseResult.Modified(remoteVersion, null))
                assertNull(fixture.checker.findAvailableUpdate("0.19.4"))
            }
        }

    @Test fun notModifiedResponseUsesCachedReleaseAndItsEtag() =
        runBlocking {
            val fixture = Fixture(LatestReleaseResult.NotModified)
            fixture.store.state =
                AppUpdateState(
                    latestVersionName = "0.20.0",
                    etag = "cached-etag",
                )

            assertEquals("0.20.0", fixture.checker.findAvailableUpdate("0.19.4")?.versionName)
            assertEquals("cached-etag", fixture.client.requestedEtags.single())
        }

    @Test fun failuresRetryAfterOneHourWithoutDiscardingCachedRelease() =
        runBlocking {
            val fixture = Fixture(LatestReleaseResult.Unavailable)
            fixture.store.state = AppUpdateState(latestVersionName = "0.20.0")

            assertEquals("0.20.0", fixture.checker.findAvailableUpdate("0.19.4")?.versionName)
            fixture.clock.value += 30L * 60L * 1_000L
            fixture.checker.findAvailableUpdate("0.19.4")
            assertEquals(1, fixture.client.calls)

            fixture.clock.value += 30L * 60L * 1_000L
            fixture.checker.findAvailableUpdate("0.19.4")
            assertEquals(2, fixture.client.calls)
        }

    @Test fun snoozeSuppressesOnlyThatReleaseForSevenDays() =
        runBlocking {
            val fixture = Fixture(LatestReleaseResult.NotModified)
            fixture.store.state =
                AppUpdateState(
                    latestVersionName = "0.20.0",
                    nextCheckAtMillis = Long.MAX_VALUE,
                )

            fixture.checker.snooze("0.20.0")
            assertNull(fixture.checker.findAvailableUpdate("0.19.4"))

            fixture.store.state = fixture.store.state.copy(latestVersionName = "0.21.0")
            assertEquals("0.21.0", fixture.checker.findAvailableUpdate("0.19.4")?.versionName)

            fixture.store.state = fixture.store.state.copy(latestVersionName = "0.20.0")
            fixture.clock.value += 7L * 24L * 60L * 60L * 1_000L
            assertEquals("0.20.0", fixture.checker.findAvailableUpdate("0.19.4")?.versionName)
        }

    private class Fixture(
        result: LatestReleaseResult,
    ) {
        val clock = FakeClock(1_000L)
        val client = FakeClient(result)
        val store = FakeStore()
        val checker = AppUpdateChecker(client, store, clock, DirectDispatchers)
    }

    private class FakeClient(
        var result: LatestReleaseResult,
    ) : LatestReleaseClient {
        var calls = 0
        val requestedEtags = mutableListOf<String?>()

        override fun fetch(etag: String?): LatestReleaseResult {
            calls++
            requestedEtags += etag
            return result
        }
    }

    private class FakeStore : AppUpdateStateStore {
        var state = AppUpdateState()

        override fun read(): AppUpdateState = state

        override fun write(state: AppUpdateState) {
            this.state = state
        }
    }

    private class FakeClock(
        var value: Long,
    ) : LenswaveClock {
        override fun nowMillis(): Long = value
    }

    private object DirectDispatchers : LenswaveDispatchers {
        override val io = Dispatchers.Unconfined
    }
}
