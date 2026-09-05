package com.bownee.lenswave.proton

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import javax.inject.Inject
import javax.inject.Singleton

internal interface ProtonTagApi : BaseRetrofitApi {
    @GET("drive/volumes/{volumeId}/photos")
    suspend fun getPhotos(
        @Path("volumeId") volumeId: String,
        @Query("Desc") descending: Int,
        @Query("PageSize") pageSize: Int,
        @Query("PreviousPageLastLinkID") previousPageLastLinkId: String?,
        @Query("MinimumCaptureTime") minimumCaptureTime: Long,
        @Query("Tag") tag: Long,
    ): JsonObject
}

/** Reads Proton's authoritative tag index without fetching media bodies or thumbnails. */
internal interface ProtonTagListingClient {
    suspend fun list(
        userId: UserId,
        volumeId: String,
        tag: ProtonMediaTag,
    ): List<ProtonGalleryPhoto>
}

/** One page of the tag index, as the API answers it; the seam the paging is tested through. */
internal fun interface ProtonTagPageFetcher {
    suspend fun fetch(
        userId: UserId,
        volumeId: String,
        tag: ProtonMediaTag,
        previousPageLastLinkId: String?,
    ): JsonObject
}

@Singleton
internal class ProtonTagListingApiClient internal constructor(
    private val pages: ProtonTagPageFetcher,
) : ProtonTagListingClient {
    @Inject
    constructor(apiProvider: ApiProvider) : this(
        ProtonTagPageFetcher { userId, volumeId, tag, previousPageLastLinkId ->
            apiProvider
                .get<ProtonTagApi>(userId)
                .invoke {
                    getPhotos(
                        volumeId = volumeId,
                        descending = 1,
                        pageSize = PAGE_SIZE,
                        previousPageLastLinkId = previousPageLastLinkId,
                        minimumCaptureTime = 0L,
                        tag = tag.apiValue,
                    )
                }.valueOrThrow
        },
    )

    /**
     * Pages until the API answers an empty page. Nothing documents that every page but the
     * last is full, and Proton's own web client keeps asking until a page comes back empty;
     * stopping on the first short page would silently truncate the listing, and a truncated
     * tag listing empties the tab.
     *
     * The loop still has to end when the server misbehaves: a page whose cursor did not
     * advance, a link that was already listed, or more pages than any library needs is an
     * error, never a listing. The failure reaches the sync as any other, which keeps the
     * cached listing rather than committing a partial or endlessly repeating one.
     */
    override suspend fun list(
        userId: UserId,
        volumeId: String,
        tag: ProtonMediaTag,
    ): List<ProtonGalleryPhoto> {
        val photos = mutableListOf<ProtonGalleryPhoto>()
        val listedLinkIds = HashSet<String>()
        var previousPageLastLinkId: String? = null
        var pageCount = 0
        do {
            check(pageCount < MAX_PAGES) {
                "Proton answered more than $MAX_PAGES pages for the ${tag.name.lowercase()} tag listing"
            }
            val response = pages.fetch(userId, volumeId, tag, previousPageLastLinkId)
            pageCount++
            var lastLinkId: String? = null
            val page =
                response.getValue("Photos").jsonArray.map { value ->
                    val photo = value.jsonObject
                    val linkId = photo.getValue("LinkID").jsonPrimitive.content
                    check(listedLinkIds.add(linkId)) {
                        "Proton listed $linkId twice for the ${tag.name.lowercase()} tag; the cursor is not advancing"
                    }
                    lastLinkId = linkId
                    ProtonGalleryPhoto(
                        nodeUid = "$volumeId~$linkId",
                        captureTimeEpochSeconds =
                            photo
                                .getValue("CaptureTime")
                                .jsonPrimitive.content
                                .toLong(),
                        hasThumbnail = false,
                    )
                }
            check(page.isEmpty() || lastLinkId != previousPageLastLinkId) {
                "Proton answered the same cursor $lastLinkId again for the ${tag.name.lowercase()} tag listing"
            }
            photos += page
            previousPageLastLinkId = lastLinkId
        } while (page.isNotEmpty())
        return photos
    }

    private companion object {
        const val PAGE_SIZE = 500

        /** A million tagged photos; a library is never that large, a server that ignores the cursor is. */
        const val MAX_PAGES = 2_000
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonTagListingModule {
    @Binds abstract fun bindTagListingClient(implementation: ProtonTagListingApiClient): ProtonTagListingClient
}
