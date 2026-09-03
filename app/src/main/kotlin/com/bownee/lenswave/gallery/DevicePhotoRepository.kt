package com.bownee.lenswave.gallery

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bownee.lenswave.LenswaveDispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DevicePhotoRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: LenswaveDispatchers,
) : DevicePhotoSource {
    override suspend fun loadPhotos(): List<GalleryAsset> = loadPhotos(trashed = false)

    override suspend fun loadTrashedPhotos(): List<GalleryAsset> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return loadPhotos(trashed = true)
    }

    private suspend fun loadPhotos(trashed: Boolean): List<GalleryAsset> = withContext(dispatchers.io) {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME)
            add(MediaStore.Images.ImageColumns.DATE_TAKEN)
            add(MediaStore.Files.FileColumns.DATE_ADDED)
            add(MediaStore.Files.FileColumns.DATE_MODIFIED)
            add(MediaStore.Files.FileColumns.SIZE)
            add(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.Files.FileColumns.RELATIVE_PATH)
            add(MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.Downloads.IS_DOWNLOAD)
        }.toTypedArray()
        val selection = "${MediaStore.Files.FileColumns.IS_PENDING} = 0 AND " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN " +
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val sortOrder = "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC, " +
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        queryPhotos(collection, projection, selection, sortOrder, trashed)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val bucketColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val ownerColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME)
            val mediaTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val downloadColumn = cursor.getColumnIndex(MediaStore.Downloads.IS_DOWNLOAD)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val taken = cursor.getLong(takenColumn)
                    val added = cursor.getLong(addedColumn) * 1_000L
                    val section = DevicePhotoClassifier.classify(
                        bucketName = cursor.stringOrNull(bucketColumn),
                        relativePath = cursor.stringOrNull(pathColumn),
                        ownerPackageName = cursor.stringOrNull(ownerColumn),
                        isDownload = downloadColumn >= 0 && cursor.getInt(downloadColumn) == 1,
                    )
                    add(
                        GalleryAsset.device(
                            stableId = "device:$id",
                            capturedAtEpochMillis = taken.takeIf { it > 0 } ?: added,
                            displayName = cursor.getString(nameColumn).orEmpty(),
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            collection = section,
                            sizeBytes = cursor.getLong(sizeColumn),
                            modifiedAtEpochMillis = cursor.getLong(modifiedColumn) * 1_000L,
                            mediaKind = if (
                                cursor.getInt(mediaTypeColumn) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                            ) {
                                MediaKind.VIDEO
                            } else {
                                MediaKind.IMAGE
                            },
                            isTrashed = trashed,
                        )
                    )
                }
            }
        } ?: emptyList()
    }

    private fun queryPhotos(
        collection: android.net.Uri,
        projection: Array<String>,
        selection: String,
        sortOrder: String,
        trashed: Boolean,
    ) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val queryArguments = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            putInt(
                MediaStore.QUERY_ARG_MATCH_TRASHED,
                if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE,
            )
        }
        context.contentResolver.query(collection, projection, queryArguments, null)
    } else {
        context.contentResolver.query(collection, projection, selection, null, sortOrder)
    }

    override suspend fun calculateSha1(photo: GalleryAsset): ByteArray = withContext(dispatchers.io) {
        require(photo.source == PhotoSource.DEVICE) { "Only device photos can be hashed" }
        val uri = requireNotNull(photo.uri).toUri()
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open ${photo.displayName.ifBlank { "device photo" }}" }
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest()
    }

    private fun android.database.Cursor.stringOrNull(column: Int): String? =
        column.takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private companion object {
        const val HASH_BUFFER_SIZE = 128 * 1024
    }
}
