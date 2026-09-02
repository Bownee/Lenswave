package com.bownee.lenswave.update

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal data class AppUpdateState(
    val latestVersionName: String? = null,
    val etag: String? = null,
    val nextCheckAtMillis: Long = 0L,
    val snoozedVersionName: String? = null,
    val snoozedUntilMillis: Long = 0L,
)

internal interface AppUpdateStateStore {
    fun read(): AppUpdateState
    fun write(state: AppUpdateState)
}

@Singleton
internal class SharedPreferencesAppUpdateStateStore @Inject constructor(
    @ApplicationContext context: Context,
) : AppUpdateStateStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): AppUpdateState = AppUpdateState(
        latestVersionName = preferences.getString(KEY_LATEST_VERSION, null),
        etag = preferences.getString(KEY_ETAG, null),
        nextCheckAtMillis = preferences.getLong(KEY_NEXT_CHECK_AT, 0L),
        snoozedVersionName = preferences.getString(KEY_SNOOZED_VERSION, null),
        snoozedUntilMillis = preferences.getLong(KEY_SNOOZED_UNTIL, 0L),
    )

    override fun write(state: AppUpdateState) {
        preferences.edit {
            putString(KEY_LATEST_VERSION, state.latestVersionName)
            putString(KEY_ETAG, state.etag)
            putLong(KEY_NEXT_CHECK_AT, state.nextCheckAtMillis)
            putString(KEY_SNOOZED_VERSION, state.snoozedVersionName)
            putLong(KEY_SNOOZED_UNTIL, state.snoozedUntilMillis)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "app-updates"
        const val KEY_LATEST_VERSION = "latest-version"
        const val KEY_ETAG = "etag"
        const val KEY_NEXT_CHECK_AT = "next-check-at"
        const val KEY_SNOOZED_VERSION = "snoozed-version"
        const val KEY_SNOOZED_UNTIL = "snoozed-until"
    }
}
