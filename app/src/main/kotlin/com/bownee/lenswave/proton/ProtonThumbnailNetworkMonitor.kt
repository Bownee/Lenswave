package com.bownee.lenswave.proton

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal class ProtonThumbnailNetworkMonitor(context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private fun hasValidatedUnmeteredNetwork(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.isValidatedUnmetered()
    }

    suspend fun awaitValidatedUnmeteredNetwork(timeoutMillis: Long): Boolean {
        if (hasValidatedUnmeteredNetwork()) return true

        return withTimeoutOrNull(timeoutMillis) {
            callbackFlow {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(hasValidatedUnmeteredNetwork())
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        trySend(networkCapabilities.isValidatedUnmetered())
                    }

                    override fun onLost(network: Network) {
                        trySend(hasValidatedUnmeteredNetwork())
                    }
                }
                connectivityManager.registerDefaultNetworkCallback(callback)
                trySend(hasValidatedUnmeteredNetwork())
                awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
            }.first { available -> available }
            true
        } ?: false
    }

    private fun NetworkCapabilities.isValidatedUnmetered(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
