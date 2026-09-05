package com.bownee.lenswave.proton

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tracks whether a validated, unmetered network is available. One system callback is registered
 * for the monitor's lifetime, so checking between batches costs nothing; call [close] when done.
 */
internal class ProtonThumbnailNetworkMonitor(
    context: Context,
) : AutoCloseable {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val available = MutableStateFlow(hasValidatedUnmeteredNetwork())
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available.value = hasValidatedUnmeteredNetwork()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                available.value = networkCapabilities.isValidatedUnmetered()
            }

            override fun onLost(network: Network) {
                available.value = hasValidatedUnmeteredNetwork()
            }
        }

    init {
        connectivityManager.registerDefaultNetworkCallback(callback)
        available.value = hasValidatedUnmeteredNetwork()
    }

    suspend fun awaitValidatedUnmeteredNetwork(timeoutMillis: Long): Boolean {
        if (available.value) return true
        return withTimeoutOrNull(timeoutMillis) { available.first { it } } != null
    }

    override fun close() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun hasValidatedUnmeteredNetwork(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.isValidatedUnmetered()
    }

    private fun NetworkCapabilities.isValidatedUnmetered(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
