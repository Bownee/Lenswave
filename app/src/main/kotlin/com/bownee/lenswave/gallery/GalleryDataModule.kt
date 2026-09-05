package com.bownee.lenswave.gallery

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Bindings for gallery-owned state. The Proton data-source bindings live in :proton
 * (ProtonDataSourceModule) so this module stays free of the gateway.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class GalleryDataModule {
    @Binds abstract fun bindGalleryNavigationStore(
        implementation: SharedPreferencesGalleryNavigationStore,
    ): GalleryNavigationStore
}
