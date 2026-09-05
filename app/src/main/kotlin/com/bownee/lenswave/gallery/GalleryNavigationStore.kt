package com.bownee.lenswave.gallery

import android.content.Context
import androidx.core.content.edit
import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonMediaTag
import com.bownee.lenswave.proton.SharedPreferencesProtonThumbnailPauseStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal data class StoredGalleryNavigation(
    val destination: String?,
    val albumUid: String? = null,
    val albumName: String? = null,
    val tag: String? = null,
)

internal object GalleryNavigationCodec {
    fun encode(destination: GalleryDestination): StoredGalleryNavigation =
        StoredGalleryNavigation(
            destination =
                when (destination) {
                    GalleryDestination.Timeline -> DESTINATION_TIMELINE
                    GalleryDestination.Library -> DESTINATION_LIBRARY
                    is GalleryDestination.Tag -> DESTINATION_TAG
                    is GalleryDestination.AlbumPhotos -> DESTINATION_ALBUM
                },
            albumUid = (destination as? GalleryDestination.AlbumPhotos)?.album?.nodeUid,
            albumName = (destination as? GalleryDestination.AlbumPhotos)?.album?.name,
            tag = (destination as? GalleryDestination.Tag)?.tag?.name,
        )

    fun decode(stored: StoredGalleryNavigation): GalleryDestination? =
        when (stored.destination) {
            DESTINATION_TIMELINE, LEGACY_DESTINATION_COMBINED -> {
                GalleryDestination.Timeline
            }

            DESTINATION_LIBRARY,
            LEGACY_DESTINATION_PROTON_ALBUMS,
            LEGACY_DESTINATION_DEVICE,
            -> {
                GalleryDestination.Library
            }

            DESTINATION_TAG -> {
                stored.tag
                    ?.let { runCatching { ProtonMediaTag.valueOf(it) }.getOrNull() }
                    ?.let(GalleryDestination::Tag)
                    ?: GalleryDestination.Library
            }

            DESTINATION_ALBUM -> {
                stored.albumUid?.let { uid ->
                    GalleryDestination.AlbumPhotos(
                        ProtonAlbumReference(
                            nodeUid = uid,
                            name = stored.albumName.orEmpty(),
                        ),
                    )
                } ?: GalleryDestination.Library
            }

            else -> {
                null
            }
        }

    private const val DESTINATION_TIMELINE = "proton-timeline"
    private const val DESTINATION_LIBRARY = "library"
    private const val DESTINATION_TAG = "proton-tag"
    private const val DESTINATION_ALBUM = "proton-album"
    private const val LEGACY_DESTINATION_COMBINED = "combined"
    private const val LEGACY_DESTINATION_PROTON_ALBUMS = "proton-albums"
    private const val LEGACY_DESTINATION_DEVICE = "device"
}

/**
 * Starts loading the gallery's preference files off the main thread at process start. The
 * platform caches one SharedPreferences per file for the process and parses it on its own
 * background thread once requested, so the first main-thread read (the view model's saved
 * destination in its constructor, the notification prompt flag) either finds the file parsed
 * or blocks only for the remainder of a load that began here rather than for all of it. There
 * is no guarantee the load has finished by the time the activity starts.
 */
internal object GalleryPreferenceWarmUp {
    private const val LEGACY_VIEWER_PRIVACY_PREFERENCES = "viewer-privacy"

    fun warm(context: Context) {
        context.getSharedPreferences(SharedPreferencesGalleryNavigationStore.PREFERENCES_NAME, Context.MODE_PRIVATE).all
        context.getSharedPreferences(GalleryNotificationPermissionPrompter.PREFERENCES_NAME, Context.MODE_PRIVATE).all
        // The screenshot-blocking setting is gone; its file is removed from installs that still have it.
        context.deleteSharedPreferences(LEGACY_VIEWER_PRIVACY_PREFERENCES)
        // The pause flag is read by every thumbnail run request, the first of them from the gallery's resume.
        context
            .getSharedPreferences(
                SharedPreferencesProtonThumbnailPauseStore.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ).all
    }
}

internal interface GalleryNavigationStore {
    fun read(): GalleryDestination?

    fun write(destination: GalleryDestination)
}

@Singleton
internal class SharedPreferencesGalleryNavigationStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : GalleryNavigationStore {
        private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        override fun read(): GalleryDestination? =
            GalleryNavigationCodec.decode(
                StoredGalleryNavigation(
                    destination = preferences.getString(KEY_DESTINATION, null),
                    albumUid = preferences.getString(KEY_ALBUM_UID, null),
                    albumName = preferences.getString(KEY_ALBUM_NAME, null),
                    tag = preferences.getString(KEY_TAG, null),
                ),
            )

        override fun write(destination: GalleryDestination) {
            val stored = GalleryNavigationCodec.encode(destination)
            preferences.edit {
                putString(KEY_DESTINATION, stored.destination)
                putString(KEY_ALBUM_UID, stored.albumUid)
                putString(KEY_ALBUM_NAME, stored.albumName)
                putString(KEY_TAG, stored.tag)
            }
        }

        internal companion object {
            const val PREFERENCES_NAME = "gallery-navigation"
            private const val KEY_DESTINATION = "destination"
            private const val KEY_ALBUM_UID = "album-uid"
            private const val KEY_ALBUM_NAME = "album-name"
            private const val KEY_TAG = "proton-tag"
        }
    }
