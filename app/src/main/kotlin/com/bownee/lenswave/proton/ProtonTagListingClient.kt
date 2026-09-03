package com.bownee.lenswave.proton

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
@Singleton
internal class ProtonTagListingClient
    @Inject
    constructor(
        private val apiProvider: ApiProvider,
    ) {
        suspend fun list(
            userId: UserId,
            volumeId: String,
            tag: ProtonMediaTag,
        ): List<ProtonGalleryPhoto> {
            val photos = mutableListOf<ProtonGalleryPhoto>()
            var previousPageLastLinkId: String? = null
            do {
                val response =
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
                var lastLinkId: String? = null
                val page =
                    response.getValue("Photos").jsonArray.map { value ->
                        val photo = value.jsonObject
                        val linkId = photo.getValue("LinkID").jsonPrimitive.content
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
                photos += page
                previousPageLastLinkId = lastLinkId
            } while (page.size == PAGE_SIZE)
            return photos
        }

        private companion object {
            const val PAGE_SIZE = 500
        }
    }
