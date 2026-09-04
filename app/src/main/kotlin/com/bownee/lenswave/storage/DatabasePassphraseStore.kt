package com.bownee.lenswave.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabasePassphraseStore internal constructor(
    context: Context,
    private val secureFiles: SecureFileStore,
    fileName: String,
    private val scope: String,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        secureFiles: SecureFileStore,
    ) : this(context, secureFiles, DEFAULT_FILE_NAME, DEFAULT_SCOPE)

    private val passphraseFile = File(context.noBackupFilesDir, fileName)

    @Synchronized
    fun getOrCreate(): ByteArray {
        if (passphraseFile.isFile) {
            return secureFiles.read(scope, passphraseFile)
        }
        val passphrase = ByteArray(PASSPHRASE_BYTES).also(SecureRandom()::nextBytes)
        secureFiles.write(
            scope,
            passphraseFile,
            passphrase,
            "Could not protect the Proton database key",
        )
        return passphrase
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "proton-session.key"
        const val DEFAULT_SCOPE = "proton-session-database-key"
        const val PASSPHRASE_BYTES = 32
    }
}
