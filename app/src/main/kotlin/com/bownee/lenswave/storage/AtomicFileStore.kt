package com.bownee.lenswave.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal object AtomicFileStore {
    fun write(
        target: File,
        contents: String,
        failureMessage: String,
        fsync: Boolean = false,
    ) {
        write(target, contents.toByteArray(Charsets.UTF_8), failureMessage, fsync)
    }

    /**
     * Writes [contents] to a temporary file and moves it over [target]. [commitGate] wraps only
     * the move, so a caller can hold a lock across the commit without holding it across the write.
     *
     * With [fsync] the bytes are forced to disk before the move. A rename is journaled by the
     * file system but the data behind it is not, so a power loss right after the move can leave
     * [target] renamed but empty; for a queue, an index or a wrapped key that is a miss the app
     * has to rebuild, worth one sync of a few kilobytes. Large originals and renditions stay on
     * the fast path: an empty rendition reads as absent and is fetched again.
     *
     * [commitGate] stays last on purpose: callers pass it as a trailing lambda.
     */
    fun write(
        target: File,
        contents: ByteArray,
        failureMessage: String,
        fsync: Boolean = false,
        commitGate: (commit: () -> Unit) -> Unit = { commit -> commit() },
    ) {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile("${target.name}.", ".part", target.parentFile)
        try {
            if (fsync) {
                FileOutputStream(temporary).use { output ->
                    output.write(contents)
                    output.fd.sync()
                }
            } else {
                temporary.writeBytes(contents)
            }
            commitGate { commit(temporary, target, failureMessage) }
        } catch (error: Exception) {
            temporary.delete()
            if (error is IllegalStateException) throw error
            throw IllegalStateException(failureMessage, error)
        }
    }

    fun commit(
        temporary: File,
        target: File,
        failureMessage: String,
    ) {
        target.parentFile?.mkdirs()
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: Exception) {
                throw IllegalStateException(failureMessage, error)
            }
        } catch (error: Exception) {
            throw IllegalStateException(failureMessage, error)
        }
    }

    /**
     * The names, without their extension, of every regular non-empty `.[extension]` file in
     * [directory]; empty when the directory is absent or cannot be listed.
     *
     * One directory read and one stat per entry: a rendition store hydrates thousands of entries
     * every time the gallery opens, and `isFile` plus `length` on a [File] cost two stats each. A
     * zero-length file (a rename that reached the disk before its data did) is left out, exactly
     * as the stores' own stored checks leave it out, so a listed name is one that can be loaded.
     */
    fun nonEmptyFileNames(
        directory: File,
        extension: String,
    ): Set<String> {
        val suffix = ".$extension"
        return try {
            Files.newDirectoryStream(directory.toPath()).use { entries ->
                entries.mapNotNullTo(HashSet()) { entry ->
                    val name = entry.fileName.toString()
                    if (!name.endsWith(suffix)) return@mapNotNullTo null
                    val attributes =
                        try {
                            Files.readAttributes(entry, BasicFileAttributes::class.java)
                        } catch (_: IOException) {
                            // Removed between the listing and the stat: not stored.
                            return@mapNotNullTo null
                        }
                    name.removeSuffix(suffix).takeIf { attributes.isRegularFile && attributes.size() > 0L }
                }
            }
        } catch (_: IOException) {
            emptySet()
        } catch (_: DirectoryIteratorException) {
            emptySet()
        }
    }

    /**
     * SHA-256 of [value] as lower-case hex. The digest is reused per thread: `getInstance` is a
     * provider lookup plus an allocation, and the cache calls this once per photo when it
     * hydrates a listing, reconciles a sync or sweeps a directory.
     */
    fun safeName(value: String): String {
        val digest = checkNotNull(SHA256.get())
        digest.reset()
        return digest.digest(value.toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * Lower-case hex without `String.format`, which costs tens of microseconds per byte on
     * Android; the cache hashes thousands of node uids every time the gallery opens.
     */
    fun ByteArray.toHex(): String {
        val out = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            out[index * 2] = HEX_DIGITS[value ushr 4]
            out[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(out)
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
    private val SHA256 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }
}
