package com.bownee.lenswave.proton

import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** A photo index whose bytes do not decode; the cache deletes it like any other corrupt snapshot. */
internal class CorruptPhotoIndexException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The on-disk form of a photo listing (the timeline, a tag, an album): a node uid and a capture
 * time per photo. Earlier releases wrote a JSON array of objects; parsing a few thousand of
 * them with `org.json` was the slowest step of a launch, so listings are now a length-prefixed
 * binary record read in one pass. Both forms are read; a JSON listing is rewritten in the
 * binary form at its next sync.
 */
internal object ProtonPhotoIndexCodec {
    private const val MAGIC = 0x4C57_4958 // "LWIX"
    private const val VERSION = 1
    private const val JSON_ARRAY_START = '['.code.toByte()

    fun encode(photos: List<ProtonGalleryPhoto>): ByteArray {
        val output = ByteArrayOutputStream(HEADER_BYTES + photos.size * ESTIMATED_ENTRY_BYTES)
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(photos.size)
            photos.forEach { photo ->
                data.writeUTF(photo.nodeUid)
                data.writeLong(photo.captureTimeEpochSeconds)
            }
        }
        return output.toByteArray()
    }

    /** Whether [bytes] hold a listing in the JSON form earlier releases wrote. */
    fun isLegacyJson(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes[0] == JSON_ARRAY_START

    /** Hands every entry to [entry] in stored order; throws [CorruptPhotoIndexException] when [bytes] do not decode. */
    inline fun <T> decode(
        bytes: ByteArray,
        entry: (nodeUid: String, captureTimeEpochSeconds: Long) -> T,
    ): List<T> =
        if (isLegacyJson(bytes)) {
            val array = JSONArray(bytes.toString(Charsets.UTF_8))
            List(array.length()) { position ->
                val value = array.getJSONObject(position)
                entry(value.getString("nodeUid"), value.getLong("captureTime"))
            }
        } else {
            val data = openBinary(bytes)
            val count = readCount(data)
            List(count) {
                val nodeUid = readNodeUid(data)
                val captureTime = readCaptureTime(data)
                entry(nodeUid, captureTime)
            }
        }

    /** The header checked; the returned stream is positioned at the entry count. */
    fun openBinary(bytes: ByteArray): DataInputStream {
        val data = DataInputStream(ByteArrayInputStream(bytes))
        val magic = readOrCorrupt("magic") { data.readInt() }
        if (magic != MAGIC) throw CorruptPhotoIndexException("Photo index starts with 0x${magic.toUInt().toString(16)}")
        val version = readOrCorrupt("version") { data.readInt() }
        if (version != VERSION) throw CorruptPhotoIndexException("Photo index version $version is not $VERSION")
        return data
    }

    fun readCount(data: DataInputStream): Int {
        val count = readOrCorrupt("count") { data.readInt() }
        if (count < 0) throw CorruptPhotoIndexException("Photo index claims $count entries")
        return count
    }

    fun readNodeUid(data: DataInputStream): String = readOrCorrupt("node uid") { data.readUTF() }

    fun readCaptureTime(data: DataInputStream): Long = readOrCorrupt("capture time") { data.readLong() }

    private inline fun <T> readOrCorrupt(
        field: String,
        read: () -> T,
    ): T =
        try {
            read()
        } catch (error: IOException) {
            throw CorruptPhotoIndexException("Photo index ends inside its $field", error)
        }

    private const val HEADER_BYTES = 12
    private const val ESTIMATED_ENTRY_BYTES = 48
}
