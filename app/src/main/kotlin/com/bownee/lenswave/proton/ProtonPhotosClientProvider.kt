package com.bownee.lenswave.proton

import android.content.Context
import androidx.core.content.edit
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import com.bownee.lenswave.storage.AtomicFileStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.repository.PublicAddressRepository
import me.proton.core.network.data.ApiProvider
import me.proton.core.user.domain.repository.UserAddressRepository
import me.proton.drive.sdk.CorePublicAddressResolver
import me.proton.drive.sdk.CoreUserAddressResolver
import me.proton.drive.sdk.LoggerProvider
import me.proton.drive.sdk.ProgressUpdate
import me.proton.drive.sdk.ProtonDriveSdk
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.entity.ClientCreateRequest
import me.proton.drive.sdk.entity.NodeUid
import me.proton.drive.sdk.entity.PhotosDownloaderRequest
import java.nio.channels.WritableByteChannel
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands out the SDK client of one account at a time; [ProtonSdkPhotosClientProvider] in
 * production. The interface lets the repositories and the gateway run against a fake that never
 * reaches the SDK or an Android context.
 */
interface ProtonPhotosClientProvider {
    suspend fun get(userId: UserId): ProtonPhotosClient

    suspend fun disconnect(userId: UserId)

    suspend fun downloadTo(
        userId: UserId,
        nodeUid: String,
        output: WritableByteChannel,
        onProgress: (ProgressUpdate) -> Unit = {},
    )

    companion object {
        const val BASE_URL = "https://drive-api.proton.me/"
    }
}

@Singleton
internal class ProtonSdkPhotosClientProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val apiProvider: ApiProvider,
        private val cryptoContext: CryptoContext,
        private val userAddressRepository: UserAddressRepository,
        private val publicAddressRepository: PublicAddressRepository,
    ) : ProtonPhotosClientProvider {
        private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private var clientUserId: UserId? = null
        private var client: ProtonPhotosClient? = null
        private var loggerProvider: LoggerProvider? = null

        override suspend fun get(userId: UserId): ProtonPhotosClient =
            try {
                mutex.withLock {
                    client?.takeIf { clientUserId == userId } ?: create(userId).also { created ->
                        client?.close()
                        client = created
                        clientUserId = userId
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LenswaveDiagnostics.reportFailure(LenswaveOperation.PROTON_CLIENT_CREATE, error)
                throw error
            }

        override suspend fun disconnect(userId: UserId) =
            mutex.withLock {
                if (clientUserId != userId) return@withLock
                // SDK work launched on the shared scope belongs to this client; a closed client must not
                // keep transferring into caches that are about to be wiped.
                clientScope.coroutineContext.cancelChildren()
                client?.close()
                client = null
                clientUserId = null
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit {
                    remove(clientUidKey(userId))
                }
            }

        override suspend fun downloadTo(
            userId: UserId,
            nodeUid: String,
            output: WritableByteChannel,
            onProgress: (ProgressUpdate) -> Unit,
        ) {
            get(userId).downloader(PhotosDownloaderRequest(NodeUid(nodeUid))).use { downloader ->
                downloader.downloadToStream(clientScope, output).use { controller ->
                    coroutineScope {
                        val progressJob =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    controller.progressFlow.collect { progress ->
                                        progress?.let(onProgress)
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Throwable) {
                                    LenswaveDiagnostics.reportFailure(
                                        LenswaveOperation.ORIGINAL_DOWNLOAD_PROGRESS,
                                        error,
                                    )
                                }
                            }
                        try {
                            controller.awaitCompletion()
                        } finally {
                            progressJob.cancelAndJoin()
                        }
                    }
                }
            }
        }

        private suspend fun create(userId: UserId): ProtonPhotosClient {
            val logger =
                loggerProvider ?: ProtonDriveSdk.loggerProvider { _, _, _ -> }.also {
                    loggerProvider = it
                }
            val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            val clientUidKey = clientUidKey(userId)
            val clientUid =
                preferences.getString(clientUidKey, null) ?: UUID.randomUUID().toString().also {
                    preferences.edit { putString(clientUidKey, it) }
                }
            return ProtonDriveSdk.protonPhotosClientCreate(
                coroutineScope = clientScope,
                userId = userId,
                apiProvider = apiProvider,
                request =
                    ClientCreateRequest(
                        baseUrl = ProtonPhotosClientProvider.BASE_URL,
                        loggerProvider = logger,
                        bindingsLanguage = "kotlin",
                        uid = clientUid,
                    ),
                userAddressResolver =
                    CoreUserAddressResolver(
                        userId = userId,
                        cryptoContext = cryptoContext,
                        userAddressRepository = userAddressRepository,
                    ),
                publicAddressResolver =
                    CorePublicAddressResolver(
                        userId = userId,
                        publicAddressRepository = publicAddressRepository,
                    ),
            )
        }

        private companion object {
            const val PREFERENCES_NAME = "proton-sdk"

            fun clientUidKey(userId: UserId): String = "client-uid-${AtomicFileStore.safeName(userId.id)}"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonPhotosClientModule {
    @Binds abstract fun bindClientProvider(implementation: ProtonSdkPhotosClientProvider): ProtonPhotosClientProvider
}
