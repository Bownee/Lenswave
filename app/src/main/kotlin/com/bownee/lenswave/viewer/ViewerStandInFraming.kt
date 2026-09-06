package com.bownee.lenswave.viewer

import android.graphics.Matrix
import android.widget.ImageView
import com.bownee.lenswave.gallery.MediaKind

/**
 * Frames a thumbnail stand-in the way the media it stands in for will be framed, so the handover
 * to the real picture moves nothing. A photo is fitted whole, as [FullResolutionPhotoView] fits
 * it. A video follows [ViewerVideoFramingPolicy], which the player applies to the clip once its
 * size is known; the thumbnail has the clip's shape, so the same call answers the same way, and
 * a clip that will span the width gets a stand-in that already does. Left fitted, the stand-in
 * sat between two bands of background until the player's wider first frame popped in over it,
 * which read as a flicker every time a video opened.
 */
internal class ViewerStandInFraming(
    private val view: ImageView,
) {
    private var mediaKind = MediaKind.IMAGE

    init {
        // A spanning bitmap overhangs the box top and bottom by as much as the clip will be
        // cropped. The media frame does not clip its children (a zoomed photo draws past the
        // box), so the view clips its own drawable: left to the frame, the overhang was drawn
        // over the bands above and below the box until the stand-in was cleared, a flash of
        // picture there on every video open.
        view.cropToPadding = true
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) apply()
        }
    }

    /** Call once the stand-in's bitmap is set, with the kind of media it stands in for. */
    fun standsInFor(kind: MediaKind) {
        mediaKind = kind
        apply()
    }

    private fun apply() {
        val drawable = view.drawable
        val boxWidth = view.width - view.paddingLeft - view.paddingRight
        val boxHeight = view.height - view.paddingTop - view.paddingBottom
        val framing =
            if (mediaKind == MediaKind.VIDEO && drawable != null) {
                ViewerVideoFramingPolicy.framing(drawable.intrinsicWidth, drawable.intrinsicHeight, boxWidth, boxHeight)
            } else {
                ViewerVideoFramingPolicy.Framing.FIT
            }
        if (framing == ViewerVideoFramingPolicy.Framing.FIT || drawable == null) {
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            return
        }
        val placement =
            ViewerVideoFramingPolicy.fillWidthPlacement(
                drawable.intrinsicWidth,
                drawable.intrinsicHeight,
                boxWidth,
                boxHeight,
            )
        view.scaleType = ImageView.ScaleType.MATRIX
        view.imageMatrix =
            Matrix().apply {
                setScale(placement.scale, placement.scale)
                postTranslate(0f, placement.offsetY)
            }
    }
}
