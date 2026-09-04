package com.bownee.lenswave.viewer

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's choices about what the viewer lets out of the app. Read on the main thread in the
 * viewer's onCreate, so the value is kept in memory after the first read and the preference file
 * can be loaded ahead of time with [warm].
 */
@Singleton
class ViewerPrivacySettings
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        /** The value last read or written; null until the file has been consulted once. */
        @Volatile
        private var cachedBlockScreenshots: Boolean? = null

        /** Whether the viewer's window is marked secure, which keeps it out of screenshots and screen recordings. */
        var blockScreenshots: Boolean
            get() =
                cachedBlockScreenshots
                    ?: preferences.getBoolean(KEY_BLOCK_SCREENSHOTS, false).also { cachedBlockScreenshots = it }
            set(value) {
                cachedBlockScreenshots = value
                preferences.edit { putBoolean(KEY_BLOCK_SCREENSHOTS, value) }
            }

        companion object {
            const val PREFERENCES_NAME = "viewer-privacy"
            private const val KEY_BLOCK_SCREENSHOTS = "block-screenshots"

            /**
             * Loads the preference file off the main thread so the viewer's first read finds it
             * in memory. The platform caches the SharedPreferences instance per name, so the
             * injected instance shares the load. For the application's start-up warm-up.
             */
            fun warm(context: Context) {
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).all
            }
        }
    }
