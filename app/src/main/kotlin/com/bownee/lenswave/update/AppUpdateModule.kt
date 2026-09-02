package com.bownee.lenswave.update

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppUpdateModule {
    @Binds
    abstract fun bindLatestReleaseClient(implementation: GitHubReleasesClient): LatestReleaseClient

    @Binds
    abstract fun bindAppUpdateStateStore(
        implementation: SharedPreferencesAppUpdateStateStore,
    ): AppUpdateStateStore
}
