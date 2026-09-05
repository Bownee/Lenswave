package com.bownee.lenswave.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryDialogPromptPolicyTest {
    @Test
    fun `a dialog shows only while the state is not saved and none is up`() {
        assertTrue(GalleryDialogPromptPolicy.canShow(stateSaved = false, dialogShowing = false))
        assertFalse(GalleryDialogPromptPolicy.canShow(stateSaved = true, dialogShowing = false))
        assertFalse(GalleryDialogPromptPolicy.canShow(stateSaved = false, dialogShowing = true))
        assertFalse(GalleryDialogPromptPolicy.canShow(stateSaved = true, dialogShowing = true))
    }
}
