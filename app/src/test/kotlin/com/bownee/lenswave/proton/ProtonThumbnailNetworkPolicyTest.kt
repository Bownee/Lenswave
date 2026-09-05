package com.bownee.lenswave.proton

import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonThumbnailNetworkPolicyTest {
    private val validatedUnmetered =
        setOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
        )

    @Test
    fun `internet, validated and not metered together are enough`() {
        assertTrue(ProtonThumbnailNetworkPolicy.isValidatedUnmetered(validatedUnmetered::contains))
        assertEquals(validatedUnmetered, ProtonThumbnailNetworkPolicy.REQUIRED_CAPABILITIES.toSet())
    }

    @Test
    fun `a network missing any one of them does not count`() {
        validatedUnmetered.forEach { missing ->
            val capabilities = validatedUnmetered - missing
            assertFalse("without $missing", ProtonThumbnailNetworkPolicy.isValidatedUnmetered(capabilities::contains))
        }
    }

    @Test
    fun `a metered network with validated internet is not enough`() {
        val metered = setOf(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        assertFalse(ProtonThumbnailNetworkPolicy.isValidatedUnmetered(metered::contains))
    }

    @Test
    fun `no capabilities at all is no network`() {
        assertFalse(ProtonThumbnailNetworkPolicy.isValidatedUnmetered { false })
    }
}
