package com.bownee.lenswave

internal object PhotoViewerMediaLayoutPolicy {
    fun mediaHeight(viewportHeight: Int, mediaTop: Int): Int =
        (viewportHeight - mediaTop.coerceAtLeast(0)).coerceAtLeast(0)

    fun bottomInset(viewportHeight: Int, actionsTop: Int, gap: Int): Int {
        if (viewportHeight <= 0 || actionsTop !in 1 until viewportHeight) return 0
        return (viewportHeight - actionsTop + gap).coerceAtMost(viewportHeight)
    }
}
