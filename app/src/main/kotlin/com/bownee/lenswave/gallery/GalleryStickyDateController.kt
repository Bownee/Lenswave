package com.bownee.lenswave.gallery

import android.view.View
import android.widget.AbsListView
import android.widget.TextView

/**
 * Keeps the floating date pill in sync with the first row that is actually visible below the
 * pinned header, not the first row the list considers on screen (which may be hidden under it).
 *
 * Scroll callbacks arrive far more often than frames are drawn, so updates are coalesced into a
 * single animation-frame callback.
 */
internal class GalleryStickyDateController(
    private val list: GalleryListView,
    private val adapter: GalleryListAdapter,
    private val stickyDate: TextView,
    /** Pixels of the pinned header scrolled out of view; the badge follows the rows under what is left of it. */
    private val headerHidden: () -> Int = { 0 },
    private val onScrollStateChanged: (Int) -> Unit = {},
) {
    private var updatePosted = false
    private val update =
        Runnable {
            updatePosted = false
            render()
        }

    /** Follows the list's own scrolling; fast-scroll interaction is reported separately by the screen. */
    fun attach() {
        list.setOnScrollListener(
            object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(
                    view: AbsListView?,
                    scrollState: Int,
                ) = onScrollStateChanged(scrollState)

                override fun onScroll(
                    view: AbsListView?,
                    firstVisibleItem: Int,
                    visibleItemCount: Int,
                    totalItemCount: Int,
                ) {
                    schedule()
                }
            },
        )
    }

    fun schedule() {
        if (updatePosted) return
        updatePosted = true
        list.postOnAnimation(update)
    }

    fun dispose() {
        list.removeCallbacks(update)
        updatePosted = false
    }

    private fun render() {
        val position =
            firstRowBelow(
                firstVisiblePosition = list.firstVisiblePosition,
                childCount = list.childCount,
                headerBottom = list.paddingTop - headerHidden(),
            ) { index -> list.getChildAt(index).bottom }
        val label = position?.let { labelFor(it, list.headerViewsCount, adapter::dateLabelForPosition) }
        if (label == null) {
            stickyDate.visibility = View.GONE
        } else {
            stickyDate.text = label
            stickyDate.visibility = View.VISIBLE
        }
    }

    companion object {
        /**
         * The list position of the first row whose bottom edge lies below [headerBottom], given the
         * bottoms of the currently laid-out children in list coordinates; null when none does.
         */
        fun firstRowBelow(
            firstVisiblePosition: Int,
            childBottoms: List<Int>,
            headerBottom: Int,
        ): Int? = firstRowBelow(firstVisiblePosition, childBottoms.size, headerBottom, childBottoms::get)

        /** As above, reading each child's bottom through [childBottom] so a frame allocates no list. */
        inline fun firstRowBelow(
            firstVisiblePosition: Int,
            childCount: Int,
            headerBottom: Int,
            childBottom: (Int) -> Int,
        ): Int? {
            for (index in 0 until childCount) {
                if (childBottom(index) > headerBottom) return firstVisiblePosition + index
            }
            return null
        }

        /**
         * The date label to float for the list row at [position], or null while a header view
         * (or a position without a date) is at the top.
         */
        fun labelFor(
            position: Int,
            headerViewsCount: Int,
            dateLabelForPosition: (Int) -> String?,
        ): String? {
            if (position < headerViewsCount) return null
            return dateLabelForPosition(position - headerViewsCount)
        }
    }
}
