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

    fun safeName(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
