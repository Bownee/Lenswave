package com.bownee.lenswave.viewer

import android.view.View

/**
 * Moves the five views that travel together as "the media" (the photo, the video player, the
 * thumbnail stand-in, the loading panel and the floating title) as one unit during swipes and
 * dismiss drags. The peek preview is deliberately not part of the set: it follows its own path.
 */
internal class ViewerMediaTransform(
    private val photoView: View,
    private val playerView: View,
    private val thumbnailPreview: View,
    private val loadingPanel: View,
    private val mediaTitle: View,
) {
    fun cancelMediaAnimations() {
        photoView.animate().cancel()
        playerView.animate().cancel()
        thumbnailPreview.animate().cancel()
        loadingPanel.animate().cancel()
        mediaTitle.animate().cancel()
    }

    fun setMediaTranslationX(translationX: Float) {
        photoView.translationX = translationX
        playerView.translationX = translationX
        thumbnailPreview.translationX = translationX
        loadingPanel.translationX = translationX
        // The date stays put while photos slide: it is a caption for the viewer, not part of the picture.
    }

    fun animateMediaTranslationX(
        translationX: Float,
        duration: Long,
    ) {
        photoView
            .animate()
            .translationX(translationX)
            .setDuration(duration)
            .start()
        playerView
            .animate()
            .translationX(translationX)
            .setDuration(duration)
            .start()
        thumbnailPreview
            .animate()
            .translationX(translationX)
            .setDuration(duration)
            .start()
        loadingPanel
            .animate()
            .translationX(translationX)
            .setDuration(duration)
            .start()
    }

    fun setMediaTranslationY(translationY: Float) {
        photoView.translationY = translationY
        playerView.translationY = translationY
        thumbnailPreview.translationY = translationY
        loadingPanel.translationY = translationY
        mediaTitle.translationY = translationY
    }

    fun setMediaDismissTransform(
        translationY: Float,
        scale: Float,
    ) {
        setMediaTranslationY(translationY)
        photoView.scaleX = scale
        photoView.scaleY = scale
        playerView.scaleX = scale
        playerView.scaleY = scale
        thumbnailPreview.scaleX = scale
        thumbnailPreview.scaleY = scale
    }

    fun animateMediaDismissTransform(
        translationY: Float,
        scale: Float,
        alpha: Float,
        duration: Long,
    ) {
        photoView
            .animate()
            .translationY(translationY)
            .scaleX(scale)
            .scaleY(scale)
            .alpha(alpha)
            .setDuration(duration)
            .setInterpolator(ViewerVerticalSettle.interpolator)
            .start()
        playerView
            .animate()
            .translationY(translationY)
            .scaleX(scale)
            .scaleY(scale)
            .alpha(alpha)
            .setDuration(duration)
            .setInterpolator(ViewerVerticalSettle.interpolator)
            .start()
        thumbnailPreview
            .animate()
            .translationY(translationY)
            .scaleX(scale)
            .scaleY(scale)
            .alpha(alpha)
            .setDuration(duration)
            .setInterpolator(ViewerVerticalSettle.interpolator)
            .start()
        loadingPanel
            .animate()
            .translationY(translationY)
            .alpha(alpha)
            .setDuration(duration)
            .setInterpolator(ViewerVerticalSettle.interpolator)
            .start()
        mediaTitle
            .animate()
            .translationY(translationY)
            .alpha(alpha)
            .setDuration(duration)
            .setInterpolator(ViewerVerticalSettle.interpolator)
            .start()
    }

    /** Sends one media view off-screen the way a dismissed photo leaves, without an end action. */
    fun animateDismissedMedia(
        view: View,
        targetY: Float,
        duration: Long,
    ) {
        view
            .animate()
            .translationY(targetY)
            .scaleX(DISMISSED_SCALE)
            .scaleY(DISMISSED_SCALE)
            .alpha(DISMISSED_ALPHA)
            .setDuration(duration)
            .setInterpolator(ViewerVerticalSettle.interpolator)
            .start()
    }

    companion object {
        const val DISMISSED_SCALE = 0.82f
        const val DISMISSED_ALPHA = 0.12f
    }
}
