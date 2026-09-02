package com.bownee.lenswave

internal data class EditorState(
    val adjustments: PhotoAdjustments,
    val activeLook: Int,
) {
    fun withAdjustments(adjustments: PhotoAdjustments): EditorState = copy(adjustments = adjustments)

    companion object {
        const val NO_LOOK = -1

        val INITIAL = EditorState(PhotoAdjustments.NEUTRAL, NO_LOOK)
    }
}
