package com.bownee.lenswave.gallery

/**
 * Whether a dialog fragment can go up now. The fragment manager's saved state is the lifecycle
 * guard (a show after onSaveInstanceState throws), and a fragment already under the tag means
 * the manager restored the dialog itself, so a second one must not stack on it.
 */
internal object GalleryDialogPromptPolicy {
    fun canShow(
        stateSaved: Boolean,
        dialogShowing: Boolean,
    ): Boolean = !stateSaved && !dialogShowing
}
