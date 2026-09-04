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
                ) = Unit

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
        val childBottoms = (0 until list.childCount).map { index -> list.getChildAt(index).bottom }
        val position =
            firstRowBelow(
                firstVisiblePosition = list.firstVisiblePosition,
                childBottoms = childBottoms,
                headerBottom = list.paddingTop,
            )
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
        ): Int? {
            val index = childBottoms.indexOfFirst { bottom -> bottom > headerBottom }
            return if (index < 0) null else firstVisiblePosition + index
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
