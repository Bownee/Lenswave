package com.bownee.lenswave.proton

import android.content.Context
import com.bownee.lenswave.LenswaveClock
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import com.bownee.lenswave.storage.AtomicFileStore
import com.bownee.lenswave.storage.SecureFileStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decrypts a cached original for a reader that starts before the decrypt is over: the video
 * player. [ProtonMediaCache.readOriginal] is the same operation without the callbacks.
 */
internal interface ProtonOriginalMaterializer {
    /**
     * The plaintext copy, or null when nothing is cached. [onStarted] names the growing plaintext
     * file before the first segment is written and [onBytesWritten] follows every verified
     * segment with the plaintext total; neither is called for a copy that was already on disk.
     * The growing file is renamed to the returned one once the whole original verified.
     */
    fun materialize(
        userId: String,
        nodeUid: String,
        shouldContinue: () -> Boolean,
        onStarted: (plaintextInProgress: File) -> Unit,
        onBytesWritten: (totalBytes: Long) -> Unit,
    ): File?
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonOriginalStoreModule {
    @Binds abstract fun bindMaterializer(implementation: ProtonOriginalStore): ProtonOriginalMaterializer
}

/**
 * Encrypted on-disk store for full-size originals plus the short-lived plaintext copies the
 * viewer and the video player read from.
 *
 * Encrypted originals live under `cacheDir` and are kept per user up to
 * [ProtonStorageLayout.ORIGINALS_CACHE_LIMIT_BYTES]; the least recently read ones go first.
 * Plaintext copies expire after [ProtonStorageLayout.DECRYPTED_TTL_MILLIS] and are wiped
 * wholesale once per process. A copy a reader holds open (see [ProtonDecryptedCopyRegistry]) is
 * never expired: the player keeps its file for as long as the video is up, and the copy's age
 * restarts when the reader closes it.
 */
@Singleton
internal class ProtonOriginalStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val secureFiles: SecureFileStore,
        private val clock: LenswaveClock,
        private val openCopies: ProtonDecryptedCopyRegistry,
    ) : ProtonOriginalMaterializer {
        private val originals = File(context.cacheDir, ProtonStorageLayout.ORIGINALS_DIRECTORY).apply { mkdirs() }
        private val decrypted = File(context.cacheDir, ProtonStorageLayout.DECRYPTED_DIRECTORY)

        /**
         * Plaintext copies from a previous process are wiped once, by the first read or download
         * target of the process, which run on an I/O dispatcher, so nothing materializes a new
         * copy next to stale ones. Doing it in the constructor would delete potentially hundreds
         * of megabytes on the main thread while Hilt builds the object graph.
         */
        @Volatile private var decryptedWiped = false

        /**
         * Bytes in each user's originals directory, established from one listing and kept up to
         * date by stores and removals, so a stored download only lists and stats the whole
         * directory when the total has actually passed the cap. Every trim re-establishes the
         * exact figure from its own listing.
         */
        private val trackedBytes = ConcurrentHashMap<String, Long>()
        private val transientReadFailures = ProtonRenditionReadFailures()

        /**
         * The plaintext copy the viewer reads, decrypting it on demand; null when nothing is cached.
         *
         * [shouldContinue] is consulted between decrypt segments. When it turns false the decrypt
         * stops with a [CancellationException]; the encrypted original is kept, because the caller
         * lost interest, not the file its integrity. Callers outside a coroutine leave the default.
         */
        fun read(
            userId: String,
            nodeUid: String,
            shouldContinue: () -> Boolean = { true },
        ): File? = materialize(userId, nodeUid, shouldContinue, onStarted = {}, onBytesWritten = {})

        override fun materialize(
            userId: String,
            nodeUid: String,
            shouldContinue: () -> Boolean,
            onStarted: (plaintextInProgress: File) -> Unit,
            onBytesWritten: (totalBytes: Long) -> Unit,
        ): File? {
            wipeStaleDecryptedCopies()
            val file = file(userId, nodeUid)
            if (!file.isFile || file.length() <= 0L) {
                deleteTracked(userId, file)
                return null
            }
            val materialized = decryptedFile(userId, nodeUid)
            if (materialized.isFile && !isExpiredCopy(materialized)) {
                materialized.setLastModified(clock.nowMillis())
                file.setLastModified(clock.nowMillis())
                return materialized
            }
            return try {
                materialized.delete()
                secureFiles.decryptFile(scope(userId), file, materialized, onStarted, onBytesWritten, shouldContinue)
                transientReadFailures.recovered()
                file.setLastModified(clock.nowMillis())
                materialized.setLastModified(clock.nowMillis())
                materialized
            } catch (interrupted: CancellationException) {
                materialized.delete()
                throw interrupted
            } catch (error: Exception) {
                materialized.delete()
                // Only a provably bad original is dropped; a Keystore or I/O hiccup keeps the
                // encrypted file, and the next open decrypts it again.
                if (ProtonSnapshotCorruptionPolicy.isCorrupt(error)) {
                    deleteTracked(userId, file)
                } else {
                    transientReadFailures.report(error)
                }
                null
            }
        }

        /** The plaintext download target and the encrypted file it is committed to. */
        fun createTarget(
            userId: String,
            nodeUid: String,
        ): Pair<File, File> {
            wipeStaleDecryptedCopies()
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
            val stored = target.length()
            // A first listing already includes the file that was just committed. It runs before
            // the map is touched: listing a directory inside compute would hold the map's bin lock
            // across every stat.
            val listed = if (trackedBytes.containsKey(userId)) null else directoryBytes(userId)
            val total =
                if (listed != null && trackedBytes.putIfAbsent(userId, listed) == null) {
                    listed
                } else {
                    checkNotNull(trackedBytes.merge(userId, stored) { current, added -> current + added })
                }
            if (total > ProtonStorageLayout.ORIGINALS_CACHE_LIMIT_BYTES) trimToLimit(userId, keepName = target.name)
        }

        fun maintain(userId: String) {
            wipeStaleDecryptedCopies()
            expireCopies(decryptedDirectory(userId))
            trimToLimit(userId, keepName = null)
        }

        /**
         * Deletes every plaintext copy, of any user, that is past
         * [ProtonStorageLayout.DECRYPTED_TTL_MILLIS]. [maintain] does the same for one user once
         * per activation; this is for the gallery when the app leaves the screen, so plaintext
         * does not sit on disk for the rest of the process just because nothing re-activated the
         * account. Copies still within their TTL are left, since the viewer may come back to them,
         * and so is a copy a player holds open, however old.
         */
        fun sweepExpiredDecryptedCopies() {
            wipeStaleDecryptedCopies()
            decrypted.listFiles()?.filter(File::isDirectory)?.forEach(::expireCopies)
        }

        fun remove(
            userId: String,
            nodeUid: String,
        ) {
            deleteTracked(userId, file(userId, nodeUid))
            decryptedFile(userId, nodeUid).delete()
        }

        /**
         * Drops stale partial downloads and completed files that no longer belong to a known node;
         * [retainedNames] are file names without extension, as [AtomicFileStore.safeName] produces them.
         */
        fun removeUnreferenced(
            userId: String,
            retainedNames: Set<String>,
        ) {
            listOf(directory(userId), decryptedDirectory(userId)).forEach { directory ->
                directory.listFiles()?.forEach { file ->
                    if (isStalePartial(file) ||
                        (file.extension != "part" && file.nameWithoutExtension !in retainedNames)
                    ) {
                        file.delete()
                    }
                }
            }
            trackedBytes.remove(userId)
        }

        /** Removes every original of one user; false when something could not be deleted. */
        fun clear(userId: String): Boolean {
            trackedBytes.remove(userId)
            val originalsDeleted = directory(userId).deleteRecursively()
            val decryptedDeleted = decryptedDirectory(userId).deleteRecursively()
            return originalsDeleted && decryptedDeleted
        }

        /**
         * Best effort, like the cache's own sweep: a directory that resists deletion is reported
         * once and left for the next account transition. Throwing here failed that transition,
         * which the session manager then retried forever.
         */
        fun retainOnly(userId: String?) {
            trackedBytes.keys.removeAll { key -> key != userId }
            val retainedName = userId?.let(AtomicFileStore::safeName)
            var deleted = true
            listOf(originals, decrypted).forEach { root ->
                root.listFiles()?.filter { it.name != retainedName }?.forEach { directory ->
                    deleted = directory.deleteRecursively() && deleted
                }
            }
            if (!deleted) {
                LenswaveDiagnostics.reportFailure(
                    LenswaveOperation.CACHE_CLEAR,
                    IllegalStateException("Could not remove all orphaned Proton originals; residue is swept later"),
                )
            }
        }

        /** Runs once per process; every later call returns at once, without taking the lock. */
        fun wipeStaleDecryptedCopies() {
            if (decryptedWiped) return
            synchronized(this) {
                if (decryptedWiped) return
                decrypted.deleteRecursively()
                decrypted.mkdirs()
                decryptedWiped = true
            }
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
            var remainingBytes = entries.sumOf(ProtonOriginalTrimPolicy.Entry::sizeBytes)
            val sizes = entries.associate { entry -> entry.name to entry.sizeBytes }
            ProtonOriginalTrimPolicy
                .select(
                    entries,
                    limitBytes = ProtonStorageLayout.ORIGINALS_CACHE_LIMIT_BYTES,
                    nowMillis = clock.nowMillis(),
                    stalePartTtlMillis = ProtonStorageLayout.STALE_PART_TTL_MILLIS,
                    keepName = keepName,
                ).forEach { name ->
                    if (File(directory, name).delete()) remainingBytes -= sizes.getValue(name)
                }
            trackedBytes[userId] = remainingBytes
        }

        private fun directoryBytes(userId: String): Long =
            directory(userId)
                .listFiles()
                ?.sumOf { file -> if (file.isFile) file.length() else 0L }
                ?: 0L

        /** Deletes one original and keeps the tracked total in step; a miss costs nothing. */
        private fun deleteTracked(
            userId: String,
            file: File,
        ) {
            val size = file.length()
            if (file.delete() && size > 0L) {
                trackedBytes.computeIfPresent(userId) { _, total -> (total - size).coerceAtLeast(0L) }
            }
        }

        /** Deletes the plaintext copies in [directory] past their TTL, except those a reader holds open. */
        private fun expireCopies(directory: File) {
            directory
                .listFiles()
                ?.filter { file -> file.isFile && isExpiredCopy(file) }
                ?.forEach(File::delete)
        }

        private fun isExpiredCopy(file: File): Boolean =
            !openCopies.isInUse(file) && isExpired(file, ProtonStorageLayout.DECRYPTED_TTL_MILLIS)

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
