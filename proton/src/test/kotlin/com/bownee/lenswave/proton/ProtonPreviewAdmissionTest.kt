package com.bownee.lenswave.proton

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonPreviewAdmissionTest {
    @Test
    fun `previews are allowed unless a run says otherwise`() {
        val admission = ProtonPreviewAdmission()
        assertTrue(admission.previewsAllowed())

        var allowed = true
        admission.bind { allowed }
        assertTrue(admission.previewsAllowed())
        allowed = false
        assertFalse(admission.previewsAllowed())

        admission.unbind()
        assertTrue(admission.previewsAllowed())
    }
}
