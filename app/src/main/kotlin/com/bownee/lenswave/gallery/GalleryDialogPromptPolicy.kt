package com.bownee.lenswave.gallery

/**
 * Whether a dialog fragment can go up now. The fragment manager's saved state is the lifecycle
 * guard (a show after onSaveInstanceState throws), and a fragment already under the tag means
 * the manager restored the dialog itself, so a second one must not stack on it.
 */
internal object GalleryDialogPromptPolicy {
    enum class Decision {
        /** Show the dialog now. */
        SHOW,

        /** The state is saved: keep the dialog and try again once the activity resumes. */
        WAIT,

        /** A dialog under the tag is already up; this one is not needed. */
        DROP,
    }

    fun decide(
        stateSaved: Boolean,
        dialogShowing: Boolean,
    ): Decision =
        when {
            dialogShowing -> Decision.DROP
            stateSaved -> Decision.WAIT
            else -> Decision.SHOW
        }
}
