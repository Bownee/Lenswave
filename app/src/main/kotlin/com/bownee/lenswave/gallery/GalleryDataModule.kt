package com.bownee.lenswave.gallery

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GalleryDataModule {
    @Binds abstract fun bindGalleryNavigationStore(
        implementation: SharedPreferencesGalleryNavigationStore,
    ): GalleryNavigationStore
}
