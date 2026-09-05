package com.bownee.lenswave.gallery

import com.bownee.lenswave.gallery.GalleryDialogPromptPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryDialogPromptPolicyTest {
    @Test
    fun `a dialog shows only while the state is not saved and none is up`() {
        assertEquals(Decision.SHOW, GalleryDialogPromptPolicy.decide(stateSaved = false, dialogShowing = false))
        assertEquals(Decision.WAIT, GalleryDialogPromptPolicy.decide(stateSaved = true, dialogShowing = false))
        assertEquals(Decision.DROP, GalleryDialogPromptPolicy.decide(stateSaved = false, dialogShowing = true))
        assertEquals(Decision.DROP, GalleryDialogPromptPolicy.decide(stateSaved = true, dialogShowing = true))
    }
}
