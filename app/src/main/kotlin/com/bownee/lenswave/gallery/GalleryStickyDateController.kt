package com.bownee.lenswave.gallery

import android.view.View
import android.widget.AbsListView
import android.widget.TextView

/**
 * Keeps the floating date pill in sync with the first visible row of the gallery list.
 *
 * Scroll callbacks arrive far more often than frames are drawn, so updates are coalesced into a
 * single animation-frame callback that applies only the latest position.
 */
internal class GalleryStickyDateController(
    private val list: GalleryListView,
    private val adapter: GalleryListAdapter,
    private val stickyDate: TextView,
) {
    private var pendingPosition: Int? = null
    private var updatePosted = false
    private val update =
        Runnable {
            updatePosted = false
            val position = pendingPosition ?: return@Runnable
            pendingPosition = null
            render(position)
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
                    schedule(firstVisibleItem)
                }
            },
        )
    }

    fun schedule(firstVisibleItem: Int) {
        pendingPosition = firstVisibleItem
        if (updatePosted) return
        updatePosted = true
        list.postOnAnimation(update)
    }

    fun dispose() {
        list.removeCallbacks(update)
        pendingPosition = null
        updatePosted = false
    }

    private fun render(firstVisibleItem: Int) {
        val label = labelFor(firstVisibleItem, list.headerViewsCount, adapter::dateLabelForPosition)
        if (label == null) {
            stickyDate.visibility = View.GONE
        } else {
            stickyDate.text = label
            stickyDate.visibility = View.VISIBLE
        }
    }

    companion object {
        /**
         * The date label to float for the list row at [firstVisibleItem], or null while a header
         * view (or a position without a date) is at the top.
         */
        fun labelFor(
            firstVisibleItem: Int,
            headerViewsCount: Int,
            dateLabelForPosition: (Int) -> String?,
        ): String? {
            if (firstVisibleItem < headerViewsCount) return null
            return dateLabelForPosition(firstVisibleItem - headerViewsCount)
        }
    }
}
