package com.bownee.lenswave.proton

import android.content.Context
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.storage.AtomicFileStore
import com.bownee.lenswave.storage.SecureFileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted on-disk store for full-size originals plus the short-lived plaintext copies the
 * viewer and the video player read from.
 *
 * Encrypted originals live under `cacheDir` and are kept per user up to
 * [ProtonStorageLayout.ORIGINALS_CACHE_LIMIT_BYTES]; the least recently read ones go first.
 * Plaintext copies expire after [ProtonStorageLayout.DECRYPTED_TTL_MILLIS] and are wiped
 * wholesale once per process.
 */
@Singleton
internal class ProtonOriginalStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val secureFiles: SecureFileStore,
        private val clock: LenswaveClock,
    ) {
        private val originals = File(context.cacheDir, ProtonStorageLayout.ORIGINALS_DIRECTORY).apply { mkdirs() }
        private val decrypted = File(context.cacheDir, ProtonStorageLayout.DECRYPTED_DIRECTORY)

        /**
         * Plaintext copies from a previous process are wiped once, on the first session activation,
         * which runs on an I/O dispatcher. Doing it in the constructor would delete potentially
         * hundreds of megabytes on the main thread while Hilt builds the object graph.
         */
        @Volatile private var decryptedWiped = false

        /** The plaintext copy the viewer reads, decrypting it on demand; null when nothing is cached. */
        fun read(
            userId: String,
            nodeUid: String,
        ): File? {
            val file = file(userId, nodeUid)
            if (!file.isFile || file.length() <= 0L) {
                file.delete()
                return null
            }
            val materialized = decryptedFile(userId, nodeUid)
            if (materialized.isFile && !isExpired(materialized, ProtonStorageLayout.DECRYPTED_TTL_MILLIS)) {
                materialized.setLastModified(clock.nowMillis())
                file.setLastModified(clock.nowMillis())
                return materialized
            }
            return runCatching {
                materialized.delete()
                secureFiles.decryptFile(scope(userId), file, materialized)
                file.setLastModified(clock.nowMillis())
                materialized.setLastModified(clock.nowMillis())
                materialized
            }.getOrElse {
                materialized.delete()
                file.delete()
                null
            }
        }

        /** The plaintext download target and the encrypted file it is committed to. */
        fun createTarget(
            userId: String,
            nodeUid: String,
        ): Pair<File, File> {
            val target = file(userId, nodeUid)
            val materialized = decryptedFile(userId, nodeUid)
            materialized.parentFile?.mkdirs()
            target.parentFile?.mkdirs()
            check(materialized.delete() || !materialized.exists()) {
                "Could not replace materialized Proton media"
            }
            return materialized to target
        }

        fun commit(
            userId: String,
            nodeUid: String,
            plaintext: File,
            target: File,
        ): File {
            require(plaintext == decryptedFile(userId, nodeUid)) {
                "Downloaded Proton media must use its materialized cache target"
            }
            secureFiles.encryptFile(scope(userId), plaintext, target, "Could not protect downloaded photo")
            val storedAt = clock.nowMillis()
            target.setLastModified(storedAt)
            plaintext.setLastModified(storedAt)
            return plaintext
        }

        /** Marks [target] as the most recent original and evicts older ones beyond the size limit. */
        fun onStored(
            userId: String,
            target: File,
        ) {
            target.setLastModified(clock.nowMillis())
            trimToLimit(userId, keepName = target.name)
        }

        fun maintain(userId: String) {
            wipeStaleDecryptedCopies()
            expireFiles(decryptedDirectory(userId), ProtonStorageLayout.DECRYPTED_TTL_MILLIS)
            trimToLimit(userId, keepName = null)
        }

        fun remove(
            userId: String,
            nodeUid: String,
        ) {
            file(userId, nodeUid).delete()
            decryptedFile(userId, nodeUid).delete()
        }

        /** Drops stale partial downloads and completed files that no longer belong to a known node. */
        fun removeUnreferenced(
            userId: String,
            retainedNodeUids: Collection<String>,
        ) {
            val retainedNames = retainedNodeUids.mapTo(mutableSetOf(), AtomicFileStore::safeName)
            listOf(directory(userId), decryptedDirectory(userId)).forEach { directory ->
                directory.listFiles()?.forEach { file ->
                    if (isStalePartial(file) ||
                        (file.extension != "part" && file.nameWithoutExtension !in retainedNames)
                    ) {
                        file.delete()
                    }
                }
            }
        }

        /** Removes every original of one user; false when something could not be deleted. */
        fun clear(userId: String): Boolean {
            val originalsDeleted = directory(userId).deleteRecursively()
            val decryptedDeleted = decryptedDirectory(userId).deleteRecursively()
            return originalsDeleted && decryptedDeleted
        }

        fun retainOnly(userId: String?) {
            val retainedName = userId?.let(AtomicFileStore::safeName)
            originals.listFiles()?.filter { it.name != retainedName }?.forEach { directory ->
                check(directory.deleteRecursively()) { "Could not remove orphaned Proton originals" }
            }
            decrypted.listFiles()?.filter { it.name != retainedName }?.forEach { directory ->
                check(directory.deleteRecursively()) { "Could not remove orphaned decrypted Proton media" }
            }
        }

        @Synchronized
        private fun wipeStaleDecryptedCopies() {
            if (decryptedWiped) return
            decrypted.deleteRecursively()
            decrypted.mkdirs()
            decryptedWiped = true
        }

        private fun trimToLimit(
            userId: String,
            keepName: String?,
        ) {
            val directory = directory(userId)
            val entries =
                directory
                    .listFiles()
                    ?.filter(File::isFile)
                    ?.map { file -> ProtonOriginalTrimPolicy.Entry(file.name, file.length(), file.lastModified()) }
                    .orEmpty()
            ProtonOriginalTrimPolicy
                .select(
                    entries,
                    limitBytes = ProtonStorageLayout.ORIGINALS_CACHE_LIMIT_BYTES,
                    nowMillis = clock.nowMillis(),
                    stalePartTtlMillis = ProtonStorageLayout.STALE_PART_TTL_MILLIS,
                    keepName = keepName,
                ).forEach { name -> File(directory, name).delete() }
        }

        private fun expireFiles(
            directory: File,
            ttlMillis: Long,
        ) {
            directory
                .listFiles()
                ?.filter { file -> file.isFile && isExpired(file, ttlMillis) }
                ?.forEach(File::delete)
        }

        private fun file(
            userId: String,
            nodeUid: String,
        ): File = File(directory(userId), "${AtomicFileStore.safeName(nodeUid)}.$EXTENSION")

        private fun directory(userId: String): File = File(originals, AtomicFileStore.safeName(userId))

        private fun decryptedFile(
            userId: String,
            nodeUid: String,
        ): File = File(decryptedDirectory(userId), "${AtomicFileStore.safeName(nodeUid)}.$EXTENSION")

        private fun decryptedDirectory(userId: String): File = File(decrypted, AtomicFileStore.safeName(userId))

        private fun scope(userId: String): String = ProtonStorageLayout.mediaScope(userId)

        private fun isStalePartial(file: File): Boolean =
            file.extension == "part" && isExpired(file, ProtonStorageLayout.STALE_PART_TTL_MILLIS)

        private fun isExpired(
            file: File,
            ttlMillis: Long,
        ): Boolean = file.lastModified() <= 0L || clock.nowMillis() - file.lastModified() > ttlMillis

        private companion object {
            const val EXTENSION = "image"
        }
    }

