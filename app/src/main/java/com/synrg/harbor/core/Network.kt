package com.synrg.harbor.core

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the current connection costs: playing and seeding behave differently on mobile data. */
enum class Connection {
    /** Wi-Fi or Ethernet without a data cap. */
    Unmetered,
    Cellular,
    /** A metered network that is not cellular (a phone's hotspot, a capped Wi-Fi). */
    Metered,
    Offline;

    val metered: Boolean get() = this == Cellular || this == Metered
}

class NetworkMonitor(app: Application) {
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow(read(cm.getNetworkCapabilities(cm.activeNetwork)))
    val state: StateFlow<Connection> = _state

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _state.value = read(caps)
            }

            override fun onLost(network: Network) {
                _state.value = read(cm.getNetworkCapabilities(cm.activeNetwork))
            }
        })
    }

    /** Asks Android directly, so it is right even before the first callback arrives. */
    val current: Connection get() = read(cm.getNetworkCapabilities(cm.activeNetwork))

    private fun read(caps: NetworkCapabilities?): Connection = when {
        caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> Connection.Offline
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            (Build.VERSION.SDK_INT >= 30 && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)) -> Connection.Unmetered
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Connection.Cellular
        else -> Connection.Metered
    }
}
