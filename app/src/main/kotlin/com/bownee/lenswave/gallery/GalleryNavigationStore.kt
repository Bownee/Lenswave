package com.bownee.lenswave.gallery

import android.content.Context
import androidx.core.content.edit
import com.bownee.lenswave.proton.ProtonAlbumReference
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal data class GalleryNavigationState(
    val destination: GalleryDestination,
    val selectedDeviceCollection: DeviceCollection = DeviceCollection.CAMERA,
)

internal data class StoredGalleryNavigation(
    val destination: String?,
    val deviceCollection: String? = null,
    val trashSource: String? = null,
    val albumUid: String? = null,
    val albumName: String? = null,
)

internal object GalleryNavigationCodec {
    fun encode(state: GalleryNavigationState): StoredGalleryNavigation = StoredGalleryNavigation(
        destination = when (state.destination) {
            GalleryDestination.Combined -> DESTINATION_COMBINED
            is GalleryDestination.Device -> DESTINATION_DEVICE
            GalleryDestination.ProtonTimeline -> DESTINATION_PROTON_TIMELINE
            GalleryDestination.ProtonAlbums -> DESTINATION_PROTON_ALBUMS
            is GalleryDestination.ProtonAlbumPhotos -> DESTINATION_PROTON_ALBUM
            is GalleryDestination.Trash -> DESTINATION_TRASH
        },
        deviceCollection = state.selectedDeviceCollection.name,
        trashSource = (state.destination as? GalleryDestination.Trash)?.source?.name,
        albumUid = (state.destination as? GalleryDestination.ProtonAlbumPhotos)?.album?.nodeUid,
        albumName = (state.destination as? GalleryDestination.ProtonAlbumPhotos)?.album?.name,
    )

    fun decode(stored: StoredGalleryNavigation): GalleryNavigationState? {
        val collection = stored.deviceCollection
            ?.let { runCatching { DeviceCollection.valueOf(it) }.getOrNull() }
            ?: DeviceCollection.CAMERA
        val destination = when (stored.destination) {
            DESTINATION_COMBINED -> GalleryDestination.Combined
            DESTINATION_DEVICE -> GalleryDestination.Device(collection)
            DESTINATION_PROTON_TIMELINE -> GalleryDestination.ProtonTimeline
            DESTINATION_PROTON_ALBUMS -> GalleryDestination.ProtonAlbums
            DESTINATION_PROTON_ALBUM -> stored.albumUid?.let { uid ->
                GalleryDestination.ProtonAlbumPhotos(
                    ProtonAlbumReference(
                        nodeUid = uid,
                        name = stored.albumName.orEmpty(),
                    ),
                )
            } ?: GalleryDestination.ProtonAlbums
            DESTINATION_TRASH -> stored.trashSource
                ?.let { runCatching { PhotoSource.valueOf(it) }.getOrNull() }
                ?.let(GalleryDestination::Trash)
                ?: GalleryDestination.Device(collection)
            else -> return null
        }
        return GalleryNavigationState(destination, collection)
    }

    private const val DESTINATION_COMBINED = "combined"
    private const val DESTINATION_DEVICE = "device"
    private const val DESTINATION_PROTON_TIMELINE = "proton-timeline"
    private const val DESTINATION_PROTON_ALBUMS = "proton-albums"
    private const val DESTINATION_PROTON_ALBUM = "proton-album"
    private const val DESTINATION_TRASH = "trash"
}

internal interface GalleryNavigationStore {
    fun read(): GalleryNavigationState?
    fun write(state: GalleryNavigationState)
}

@Singleton
internal class SharedPreferencesGalleryNavigationStore @Inject constructor(
    @ApplicationContext context: Context,
) : GalleryNavigationStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): GalleryNavigationState? = GalleryNavigationCodec.decode(
        StoredGalleryNavigation(
            destination = preferences.getString(KEY_DESTINATION, null),
            deviceCollection = preferences.getString(KEY_DEVICE_COLLECTION, null),
            trashSource = preferences.getString(KEY_TRASH_SOURCE, null),
            albumUid = preferences.getString(KEY_ALBUM_UID, null),
            albumName = preferences.getString(KEY_ALBUM_NAME, null),
        ),
    )

    override fun write(state: GalleryNavigationState) {
        val stored = GalleryNavigationCodec.encode(state)
        preferences.edit {
            putString(KEY_DESTINATION, stored.destination)
            putString(KEY_DEVICE_COLLECTION, stored.deviceCollection)
            putString(KEY_TRASH_SOURCE, stored.trashSource)
            putString(KEY_ALBUM_UID, stored.albumUid)
            putString(KEY_ALBUM_NAME, stored.albumName)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "gallery-navigation"
        const val KEY_DESTINATION = "destination"
        const val KEY_DEVICE_COLLECTION = "device-collection"
        const val KEY_TRASH_SOURCE = "trash-source"
        const val KEY_ALBUM_UID = "album-uid"
        const val KEY_ALBUM_NAME = "album-name"
    }
}
