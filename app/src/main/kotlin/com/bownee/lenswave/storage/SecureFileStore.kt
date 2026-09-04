package com.bownee.lenswave.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authenticated app-private storage. Each scope owns a random data key that encrypts file
 * contents in software; the data key itself is stored wrapped by a non-exportable Android
 * Keystore key. Keystore ciphers run inside the secure hardware and manage only a few kilobytes
 * per second on some devices, so sending every cached index and thumbnail through them made the
 * gallery take seconds to open.
 *
 * Small payloads ([read], [write]) are one GCM record (format version 2). Whole files
 * ([encryptFile], [decryptFile]) go through [SegmentedEnvelope] (format version 3) so an original
 * of any size streams through a bounded buffer and a decrypt can stop between segments. Files
 * written by earlier versions were encrypted with the Keystore key directly (version 1) or as one
 * whole-file record (version 2); both stay readable, and small version 1 payloads are rewritten
 * in the current format when read.
 */
@Singleton
class SecureFileStore(
    private val keyDirectory: File,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(File(context.noBackupFilesDir, KEY_DIRECTORY))

    fun read(
        scope: String,
        file: File,
    ): ByteArray {
        val payload = file.readBytes()
        val plaintext = decrypt(scope, payload)
        if (payload[MAGIC.size] == LEGACY_VERSION) upgrade(scope, file, plaintext)
        return plaintext
    }

    fun readText(
        scope: String,
        file: File,
    ): String = read(scope, file).toString(Charsets.UTF_8)

    fun write(
        scope: String,
        file: File,
        bytes: ByteArray,
        failureMessage: String,
    ) {
        AtomicFileStore.write(file, encrypt(scope, bytes), failureMessage)
    }

    fun writeText(
        scope: String,
        file: File,
        text: String,
        failureMessage: String,
    ) {
        write(scope, file, text.toByteArray(Charsets.UTF_8), failureMessage)
    }

    fun encryptFile(
        scope: String,
        plaintext: File,
        target: File,
        failureMessage: String,
    ) {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile("${target.name}.", ".part", target.parentFile)
        try {
            val key = dataKey(scope)
            FileOutputStream(temporary).use { output ->
                output.write(MAGIC)
                output.write(SEGMENTED_VERSION.toInt())
                FileInputStream(plaintext).use { input -> SegmentedEnvelope.encrypt(key, input, output) }
            }
            AtomicFileStore.commit(temporary, target, failureMessage)
        } finally {
            temporary.delete()
        }
    }

    /**
     * Decrypts [encrypted] into [target] through a temporary file. [shouldContinue] is asked before
     * every segment; once it returns false the decrypt stops with a [CopyInterruptedException],
     * nothing is committed and the partial plaintext is removed. A file in a whole-file legacy
     * format is asked once, before it starts: its provider decrypts it in one piece.
     */
    fun decryptFile(
        scope: String,
        encrypted: File,
        target: File,
        shouldContinue: () -> Boolean = { true },
    ) {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile("${target.name}.", ".part", target.parentFile)
        try {
            FileInputStream(encrypted).use { rawInput ->
                val version = readMagic(rawInput)
                FileOutputStream(temporary).use { output ->
                    if (version == SEGMENTED_VERSION) {
                        SegmentedEnvelope.decrypt(dataKey(scope), rawInput, output, shouldContinue)
                    } else {
                        if (!shouldContinue()) throw CopyInterruptedException()
                        val cipher = readLegacyHeader(scope, version, rawInput)
                        CipherInputStream(BufferedInputStream(rawInput), cipher).use { decryptedInput ->
                            BufferedOutputStream(output).use { buffered -> decryptedInput.copyTo(buffered) }
                        }
                    }
                }
            }
            AtomicFileStore.commit(temporary, target, "Could not materialize encrypted photo")
        } finally {
            temporary.delete()
        }
    }

    fun deleteKey(scope: String) {
        val alias = alias(scope)
        synchronized(lockFor(alias)) {
            keyStore.deleteEntry(alias)
            keyReferences.remove(alias)
            dataKeys.remove(alias)
            wrappedKeyFile(alias).delete()
        }
    }

    /** Checks the magic and returns the format version byte that follows it. */
    private fun readMagic(rawInput: InputStream): Byte {
        val magic = ByteArray(MAGIC.size)
        require(rawInput.read(magic) == magic.size && magic.contentEquals(MAGIC)) {
            "Encrypted file header is invalid"
        }
        val version = rawInput.read()
        require(version >= 0) { "Encrypted file is truncated" }
        return version.toByte()
    }

    private fun readLegacyHeader(
        scope: String,
        version: Byte,
        rawInput: InputStream,
    ): Cipher {
        val key = keyForVersion(scope, version)
        val ivSize = rawInput.read()
        require(ivSize in 12..16) { "Encrypted file IV is invalid" }
        val iv = ByteArray(ivSize)
        require(rawInput.read(iv) == ivSize) { "Encrypted file is truncated" }
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
    }

    private fun encrypt(
        scope: String,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, dataKey(scope))
        val encrypted = cipher.doFinal(plaintext)
        return ByteBuffer
            .allocate(MAGIC.size + 1 + 1 + cipher.iv.size + encrypted.size)
            .put(MAGIC)
            .put(VERSION)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
    }

    private fun decrypt(
        scope: String,
        payload: ByteArray,
    ): ByteArray {
        require(payload.size >= MAGIC.size + 2) { "Encrypted file is truncated" }
        val buffer = ByteBuffer.wrap(payload)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "Encrypted file header is invalid" }
        val key = keyForVersion(scope, buffer.get())
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 12..16 && buffer.remaining() > ivSize) { "Encrypted file IV is invalid" }
        val iv = ByteArray(ivSize).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        // The ciphertext is decrypted straight out of the payload; copying a preview-sized
        // array first only to hand it to doFinal doubled the allocation per read.
        return cipher.doFinal(payload, buffer.position(), buffer.remaining())
    }

    private fun keyForVersion(
        scope: String,
        version: Byte,
    ): SecretKey =
        when (version) {
            VERSION -> dataKey(scope)
            LEGACY_VERSION -> keystoreKey(scope)
            else -> throw IllegalArgumentException("Encrypted file version is unsupported")
        }

    /** Best effort: a file that cannot be rewritten simply stays in the slow legacy format. */
    private fun upgrade(
        scope: String,
        file: File,
        plaintext: ByteArray,
    ) {
        runCatching { AtomicFileStore.write(file, encrypt(scope, plaintext), "Could not upgrade encrypted file") }
    }

    /**
     * The per-scope data key, unwrapped once per process; created and wrapped on first use.
     * The lock is per alias, so the Keystore round trip for one scope never stalls another.
     */
    private fun dataKey(scope: String): SecretKey {
        val alias = alias(scope)
        dataKeys[alias]?.let { return it }
        return synchronized(lockFor(alias)) {
            dataKeys[alias] ?: run {
                val file = wrappedKeyFile(alias)
                val raw =
                    if (file.isFile) {
                        unwrap(scope, file.readBytes())
                    } else {
                        ByteArray(DATA_KEY_BYTES).also { bytes ->
                            SecureRandom().nextBytes(bytes)
                            AtomicFileStore.write(file, wrap(scope, bytes), "Could not store data key")
                        }
                    }
                SecretKeySpec(raw, KeyProperties.KEY_ALGORITHM_AES).also { key -> dataKeys[alias] = key }
            }
        }
    }

    private fun wrap(
        scope: String,
        rawKey: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(scope))
        val encrypted = cipher.doFinal(rawKey)
        return ByteBuffer
            .allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
    }

    private fun unwrap(
        scope: String,
        wrapped: ByteArray,
    ): ByteArray {
        val buffer = ByteBuffer.wrap(wrapped)
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 12..16 && buffer.remaining() > ivSize) { "Wrapped data key is invalid" }
        val iv = ByteArray(ivSize).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(scope), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(encrypted).also { raw ->
            require(raw.size == DATA_KEY_BYTES) { "Wrapped data key has the wrong size" }
        }
    }

    private fun wrappedKeyFile(alias: String): File = File(keyDirectory, "$alias.key")

    private fun keystoreKey(scope: String): SecretKey {
        val alias = alias(scope)
        keyReferences[alias]?.let { return it }
        return synchronized(lockFor(alias)) {
            keyReferences[alias] ?: (
                (keyStore.getKey(alias, null) as? SecretKey) ?: KeyGenerator
                    .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                    .apply {
                        init(
                            KeyGenParameterSpec
                                .Builder(
                                    alias,
                                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setKeySize(256)
                                .setRandomizedEncryptionRequired(true)
                                .build(),
                        )
                    }.generateKey()
            ).also { keyReferences[alias] = it }
        }
    }

    private fun alias(scope: String): String = ALIAS_PREFIX + AtomicFileStore.safeName(scope)

    /** One monitor per alias; a lock is never removed, and there are only a handful of scopes. */
    private fun lockFor(alias: String): Any = aliasLocks.computeIfAbsent(alias) { Any() }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_PREFIX = "lenswave.secure-file."
        const val KEY_DIRECTORY = "secure-keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val DATA_KEY_BYTES = 32
        val MAGIC = byteArrayOf(0x4c, 0x57, 0x45, 0x46)
        const val LEGACY_VERSION: Byte = 1
        const val VERSION: Byte = 2
        const val SEGMENTED_VERSION: Byte = 3
        val keyStore: KeyStore by lazy {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        }
        val aliasLocks = ConcurrentHashMap<String, Any>()
        val keyReferences = ConcurrentHashMap<String, SecretKey>()
        val dataKeys = ConcurrentHashMap<String, SecretKey>()
    }
}
