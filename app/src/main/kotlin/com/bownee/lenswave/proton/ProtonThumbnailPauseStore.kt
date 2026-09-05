package com.bownee.lenswave.proton

import android.content.Context
import androidx.core.content.edit
import com.bownee.lenswave.storage.AtomicFileStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.proton.core.domain.entity.UserId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the user has paused background downloads from the worker's notification. The flag
 * outlives the process: cancelling one worker id only stopped that run, and the next resume or
 * refresh enqueued another, so the pause the user asked for lasted seconds. Every request for a
 * run honours the flag ([ProtonThumbnailWorkScheduler]), the worker exits at once under it, and
 * a manual refresh clears it.
 */
internal interface ProtonThumbnailPauseStore {
    fun isPaused(userId: UserId): Boolean

    fun setPaused(
        userId: UserId,
        paused: Boolean,
    )

    /** Loads whatever backs the store off the main thread; stores that need no loading ignore it. */
    fun warm() {}
}

/**
 * The first read of a SharedPreferences file is a disk read, and [isPaused] is asked on the main
 * thread by every enqueue the gallery makes. The file is opened on the IO dispatcher as soon as
 * the store exists (and by [warm], for a start-up that wants to be sure), so the main thread
 * finds it loaded.
 */
@Singleton
internal class SharedPreferencesProtonThumbnailPauseStore(
    context: Context,
    warmScope: CoroutineScope,
) : ProtonThumbnailPauseStore {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val preferences by lazy { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }

    init {
        warmScope.launch { preferences }
    }

    override fun warm() {
        preferences
    }

    override fun isPaused(userId: UserId): Boolean = preferences.getBoolean(key(userId), false)

    override fun setPaused(
        userId: UserId,
        paused: Boolean,
    ) {
        preferences.edit {
            if (paused) putBoolean(key(userId), true) else remove(key(userId))
        }
    }

    private fun key(userId: UserId): String = "$KEY_PAUSED_PREFIX${AtomicFileStore.safeName(userId.id)}"

    private companion object {
        const val PREFERENCES_NAME = "thumbnail-downloads"
        const val KEY_PAUSED_PREFIX = "paused-"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonThumbnailPauseStoreModule {
    @Binds
    abstract fun bindProtonThumbnailPauseStore(
        implementation: SharedPreferencesProtonThumbnailPauseStore,
    ): ProtonThumbnailPauseStore
}
