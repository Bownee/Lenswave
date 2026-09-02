package com.bownee.lenswave.gallery

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

internal interface GalleryText {
    fun string(@StringRes id: Int, vararg arguments: Any): String
    fun quantity(@PluralsRes id: Int, quantity: Int, vararg arguments: Any): String
}

internal class AndroidGalleryText(private val resources: Resources) : GalleryText {
    override fun string(id: Int, vararg arguments: Any): String = resources.getString(id, *arguments)

    override fun quantity(id: Int, quantity: Int, vararg arguments: Any): String =
        resources.getQuantityString(id, quantity, *arguments)
}
