package com.bownee.lenswave.storage

import android.content.Context
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.inject.Inject
import javax.inject.Singleton

/** The SQLCipher passphrase, and whether it is a fresh one that replaced an unreadable file. */
class DatabasePassphrase internal constructor(
    val bytes: ByteArray,
    /** True when the stored passphrase could not be read and a new one was minted in its place. */
    val replacedUnreadable: Boolean,
)

/**
 * Keeps the SQLCipher passphrase in one file under the session key scope.
 *
 * The file is unreadable once its scope's data key is gone: [SecureFileStore] discards a wrapped
 * key the Keystore can no longer unwrap (a Keystore reset, a restored backup) and mints a new
 * one, after which the stored passphrase fails its tag. Throwing here would fail the Hilt
 * provider on every launch until the user cleared the app's data. Instead the unreadable file is
 * reported and replaced by a fresh passphrase; the session database that was keyed with the old
 * one then opens with neither key and the migration discards it, so the user signs in again. A
 * data key that is merely unavailable right now is left alone and the failure propagates,
 * because minting a passphrase over a Keystore hiccup would lose a perfectly good session.
 */
@Singleton
class DatabasePassphraseStore internal constructor(
    private val passphraseFile: File,
    private val secureFiles: SecurePayloadStore,
    private val scope: String,
    private val reportFailure: (Throwable) -> Unit = { error ->
        LenswaveDiagnostics.reportFailure(LenswaveOperation.DATABASE_PASSPHRASE_RECOVERY, error)
    },
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        secureFiles: SecureFileStore,
    ) : this(File(context.noBackupFilesDir, DEFAULT_FILE_NAME), secureFiles, DEFAULT_SCOPE)

    @Synchronized
    fun getOrCreate(): DatabasePassphrase {
        var replaced = false
        if (passphraseFile.isFile) {
            val failure =
                try {
                    return DatabasePassphrase(secureFiles.read(scope, passphraseFile), replacedUnreadable = false)
                } catch (error: AEADBadTagException) {
                    error
                } catch (error: IllegalArgumentException) {
                    error
                }
            reportFailure(failure)
            check(passphraseFile.delete() || !passphraseFile.exists()) {
                "Could not discard the unreadable Proton database key"
            }
            replaced = true
        }
        val passphrase = ByteArray(PASSPHRASE_BYTES).also(SecureRandom()::nextBytes)
        secureFiles.write(
            scope,
            passphraseFile,
            passphrase,
            "Could not protect the Proton database key",
        )
        return DatabasePassphrase(passphrase, replacedUnreadable = replaced)
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "proton-session.key"
        const val DEFAULT_SCOPE = "proton-session-database-key"
        const val PASSPHRASE_BYTES = 32
    }
}
