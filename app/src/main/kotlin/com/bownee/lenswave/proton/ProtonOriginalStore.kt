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
     * file before the first segment is written, with the plaintext size the encrypted file
     * promises (null when its length does not fit the format), and [onBytesWritten] follows every
     * verified segment with the plaintext total; neither is called for a copy that was already on
     * disk. The growing file is renamed to the returned one once the whole original verified.
     */
    fun materialize(
        userId: String,
        nodeUid: String,
        shouldContinue: () -> Boolean,
        onStarted: (plaintextInProgress: File, expectedBytes: Long?) -> Unit,
        onBytesWritten: (totalBytes: Long) -> Unit,
    ): File?
}

/**
 * A download in flight: the private plaintext file it writes, the encrypted original it is
 * committed to, and the removal epoch it started in, see [ProtonRemovalEpochs]. The plaintext is
 * the download's own; the shared plaintext path is only ever written by a commit.
 */
data class ProtonOriginalTarget(
    val plaintext: File,
    val encrypted: File,
    val removalEpoch: Long,
)

/**
 * What a download's commit came to: [plaintext] is where the viewer reads the original from
 * now (the shared path, or the download's own file when it could not be moved), and
 * [encryptedStored] whether the encrypted original landed and is to be accounted for.
 */
data class ProtonOriginalCommit(
    val plaintext: File,
    val encryptedStored: Boolean,
)

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
internal class ProtonOriginalStore(
    cacheDir: File,
    private val secureFiles: SecureFileStore,
    private val clock: LenswaveClock,
    private val openCopies: ProtonDecryptedCopyRegistry,
    private val reportFailure: (LenswaveOperation, Throwable) -> Unit,
) : ProtonOriginalMaterializer {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        secureFiles: SecureFileStore,
        clock: LenswaveClock,
        openCopies: ProtonDecryptedCopyRegistry,
    ) : this(context.cacheDir, secureFiles, clock, openCopies, LenswaveDiagnostics::reportFailure)

    private val originals = File(cacheDir, ProtonStorageLayout.ORIGINALS_DIRECTORY).apply { mkdirs() }
    private val decrypted = File(cacheDir, ProtonStorageLayout.DECRYPTED_DIRECTORY)

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
     * Orders every commit of a plaintext copy or an encrypted original against [remove]: a
     * decrypt or a download that started before the photo was trashed must not bring its
     * files back once the removal has deleted them.
     */
    private val removals = ProtonRemovalEpochs()

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
    ): File? = materialize(userId, nodeUid, shouldContinue, onStarted = { _, _ -> }, onBytesWritten = {})

    override fun materialize(
        userId: String,
        nodeUid: String,
        shouldContinue: () -> Boolean,
        onStarted: (plaintextInProgress: File, expectedBytes: Long?) -> Unit,
        onBytesWritten: (totalBytes: Long) -> Unit,
    ): File? {
        wipeStaleDecryptedCopies()
        val file = file(userId, nodeUid)
        val materialized = decryptedFile(userId, nodeUid)
        // The copy comes first: only a gated commit ever writes the shared path, so a copy
        // within its TTL is the original whether or not the encrypted file beside it could
        // be written (see [commit]); it would be downloaded again otherwise.
        if (materialized.isFile && !isExpiredCopy(materialized)) {
            materialized.setLastModified(clock.nowMillis())
            file.setLastModified(clock.nowMillis())
            return materialized
        }
        if (!file.isFile || file.length() <= 0L) {
            deleteTracked(userId, file)
            return null
        }
        // Read before the decrypt: an oversized legacy original is deleted by the decrypt
        // itself, and its length is gone by the time the tracked total is adjusted below.
        val encryptedBytes = file.length()
        val startedIn = removals.current(userId, nodeUid)
        // The decrypt writes its own temporary and only the gated rename touches the shared
        // path, so a failure or a cancellation has nothing of the shared path to delete; a
        // copy another decrypt committed meanwhile is left alone.
        return try {
            materialized.delete()
            secureFiles.decryptFile(
                scope(userId),
                file,
                materialized,
                onStarted,
                onBytesWritten,
                commitGate = { commit ->
                    if (!removals.commitIf(userId, nodeUid, startedIn, commit)) throw ProtonOriginalRemovedException()
                },
                shouldContinue = shouldContinue,
            )
            transientReadFailures.recovered()
            file.setLastModified(clock.nowMillis())
            materialized.setLastModified(clock.nowMillis())
            materialized
        } catch (interrupted: CancellationException) {
            throw interrupted
        } catch (_: ProtonOriginalRemovedException) {
            // Trashed while it was being decrypted: the temporary is gone, and so is the original.
            null
        } catch (error: Exception) {
            // Only a provably bad original is dropped; a Keystore or I/O hiccup keeps the
            // encrypted file, and the next open decrypts it again.
            if (ProtonSnapshotCorruptionPolicy.isCorrupt(error)) {
                deleteTracked(userId, file, encryptedBytes)
            } else {
                transientReadFailures.report(error)
            }
            null
        }
    }

    /**
     * A private plaintext target for a download and the encrypted file it is committed to.
     * Two transfers of one node (a replacement started after the first was abandoned) each
     * own their own file, so the cleanup of one never deletes what the other is writing, and
     * neither writes the shared plaintext path before [commit].
     */
    fun createTarget(
        userId: String,
        nodeUid: String,
    ): ProtonOriginalTarget {
        wipeStaleDecryptedCopies()
        val target = file(userId, nodeUid)
        val materialized = decryptedFile(userId, nodeUid)
        materialized.parentFile?.mkdirs()
        target.parentFile?.mkdirs()
        val plaintext = File.createTempFile("${materialized.name}.", ".part", materialized.parentFile)
        return ProtonOriginalTarget(plaintext, target, removals.current(userId, nodeUid))
    }

    /**
     * Encrypts the downloaded plaintext into its original and moves the plaintext to the
     * shared path the viewer reads from. Both land under the node's removal lock and only
     * while the node has not been removed since [createTarget]; a download whose photo was
     * trashed meanwhile loses its plaintext and throws [ProtonOriginalRemovedException]
     * instead of resurrecting either file.
     *
     * A cache that cannot keep the original is not a failed download. An encrypt that fails
     * (a Keystore that will not answer, a full disk) is reported and the plaintext still
     * moves to the shared path, so the viewer reads it and the next open within the TTL
     * finds it instead of downloading again; a plaintext that cannot be moved is left where
     * the download wrote it, still readable, and swept as a stale partial. The result says
     * whether the encrypted file landed, since only then is there anything to account for.
     */
    fun commit(
        userId: String,
        nodeUid: String,
        download: ProtonOriginalTarget,
    ): ProtonOriginalCommit {
        val materialized = decryptedFile(userId, nodeUid)
        require(
            download.plaintext.parentFile == materialized.parentFile && download.encrypted == file(userId, nodeUid),
        ) {
            "Downloaded Proton media must use its own cache target"
        }
        var committed = false
        var encryptedStored = false
        var plaintextFailure: Exception? = null

        fun movePlaintext() {
            try {
                AtomicFileStore.commit(download.plaintext, materialized, "Could not materialize downloaded photo")
            } catch (error: Exception) {
                plaintextFailure = error
            }
        }
        try {
            secureFiles.encryptFile(
                scope(userId),
                download.plaintext,
                download.encrypted,
                "Could not protect downloaded photo",
            ) { commit ->
                committed =
                    removals.commitIf(userId, nodeUid, download.removalEpoch) {
                        commit()
                        encryptedStored = true
                        movePlaintext()
                    }
            }
        } catch (error: Exception) {
            // Nothing landed; the plaintext alone is committed, under the same gate.
            reportFailure(LenswaveOperation.ORIGINAL_CACHE_STORE, error)
            committed = removals.commitIf(userId, nodeUid, download.removalEpoch, ::movePlaintext)
        }
        if (!committed) {
            download.plaintext.delete()
            throw ProtonOriginalRemovedException()
        }
        val storedAt = clock.nowMillis()
        if (encryptedStored) download.encrypted.setLastModified(storedAt)
        val plaintext =
            plaintextFailure?.let { error ->
                reportFailure(LenswaveOperation.ORIGINAL_CACHE_STORE, error)
                download.plaintext
            } ?: materialized
        plaintext.setLastModified(storedAt)
        return ProtonOriginalCommit(plaintext, encryptedStored)
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

    /** Deletes the original and its plaintext copy, and refuses every decrypt or download commit that started before. */
    fun remove(
        userId: String,
        nodeUid: String,
    ) {
        removals.remove(userId, nodeUid) {
            deleteTracked(userId, file(userId, nodeUid))
            decryptedFile(userId, nodeUid).delete()
        }
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

    /**
     * Removes every original of one user; false when something could not be deleted. The user's
     * removal epochs go with the files: they ordered commits against removals of files that no
     * longer exist, and the transfers were stopped before this (see the gateway's disconnect).
     */
    fun clear(userId: String): Boolean {
        trackedBytes.remove(userId)
        removals.forget(userId)
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
        removals.retainOnly(userId)
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

    /**
     * Deletes one original and keeps the tracked total in step; a miss costs nothing. [size]
     * is the length the file had when it was counted, for a caller whose file may already be
     * gone: the total comes down by it whether this call or an earlier one removed the file.
     */
    private fun deleteTracked(
        userId: String,
        file: File,
        size: Long = file.length(),
    ) {
        if ((file.delete() || !file.exists()) && size > 0L) {
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