/** Pure eviction rule for one user's encrypted originals directory. */
internal object ProtonOriginalTrimPolicy {
    data class Entry(
        val name: String,
        val sizeBytes: Long,
        val lastModifiedMillis: Long,
    )

    /**
     * Names to delete so the directory fits [limitBytes]: abandoned partial downloads first, then
     * the least recently used files. A partial download that is still fresh is never deleted, and
     * neither is [keepName], the original that was just stored.
     */
    fun select(
        entries: List<Entry>,
        limitBytes: Long,
        nowMillis: Long,
        stalePartTtlMillis: Long,
        keepName: String? = null,
    ): List<String> {
        val (partials, completed) = entries.partition { it.name.endsWith(".part") }
        val stalePartials =
            partials.filter { it.lastModifiedMillis <= 0L || nowMillis - it.lastModifiedMillis > stalePartTtlMillis }
        val deleted = stalePartials.mapTo(mutableListOf(), Entry::name)
        var totalBytes = entries.sumOf(Entry::sizeBytes) - stalePartials.sumOf(Entry::sizeBytes)
        val candidates = completed.filter { it.name != keepName }.sortedBy(Entry::lastModifiedMillis)
        for (entry in candidates) {
            if (totalBytes <= limitBytes) break
            deleted += entry.name
            totalBytes -= entry.sizeBytes
        }
        return deleted
    }
}
