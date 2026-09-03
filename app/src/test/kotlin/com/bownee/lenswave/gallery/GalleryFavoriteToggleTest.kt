package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonFavoriteResult
import com.bownee.lenswave.proton.ProtonMediaTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryFavoriteToggleTest {
    private val userId = UserId("user")
    private val photo = GalleryAsset(
        stableId = "proton:node",
        capturedAtEpochMillis = 1_000L,
        nodeUid = "node",
        hasThumbnail = true,
    )
    private val events = mutableListOf<String>()

    private fun toggle(
        scope: CoroutineScope,
        setFavorite: suspend (UserId, List<String>, Boolean) -> ProtonFavoriteResult,
    ) = GalleryFavoriteToggle(
        scope = scope,
        setFavorite = setFavorite,
        onBegin = { stableId, favorite -> events += "begin:$stableId:$favorite" },
        onFinish = { stableId, succeeded -> events += "finish:$stableId:$succeeded" },
        onError = { events += "error" },
    )

    @Test
    fun `successful request shows the new value immediately and confirms it`() = runTest {
        var requested: Triple<UserId, List<String>, Boolean>? = null
        val toggle = toggle(this) { user, nodeUids, favorite ->
            requested = Triple(user, nodeUids, favorite)
            ProtonFavoriteResult(updatedCount = nodeUids.size)
        }

        toggle.toggle(userId, photo).join()

        assertEquals(Triple(userId, listOf("node"), true), requested)
        assertEquals(listOf("begin:proton:node:true", "finish:proton:node:true"), events)
    }

    @Test
    fun `toggling a favourite requests its removal`() = runTest {
        var requestedFavorite: Boolean? = null
        val toggle = toggle(this) { _, nodeUids, favorite ->
            requestedFavorite = favorite
            ProtonFavoriteResult(updatedCount = nodeUids.size)
        }

        toggle.toggle(userId, photo.copy(tags = setOf(ProtonMediaTag.FAVORITES))).join()

        assertEquals(false, requestedFavorite)
    }

    @Test
    fun `partial update reports an error and rolls back`() = runTest {
        val toggle = toggle(this) { _, _, _ -> ProtonFavoriteResult(updatedCount = 0, failedCount = 1) }

        toggle.toggle(userId, photo).join()

        assertEquals(listOf("begin:proton:node:true", "error", "finish:proton:node:false"), events)
    }

    @Test
    fun `failed request reports an error and rolls back`() = runTest {
        val toggle = toggle(this) { _, _, _ -> throw IllegalStateException("offline") }

        toggle.toggle(userId, photo).join()

        assertEquals(listOf("begin:proton:node:true", "error", "finish:proton:node:false"), events)
    }

    @Test
    fun `cancellation rolls back without reporting an error`() = runTest {
        val toggle = toggle(this) { _, _, _ -> throw CancellationException("cancelled") }

        toggle.toggle(userId, photo).join()

        assertEquals(listOf("begin:proton:node:true", "finish:proton:node:false"), events)
    }
}
