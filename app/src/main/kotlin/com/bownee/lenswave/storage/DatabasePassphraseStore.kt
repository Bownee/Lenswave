package com.bownee.lenswave.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabasePassphraseStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val secureFiles: SecureFileStore,
    ) {
        private val passphraseFile = File(context.noBackupFilesDir, "proton-session.key")

        @Synchronized
        fun getOrCreate(): ByteArray {
            if (passphraseFile.isFile) {
                return secureFiles.read(DATABASE_KEY_SCOPE, passphraseFile)
            }
            val passphrase = ByteArray(PASSPHRASE_BYTES).also(SecureRandom()::nextBytes)
            secureFiles.write(
                DATABASE_KEY_SCOPE,
                passphraseFile,
                passphrase,
                "Could not protect the Proton database key",
            )
            return passphrase
        }

        private companion object {
            const val DATABASE_KEY_SCOPE = "proton-session-database-key"
            const val PASSPHRASE_BYTES = 32
        }
    }
