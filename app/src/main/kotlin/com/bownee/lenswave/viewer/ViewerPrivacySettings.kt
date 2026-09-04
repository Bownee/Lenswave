package com.bownee.lenswave.viewer

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The user's choices about what the viewer lets out of the app; read on the main thread, so kept tiny. */
@Singleton
class ViewerPrivacySettings
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        /** Whether the viewer's window is marked secure, which keeps it out of screenshots and screen recordings. */
        var blockScreenshots: Boolean
            get() = preferences.getBoolean(KEY_BLOCK_SCREENSHOTS, false)
            set(value) = preferences.edit { putBoolean(KEY_BLOCK_SCREENSHOTS, value) }

        internal companion object {
            const val PREFERENCES_NAME = "viewer-privacy"
            private const val KEY_BLOCK_SCREENSHOTS = "block-screenshots"
        }
    }
