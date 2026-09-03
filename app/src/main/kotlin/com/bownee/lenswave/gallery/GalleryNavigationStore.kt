package com.bownee.lenswave.gallery

import android.content.Context
import androidx.core.content.edit
import com.bownee.lenswave.proton.ProtonAlbumReference
import com.bownee.lenswave.proton.ProtonMediaTag
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal data class StoredGalleryNavigation(
    val destination: String?,
    val deviceCollection: String? = null,
    val trashSource: String? = null,
    val albumUid: String? = null,
    val albumName: String? = null,
    val protonTag: String? = null,
)

internal object GalleryNavigationCodec {
    fun encode(destination: GalleryDestination): StoredGalleryNavigation = StoredGalleryNavigation(
        destination = when (destination) {
            GalleryDestination.ProtonTimeline -> DESTINATION_PROTON_TIMELINE
            GalleryDestination.Library -> DESTINATION_LIBRARY
            is GalleryDestination.Device -> DESTINATION_DEVICE
            is GalleryDestination.ProtonTag -> DESTINATION_PROTON_TAG
            is GalleryDestination.ProtonAlbumPhotos -> DESTINATION_PROTON_ALBUM
            is GalleryDestination.Trash -> DESTINATION_TRASH
        },
        deviceCollection = (destination as? GalleryDestination.Device)?.collection?.name,
        trashSource = (destination as? GalleryDestination.Trash)?.source?.name,
        albumUid = (destination as? GalleryDestination.ProtonAlbumPhotos)?.album?.nodeUid,
        albumName = (destination as? GalleryDestination.ProtonAlbumPhotos)?.album?.name,
        protonTag = (destination as? GalleryDestination.ProtonTag)?.tag?.name,
    )

    fun decode(stored: StoredGalleryNavigation): GalleryDestination? = when (stored.destination) {
        DESTINATION_PROTON_TIMELINE, LEGACY_DESTINATION_COMBINED -> GalleryDestination.ProtonTimeline
        DESTINATION_LIBRARY, LEGACY_DESTINATION_PROTON_ALBUMS -> GalleryDestination.Library
        DESTINATION_DEVICE -> stored.deviceCollection
            ?.let { runCatching { DeviceCollection.valueOf(it) }.getOrNull() }
            ?.let(GalleryDestination::Device)
            ?: GalleryDestination.Library
        DESTINATION_PROTON_TAG -> stored.protonTag
            ?.let { runCatching { ProtonMediaTag.valueOf(it) }.getOrNull() }
            ?.let(GalleryDestination::ProtonTag)
            ?: GalleryDestination.Library
        DESTINATION_PROTON_ALBUM -> stored.albumUid?.let { uid ->
            GalleryDestination.ProtonAlbumPhotos(
                ProtonAlbumReference(
                    nodeUid = uid,
                    name = stored.albumName.orEmpty(),
                ),
            )
        } ?: GalleryDestination.Library
        DESTINATION_TRASH -> stored.trashSource
            ?.let { runCatching { PhotoSource.valueOf(it) }.getOrNull() }
            ?.let(GalleryDestination::Trash)
            ?: GalleryDestination.Library
        else -> null
    }

    private const val DESTINATION_PROTON_TIMELINE = "proton-timeline"
    private const val DESTINATION_LIBRARY = "library"
    private const val DESTINATION_DEVICE = "device"
    private const val DESTINATION_PROTON_TAG = "proton-tag"
    private const val DESTINATION_PROTON_ALBUM = "proton-album"
    private const val DESTINATION_TRASH = "trash"
    private const val LEGACY_DESTINATION_COMBINED = "combined"
    private const val LEGACY_DESTINATION_PROTON_ALBUMS = "proton-albums"
}

internal interface GalleryNavigationStore {
    fun read(): GalleryDestination?
    fun write(destination: GalleryDestination)
}

@Singleton
internal class SharedPreferencesGalleryNavigationStore @Inject constructor(
    @ApplicationContext context: Context,
) : GalleryNavigationStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): GalleryDestination? = GalleryNavigationCodec.decode(
        StoredGalleryNavigation(
            destination = preferences.getString(KEY_DESTINATION, null),
            deviceCollection = preferences.getString(KEY_DEVICE_COLLECTION, null),
            trashSource = preferences.getString(KEY_TRASH_SOURCE, null),
            albumUid = preferences.getString(KEY_ALBUM_UID, null),
            albumName = preferences.getString(KEY_ALBUM_NAME, null),
            protonTag = preferences.getString(KEY_PROTON_TAG, null),
        ),
    )

    override fun write(destination: GalleryDestination) {
        val stored = GalleryNavigationCodec.encode(destination)
        preferences.edit {
            putString(KEY_DESTINATION, stored.destination)
            putString(KEY_DEVICE_COLLECTION, stored.deviceCollection)
            putString(KEY_TRASH_SOURCE, stored.trashSource)
            putString(KEY_ALBUM_UID, stored.albumUid)
            putString(KEY_ALBUM_NAME, stored.albumName)
            putString(KEY_PROTON_TAG, stored.protonTag)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "gallery-navigation"
        const val KEY_DESTINATION = "destination"
        const val KEY_DEVICE_COLLECTION = "device-collection"
        const val KEY_TRASH_SOURCE = "trash-source"
        const val KEY_ALBUM_UID = "album-uid"
        const val KEY_ALBUM_NAME = "album-name"
        const val KEY_PROTON_TAG = "proton-tag"
    }
}
