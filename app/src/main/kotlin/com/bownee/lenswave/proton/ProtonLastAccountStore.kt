package com.bownee.lenswave.proton

import android.content.Context
import androidx.core.content.edit
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
 * The account that was active when the process last ran. Proton Core needs the session
 * database open before it can say which account is signed in, a few hundred milliseconds into
 * a launch; this lets the next launch activate that account's cached listings meanwhile (see
 * [ProtonAccountTransitionCoordinator.preloadLastAccount]). It is a hint, never an authority:
 * whatever Core then reports wins, and a mismatch tears the preloaded account down again.
 */
interface ProtonLastAccountStore {
    fun read(): UserId?

    /** Null once no account is signed in. */
    fun write(userId: UserId?)
}

@Singleton
internal class SharedPreferencesProtonLastAccountStore(
    context: Context,
    warmScope: CoroutineScope,
) : ProtonLastAccountStore {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val preferences by lazy { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }

    init {
        warmScope.launch { preferences }
    }

    override fun read(): UserId? = preferences.getString(KEY_USER_ID, null)?.let(::UserId)

    override fun write(userId: UserId?) {
        preferences.edit {
            if (userId == null) remove(KEY_USER_ID) else putString(KEY_USER_ID, userId.id)
        }
    }

    companion object {
        internal const val PREFERENCES_NAME = "proton-last-account"
        private const val KEY_USER_ID = "user-id"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProtonLastAccountStoreModule {
    @Binds
    abstract fun bindProtonLastAccountStore(
        implementation: SharedPreferencesProtonLastAccountStore,
    ): ProtonLastAccountStore
}
