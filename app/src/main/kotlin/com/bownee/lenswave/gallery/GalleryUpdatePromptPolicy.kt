package com.bownee.lenswave.gallery

/**
 * Whether the update dialog for a pending version can go up now. The fragment manager's saved
 * state is the only lifecycle guard needed: a show after onSaveInstanceState is lost (or
 * throws), while one during onResume is fine even though the lifecycle registry is still
 * STARTED at that point, so a RESUMED check would keep a restored pending version waiting
 * forever.
 */
internal object GalleryUpdatePromptPolicy {
    enum class Decision {
        /** Show the dialog and forget the pending version. */
        SHOW,

        /** Keep the pending version for a later attempt. */
        WAIT,

        /** The dialog is already up (restored by the fragment manager): forget the pending version. */
        ALREADY_SHOWING,

        /** Nothing is pending. */
        NOTHING,
    }

    fun decide(
        pendingVersionName: String?,
        stateSaved: Boolean,
        dialogShowing: Boolean,
    ): Decision =
        when {
            pendingVersionName == null -> Decision.NOTHING
            stateSaved -> Decision.WAIT
            dialogShowing -> Decision.ALREADY_SHOWING
            else -> Decision.SHOW
        }
}
