package com.bownee.lenswave.proton

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the paging over a scripted page fetcher; the API itself is behind that seam. */
class ProtonTagListingClientTest {
    private val cursors = mutableListOf<String?>()

    @Test
    fun `pages follow the last link of the previous page until a page comes back empty`() =
        runTest {
            val client =
                client { cursor ->
                    when (cursor) {
                        null -> page("a" to 3L, "b" to 2L)
                        "b" -> page("c" to 1L)
                        else -> page()
                    }
                }

            val photos = client.list(USER, "vol", ProtonMediaTag.VIDEOS)

            assertEquals(listOf("vol~a", "vol~b", "vol~c"), photos.map(ProtonGalleryPhoto::nodeUid))
            assertEquals(listOf(3L, 2L, 1L), photos.map(ProtonGalleryPhoto::captureTimeEpochSeconds))
            assertEquals(listOf(null, "b", "c"), cursors)
        }

    @Test
    fun `a server that ignores the cursor fails instead of listing forever`() =
        runTest {
            val client = client { page("a" to 3L, "b" to 2L) }

            val error = failure { client.list(USER, "vol", ProtonMediaTag.FAVORITES) }

            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("twice"))
            assertEquals(2, cursors.size)
        }

    @Test
    fun `a cursor that does not advance fails even when the page is new`() =
        runTest {
            // The second page ends on the very link that asked for it.
            val client =
                client { cursor ->
                    when (cursor) {
                        null -> page("a" to 3L)
                        else -> page("b" to 2L, "a" to 1L)
                    }
                }

            val error = failure { client.list(USER, "vol", ProtonMediaTag.FAVORITES) }

            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("twice"))
        }

    @Test
    fun `an endless stream of new pages is capped`() =
        runTest {
            var next = 0
            val client = client { page("p${next++}" to 1L) }

            val error = failure { client.list(USER, "vol", ProtonMediaTag.VIDEOS) }

            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("more than 2000 pages"))
            assertEquals(2_000, cursors.size)
        }

    private suspend fun failure(block: suspend () -> Unit): IllegalStateException {
        try {
            block()
        } catch (error: IllegalStateException) {
            return error
        }
        throw AssertionError("the listing must fail")
    }

    private fun client(pages: (cursor: String?) -> JsonObject) =
        ProtonTagListingApiClient(
            ProtonTagPageFetcher { _, _, _, previousPageLastLinkId ->
                cursors += previousPageLastLinkId
                pages(previousPageLastLinkId)
            },
        )

    private fun page(vararg photos: Pair<String, Long>): JsonObject =
        buildJsonObject {
            put(
                "Photos",
                buildJsonArray {
                    photos.forEach { (linkId, captureTime) ->
                        add(
                            buildJsonObject {
                                put("LinkID", linkId)
                                put("CaptureTime", captureTime)
                            },
                        )
                    }
                },
            )
        }

    private companion object {
        val USER = UserId("user")
    }
}
