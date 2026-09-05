package com.bownee.lenswave.viewer

/**
 * How a video is framed in the media box. Fitting the whole clip (the player's default) fills
 * the width only when the clip is wider than the box and pillarboxes anything taller, which left
 * a phone clip floating between two bands of background while a photo of the same shape ran edge
 * to edge. Spanning the width instead crops a taller clip top and bottom by the box: a sliver
 * for a phone clip in a phone-shaped box, most of the picture for a portrait clip in a landscape
 * one. The share cropped decides.
 */
internal object ViewerVideoFramingPolicy {
    enum class Framing {
        /** The clip spans the box's width; anything taller than the box is cropped by it. */
        FILL_WIDTH,

        /** The whole clip is shown inside the box, with bands of background where it is narrower. */
        FIT,
    }

    /** The most of a clip's height the box may cut off for the clip to span its width. */
    const val MAX_CROPPED_FRACTION = 0.2f

    fun framing(
        videoWidth: Int,
        videoHeight: Int,
        boxWidth: Int,
        boxHeight: Int,
    ): Framing {
        if (videoWidth <= 0 || videoHeight <= 0 || boxWidth <= 0 || boxHeight <= 0) return Framing.FIT
        val cropped = croppedFraction(videoWidth, videoHeight, boxWidth, boxHeight)
        return if (cropped <= MAX_CROPPED_FRACTION) Framing.FILL_WIDTH else Framing.FIT
    }

    /** Where a picture spanning the box's width sits: its scale, and its top edge relative to the box's. */
    data class Placement(
        val scale: Float,
        val offsetY: Float,
    )

    /**
     * The placement of a picture that spans the box's width and is centred on the box's height,
     * which is where the player puts a clip it frames as [Framing.FILL_WIDTH]; a stand-in placed
     * the same way is covered by the clip's first frame without a jump.
     */
    fun fillWidthPlacement(
        imageWidth: Int,
        imageHeight: Int,
        boxWidth: Int,
        boxHeight: Int,
    ): Placement {
        val scale = boxWidth.toFloat() / imageWidth
        return Placement(scale = scale, offsetY = (boxHeight - imageHeight * scale) / 2f)
    }

    /**
     * The share of the clip's height outside the box once the clip spans the box's width; zero
     * for a clip that is then no taller than the box.
     */
    fun croppedFraction(
        videoWidth: Int,
        videoHeight: Int,
        boxWidth: Int,
        boxHeight: Int,
    ): Float {
        val spannedHeight = boxWidth.toFloat() * videoHeight / videoWidth
        if (spannedHeight <= boxHeight) return 0f
        return 1f - boxHeight / spannedHeight
    }
}
