package com.bownee.lenswave.proton

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which networks the worker may download on: one with internet the system has validated (a
 * Wi-Fi behind a captive portal or without uplink satisfies WorkManager's unmetered constraint
 * but downloads nothing) and that is not metered.
 */
internal object ProtonThumbnailNetworkPolicy {
    val REQUIRED_CAPABILITIES =
        listOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
        )

    /** [hasCapability] answers for one `NET_CAPABILITY_*` constant, like `NetworkCapabilities.hasCapability`. */
    fun isValidatedUnmetered(hasCapability: (Int) -> Boolean): Boolean = REQUIRED_CAPABILITIES.all(hasCapability)
}

/**
 * What [ProtonThumbnailNetworkMonitor] needs from the platform: the answer right now, and a
 * word whenever the default network changes. [observe] is called once, before the first read,
 * and the reports stop at [close].
 */
internal interface ProtonThumbnailNetworkSource : AutoCloseable {
    fun isValidatedUnmetered(): Boolean

    fun observe(onChange: (validatedUnmetered: Boolean) -> Unit)
}

/**
 * Tracks whether a validated, unmetered network is available. One system callback is registered
 * for the monitor's lifetime, so checking between batches costs nothing; call [close] when done.
 */
internal class ProtonThumbnailNetworkMonitor(
    private val source: ProtonThumbnailNetworkSource,
) : AutoCloseable {
    constructor(context: Context) : this(ConnectivityManagerNetworkSource(context))

    private val available = MutableStateFlow(false)

    init {
        source.observe { validatedUnmetered -> available.value = validatedUnmetered }
        // Read after the callback is in place, so a change between the two is not missed.
        available.value = source.isValidatedUnmetered()
    }

    suspend fun awaitValidatedUnmeteredNetwork(timeoutMillis: Long): Boolean {
        if (available.value) return true
        return withTimeoutOrNull(timeoutMillis) { available.first { it } } != null
    }

    override fun close() {
        source.close()
    }
}

/** The platform's answers, from the default network callback and the active network's capabilities. */
private class ConnectivityManagerNetworkSource(
    context: Context,
) : ProtonThumbnailNetworkSource {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun isValidatedUnmetered(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return ProtonThumbnailNetworkPolicy.isValidatedUnmetered(capabilities::hasCapability)
    }

    override fun observe(onChange: (validatedUnmetered: Boolean) -> Unit) {
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onChange(isValidatedUnmetered())
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    onChange(ProtonThumbnailNetworkPolicy.isValidatedUnmetered(networkCapabilities::hasCapability))
                }

                override fun onLost(network: Network) {
                    onChange(isValidatedUnmetered())
                }
            }
        this.callback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    override fun close() {
        callback?.let { registered -> runCatching { connectivityManager.unregisterNetworkCallback(registered) } }
        callback = null
    }
}
