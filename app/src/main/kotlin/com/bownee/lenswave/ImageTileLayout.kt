package com.bownee.lenswave

internal object ImageTileLayout {
    fun outputSize(imageWidth: Int, imageHeight: Int, orientation: Int, rotationQuarterTurns: Int): Size {
        val orientationSwapsAxes = swapsAxes(orientation)
        val orientedWidth = if (orientationSwapsAxes) imageHeight else imageWidth
        val orientedHeight = if (orientationSwapsAxes) imageWidth else imageHeight
        return if (Math.floorMod(rotationQuarterTurns, 2) == 0) {
            Size(orientedWidth, orientedHeight)
        } else {
            Size(orientedHeight, orientedWidth)
        }
    }

    fun place(
        tileLeft: Int,
        tileTop: Int,
        tileWidth: Int,
        tileHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        orientation: Int,
        rotationQuarterTurns: Int,
    ): Placement {
        val tileRight = tileLeft + tileWidth
        val tileBottom = tileTop + tileHeight
        val oriented = when (normalizeOrientation(orientation)) {
            2 -> Placement(imageWidth - tileRight, tileTop, tileWidth, tileHeight)
            3 -> Placement(imageWidth - tileRight, imageHeight - tileBottom, tileWidth, tileHeight)
            4 -> Placement(tileLeft, imageHeight - tileBottom, tileWidth, tileHeight)
            5 -> Placement(tileTop, tileLeft, tileHeight, tileWidth)
            6 -> Placement(imageHeight - tileBottom, tileLeft, tileHeight, tileWidth)
            7 -> Placement(imageHeight - tileBottom, imageWidth - tileRight, tileHeight, tileWidth)
            8 -> Placement(tileTop, imageWidth - tileRight, tileHeight, tileWidth)
            else -> Placement(tileLeft, tileTop, tileWidth, tileHeight)
        }
        val orientedImageWidth = if (swapsAxes(orientation)) imageHeight else imageWidth
        val orientedImageHeight = if (swapsAxes(orientation)) imageWidth else imageHeight
        return rotate(oriented, orientedImageWidth, orientedImageHeight, rotationQuarterTurns)
    }

    private fun rotate(
        placement: Placement,
        imageWidth: Int,
        imageHeight: Int,
        rotationQuarterTurns: Int,
    ): Placement = with(placement) {
        when (Math.floorMod(rotationQuarterTurns, 4)) {
            1 -> Placement(imageHeight - top - height, left, height, width)
            2 -> Placement(imageWidth - left - width, imageHeight - top - height, width, height)
            3 -> Placement(top, imageWidth - left - width, height, width)
            else -> this
        }
    }

    private fun swapsAxes(orientation: Int): Boolean = normalizeOrientation(orientation) in 5..8

    private fun normalizeOrientation(orientation: Int): Int = orientation.takeIf { it in 1..8 } ?: 1

    internal data class Size(val width: Int, val height: Int)

    internal data class Placement(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )
}
