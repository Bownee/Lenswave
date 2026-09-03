package com.bownee.lenswave.viewer

import android.app.Activity
import android.os.Build
import android.view.View

/**
 * The downward drag that shrinks the photo towards the gallery and either snaps it back or
 * finishes the viewer. The Activity stays the owner of the `dismissing` flag; this controller
 * only asks for it to be raised through [Host.beginDismiss].
 */
internal class ViewerDismissController(
    private val activity: Activity,
    private val screen: PhotoViewerScreen,
    private val mediaTransform: ViewerMediaTransform,
    private val host: Host,
) {
    internal interface Host {
        /** True while a photo transition or a dismiss is already running. */
        val gesturesBlocked: Boolean
        val dismissing: Boolean
        val detailsShown: Boolean
        fun activeMediaView(): View

        /** Marks the viewer as dismissing and disables the action buttons. */
        fun beginDismiss()
    }

    private val root get() = screen.root
    private val backgroundScrim get() = screen.backgroundScrim
    private val photoView get() = screen.photoView
    private val playerView get() = screen.playerView
    private val thumbnailPreview get() = screen.thumbnailPreview
    private val loadingPanel get() = screen.loadingPanel
    private val mediaTitle get() = screen.mediaTitle
    private val actions get() = screen.actions
    private val detailsSheet get() = screen.detailsSheet
    private val density get() = activity.resources.displayMetrics.density

    fun handlePhotoDismissDrag(distance: Float, velocity: Float, finished: Boolean) {
        if (host.gesturesBlocked) return
        if (finished) {
            if (VerticalGesturePolicy.shouldDismissViewer(
                    distance,
                    velocity,
                    root.height.toFloat(),
                    density,
                )
            ) {
                animateDismissToGallery(velocity)
            } else {
                resetPhotoDismiss(velocity)
            }
            return
        }

        mediaTransform.cancelMediaAnimations()
        backgroundScrim.animate().cancel()
        actions.animate().cancel()
        mediaTitle.animate().cancel()
        val dismissProgress = (distance / (root.height * 0.58f).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val photoScale = 1f - 0.12f * dismissProgress
        mediaTransform.setMediaDismissTransform(distance, photoScale)
        loadingPanel.alpha = 1f - dismissProgress
        backgroundScrim.alpha = 1f - 0.88f * dismissProgress
        actions.alpha = 1f - dismissProgress
        mediaTitle.alpha = 1f - dismissProgress
    }

    fun resetPhotoDismiss(velocity: Float = 0f) {
        if (host.dismissing) return
        val duration = verticalSettleDuration(host.activeMediaView().translationY, velocity)
        mediaTransform.cancelMediaAnimations()
        mediaTransform.animateMediaDismissTransform(0f, 1f, 1f, duration)
        backgroundScrim.animate().alpha(1f).setDuration(duration).setInterpolator(ViewerVerticalSettle.interpolator).start()
        actions.animate().alpha(1f).setDuration(duration).setInterpolator(ViewerVerticalSettle.interpolator).start()
        mediaTitle.animate().alpha(1f).setDuration(duration).setInterpolator(ViewerVerticalSettle.interpolator).start()
    }

    fun animateDismissToGallery(velocity: Float = 0f) {
        host.beginDismiss()
        val targetY = (root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels) * 0.9f
        val activeMedia = host.activeMediaView()
        val duration = verticalSettleDuration(targetY - activeMedia.translationY, velocity)
        mediaTransform.cancelMediaAnimations()
        activeMedia.animate()
            .translationY(targetY)
            .scaleX(ViewerMediaTransform.DISMISSED_SCALE)
            .scaleY(ViewerMediaTransform.DISMISSED_SCALE)
            .alpha(ViewerMediaTransform.DISMISSED_ALPHA)
            .setDuration(duration)
            .setInterpolator(ViewerVerticalSettle.interpolator)
            .withEndAction {
                activity.finish()
                disableExitTransition()
            }
            .start()
        if (activeMedia !== photoView) {
            mediaTransform.animateDismissedMedia(photoView, targetY, duration)
        }
        if (activeMedia !== playerView) {
            mediaTransform.animateDismissedMedia(playerView, targetY, duration)
        }
        thumbnailPreview.animate()
            .translationY(targetY)
            .scaleX(ViewerMediaTransform.DISMISSED_SCALE)
            .scaleY(ViewerMediaTransform.DISMISSED_SCALE)
            .alpha(ViewerMediaTransform.DISMISSED_ALPHA)
            .setDuration(duration)
            .setInterpolator(ViewerVerticalSettle.interpolator)
            .start()
        loadingPanel.animate()
            .translationY(targetY)
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(ViewerVerticalSettle.interpolator)
            .start()
        if (host.detailsShown) {
            detailsSheet.animate().cancel()
            detailsSheet.animate()
                .translationY(detailsSheet.height.toFloat())
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(ViewerVerticalSettle.interpolator)
                .start()
        }
        backgroundScrim.animate().alpha(0f).setDuration(duration).setInterpolator(ViewerVerticalSettle.interpolator).start()
        actions.animate().alpha(0f).setDuration(duration).setInterpolator(ViewerVerticalSettle.interpolator).start()
        mediaTitle.animate().alpha(0f).setDuration(duration).setInterpolator(ViewerVerticalSettle.interpolator).start()
    }

    private fun verticalSettleDuration(remainingDistance: Float, velocity: Float): Long =
        ViewerVerticalSettle.duration(remainingDistance, velocity, density)

    @Suppress("DEPRECATION")
    private fun disableExitTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            activity.overridePendingTransition(0, 0)
        }
    }
}
