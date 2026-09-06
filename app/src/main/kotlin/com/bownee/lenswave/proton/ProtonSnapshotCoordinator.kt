package com.bownee.lenswave.proton

import com.bownee.lenswave.LenswaveClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

internal interface ProtonSyncMetadataStore {
    fun readLastSuccessfulSync(
        userId: String,
        source: String,
    ): Long

    fun writeLastSuccessfulSync(
        userId: String,
        source: String,
        timestampMillis: Long,
    )
}

/** Records successful snapshot commits; remote freshness is tracked by [ProtonEventSync]. */
@Singleton
internal class ProtonSnapshotCoordinator
    @Inject
    constructor(
        private val metadata: ProtonSyncMetadataStore,
        private val clock: LenswaveClock,
    ) {
        fun shouldEnumerate(
            userId: String,
            syncKey: String,
            forceRemote: Boolean,
            hasCachedSnapshot: Boolean,
        ): Boolean =
            ProtonSyncPolicy.shouldEnumerate(
                lastSuccessfulSyncMillis = metadata.readLastSuccessfulSync(userId, syncKey),
                forceRemote = forceRemote,
                hasCachedSnapshot = hasCachedSnapshot,
            )

        fun commit(
            userId: String,
            syncKey: String,
        ) {
            metadata.writeLastSuccessfulSync(userId, syncKey, clock.nowMillis())
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonSnapshotModule {
    @Binds abstract fun bindSyncMetadataStore(implementation: ProtonPhotoCache): ProtonSyncMetadataStore
}
