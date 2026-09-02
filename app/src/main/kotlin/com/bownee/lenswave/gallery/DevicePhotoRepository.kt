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
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.DATE_MODIFIED)
            add(MediaStore.Images.Media.SIZE)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Images.Media.RELATIVE_PATH)
            add(MediaStore.Images.Media.OWNER_PACKAGE_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.Images.Media.IS_DOWNLOAD)
        }.toTypedArray()
        val selection = "${MediaStore.Images.Media.IS_PENDING} = 0"
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
        queryPhotos(collection, projection, selection, sortOrder, trashed)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val bucketColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            val ownerColumn = cursor.getColumnIndex(MediaStore.Images.Media.OWNER_PACKAGE_NAME)
            val downloadColumn = cursor.getColumnIndex(MediaStore.Images.Media.IS_DOWNLOAD)
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
