package com.bownee.lenswave.storage

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object AtomicFileStore {
    fun write(
        target: File,
        contents: String,
        failureMessage: String,
    ) {
        write(target, contents.toByteArray(Charsets.UTF_8), failureMessage)
    }

    fun write(
        target: File,
        contents: ByteArray,
        failureMessage: String,
    ) {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile("${target.name}.", ".part", target.parentFile)
        try {
            temporary.writeBytes(contents)
            commit(temporary, target, failureMessage)
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
