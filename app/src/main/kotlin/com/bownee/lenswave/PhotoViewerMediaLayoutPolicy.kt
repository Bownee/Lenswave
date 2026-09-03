package com.bownee.lenswave

internal object PhotoViewerMediaLayoutPolicy {
    fun mediaHeight(viewportHeight: Int, mediaTop: Int): Int =
        (viewportHeight - mediaTop.coerceAtLeast(0)).coerceAtLeast(0)

    /**
     * The margin applied to both the top and the bottom of the media so it stays centred on the
     * viewport while clearing whichever overlay is taller: the title strip or the action bar.
     */
    fun verticalInset(viewportHeight: Int, titleBottom: Int, actionsTop: Int, gap: Int): Int {
        if (viewportHeight <= 0) return 0
        val actionsHeight = if (actionsTop in 1 until viewportHeight) viewportHeight - actionsTop else 0
        val overlay = maxOf(titleBottom.coerceAtLeast(0), actionsHeight)
        if (overlay <= 0) return 0
        return (overlay + gap).coerceAtMost(viewportHeight / 2)
    }
}
