package com.bownee.lenswave.proton

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import me.proton.core.network.domain.ApiResult
import me.proton.drive.sdk.entity.DriveEventId
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.ScopeId
import retrofit2.http.GET
import javax.inject.Inject

internal interface ProtonPhotoRootApi : BaseRetrofitApi {
    @GET("drive/v2/shares/photos")
    suspend fun getPhotoRoot(): JsonObject
}

/** Kotlin's Photos client exposes event enumeration but not photo-root discovery yet. */
internal class ProtonSdkEventSource
    @Inject
    constructor(
        private val clients: ProtonPhotosClientProvider,
        private val apiProvider: ApiProvider,
        private val albums: ProtonAlbumCache,
    ) : ProtonEventSource {
        override suspend fun ownScope(userId: UserId): String? {
            val response = apiProvider.get<ProtonPhotoRootApi>(userId).invoke { getPhotoRoot() }
            // Proton's own TryGetExistingPhotosFolder handles precisely this API code as absent.
            if ((response as? ApiResult.Error.Http)?.proton?.code == 2500) return null
            return response.valueOrThrow
                .getValue("Volume")
                .jsonObject
                .getValue("VolumeID")
                .jsonPrimitive.content
        }

        override fun albumScopes(userId: String): Set<String> =
            albums.readAlbumsSnapshot(userId).orEmpty().mapTo(mutableSetOf()) { protonEventScope(it.nodeUid) }

        override fun events(
            userId: UserId,
            scope: ScopeId,
            cursor: DriveEventId?,
        ) = kotlinx.coroutines.flow.flow {
            clients.get(userId).enumerateEvents(scope, cursor).collect { emit(it) }
        }
    }

/** Use the SDK's UID parser rather than duplicating its legacy UID format. */
internal fun protonEventScope(nodeUid: String): String = NodeUid(nodeUid).volumeId

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonEventModule {
    @Binds abstract fun bindEventSource(implementation: ProtonSdkEventSource): ProtonEventSource

    @Binds abstract fun bindEventStore(implementation: ProtonPhotoCache): ProtonEventStore
}
