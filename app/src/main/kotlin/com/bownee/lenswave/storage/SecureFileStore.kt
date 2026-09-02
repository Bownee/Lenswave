package com.bownee.lenswave.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Authenticated app-private storage backed by a non-exportable Android Keystore key per scope. */
@Singleton
class SecureFileStore @Inject constructor() {
    fun read(scope: String, file: File): ByteArray = decrypt(scope, file.readBytes())

    fun readText(scope: String, file: File): String = read(scope, file).toString(Charsets.UTF_8)

    fun write(scope: String, file: File, bytes: ByteArray, failureMessage: String) {
        AtomicFileStore.write(file, encrypt(scope, bytes), failureMessage)
    }

    fun writeText(scope: String, file: File, text: String, failureMessage: String) {
        write(scope, file, text.toByteArray(Charsets.UTF_8), failureMessage)
    }

    fun encryptFile(scope: String, plaintext: File, target: File, failureMessage: String) {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile("${target.name}.", ".part", target.parentFile)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key(scope))
            }
            FileOutputStream(temporary).use { rawOutput ->
                rawOutput.write(MAGIC)
                rawOutput.write(byteArrayOf(VERSION, cipher.iv.size.toByte()))
                rawOutput.write(cipher.iv)
                CipherOutputStream(BufferedOutputStream(rawOutput), cipher).use { encryptedOutput ->
                    BufferedInputStream(FileInputStream(plaintext)).use { input -> input.copyTo(encryptedOutput) }
                }
            }
            AtomicFileStore.commit(temporary, target, failureMessage)
        } finally {
            temporary.delete()
        }
    }

    fun decryptFile(scope: String, encrypted: File, target: File) {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile("${target.name}.", ".part", target.parentFile)
        try {
            FileInputStream(encrypted).use { rawInput ->
                val magic = ByteArray(MAGIC.size)
                require(rawInput.read(magic) == magic.size && magic.contentEquals(MAGIC)) {
                    "Encrypted file header is invalid"
                }
                require(rawInput.read() == VERSION.toInt()) { "Encrypted file version is unsupported" }
                val ivSize = rawInput.read()
                require(ivSize in 12..16) { "Encrypted file IV is invalid" }
                val iv = ByteArray(ivSize)
                require(rawInput.read(iv) == ivSize) { "Encrypted file is truncated" }
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, key(scope), GCMParameterSpec(TAG_BITS, iv))
                }
                CipherInputStream(BufferedInputStream(rawInput), cipher).use { decryptedInput ->
                    BufferedOutputStream(FileOutputStream(temporary)).use(decryptedInput::copyTo)
                }
            }
            AtomicFileStore.commit(temporary, target, "Could not materialize encrypted photo")
        } finally {
            temporary.delete()
        }
    }

    fun deleteKey(scope: String) {
        synchronized(keyStoreLock) {
            keyStore().deleteEntry(alias(scope))
        }
    }

    fun encrypt(scope: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(scope))
        val encrypted = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(MAGIC.size + 1 + 1 + cipher.iv.size + encrypted.size)
            .put(MAGIC)
            .put(VERSION)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
    }

    fun decrypt(scope: String, payload: ByteArray): ByteArray {
        require(payload.size >= MAGIC.size + 2) { "Encrypted file is truncated" }
        val buffer = ByteBuffer.wrap(payload)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "Encrypted file header is invalid" }
        require(buffer.get() == VERSION) { "Encrypted file version is unsupported" }
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 12..16 && buffer.remaining() > ivSize) { "Encrypted file IV is invalid" }
        val iv = ByteArray(ivSize).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(scope), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun key(scope: String): SecretKey = synchronized(keyStoreLock) {
        val alias = alias(scope)
        (keyStore().getKey(alias, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
            }
            .generateKey()
    }

    private fun alias(scope: String): String = ALIAS_PREFIX + MessageDigest
        .getInstance("SHA-256")
        .digest(scope.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_PREFIX = "lenswave.secure-file."
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        val MAGIC = byteArrayOf(0x4c, 0x57, 0x45, 0x46)
        const val VERSION: Byte = 1
        val keyStoreLock = Any()
    }
}
