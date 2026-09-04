package com.bownee.lenswave.gallery

import com.bownee.lenswave.gallery.GalleryUpdatePromptPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryUpdatePromptPolicyTest {
    @Test
    fun `nothing pending means nothing to do`() {
        assertEquals(
            Decision.NOTHING,
            GalleryUpdatePromptPolicy.decide(null, stateSaved = false, dialogShowing = false),
        )
    }

    @Test
    fun `a pending version waits only while the fragment state is saved`() {
        assertEquals(
            Decision.WAIT,
            GalleryUpdatePromptPolicy.decide("0.20.0", stateSaved = true, dialogShowing = false),
        )
        assertEquals(
            Decision.SHOW,
            GalleryUpdatePromptPolicy.decide("0.20.0", stateSaved = false, dialogShowing = false),
        )
    }

    @Test
    fun `a dialog the fragment manager restored is not shown twice`() {
        assertEquals(
            Decision.ALREADY_SHOWING,
            GalleryUpdatePromptPolicy.decide("0.20.0", stateSaved = false, dialogShowing = true),
        )
    }
}
