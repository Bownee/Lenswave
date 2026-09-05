package com.bownee.lenswave.gallery

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * The gallery's root. The list, the refresh spinner and the date badge sit below the pinned
 * header, whose height is only known once it is measured. Applying that height from a layout
 * listener meant three requestLayout calls in the middle of the root's layout pass, and the
 * framework ran a second full layout on every launch. Here it is applied between the two
 * halves of a measure pass instead: [onHeaderMeasured] receives the header's measured height
 * and answers whether anything below it changed, in which case the children are measured again
 * against their new padding, and the layout that follows is already right.
 *
 * Built in code only, like every view here; the layout editor never inflates it.
 */
@SuppressLint("ViewConstructor")
internal class GalleryRootLayout(
    context: Context,
    private val header: () -> View,
    private val onHeaderMeasured: (Int) -> Boolean,
) : FrameLayout(context) {
    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (onHeaderMeasured(header().measuredHeight)) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
