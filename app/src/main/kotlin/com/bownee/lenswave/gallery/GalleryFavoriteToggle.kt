package com.bownee.lenswave.gallery

import com.bownee.lenswave.proton.ProtonFavoriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.proton.core.domain.entity.UserId

/**
 * Flips a photo's favourite flag optimistically: [onBegin] shows the requested value at once,
 * [onFinish] confirms or rolls it back once the request settles, and [onError] reports a request
 * that failed or did not update every node.
 */
internal class GalleryFavoriteToggle(
    private val scope: CoroutineScope,
    private val setFavorite: suspend (UserId, List<String>, Boolean) -> ProtonFavoriteResult,
    private val onBegin: (stableId: String, favorite: Boolean) -> Unit,
    private val onFinish: (stableId: String, succeeded: Boolean) -> Unit,
    private val onError: () -> Unit,
) {
    /** Returns the request job. */
    fun toggle(userId: UserId, photo: GalleryAsset): Job {
        val nodeUids = listOf(photo.nodeUid)
        val favorite = !photo.isFavorite
        onBegin(photo.stableId, favorite)
        return scope.launch {
            var succeeded = false
            try {
                val result = setFavorite(userId, nodeUids, favorite)
                succeeded = result.updatedCount == nodeUids.size
                if (!succeeded) onError()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                onError()
            } finally {
                onFinish(photo.stableId, succeeded)
            }
        }
    }
}
