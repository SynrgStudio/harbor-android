package com.synrg.harbor.core

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidNetworkMonitor(app: Application) : NetworkMonitor {
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow(read(cm.getNetworkCapabilities(cm.activeNetwork)))
    override val state: StateFlow<Connection> = _state

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
    override val current: Connection get() = read(cm.getNetworkCapabilities(cm.activeNetwork))

    private fun read(caps: NetworkCapabilities?): Connection = when {
        caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> Connection.Offline
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            (Build.VERSION.SDK_INT >= 30 && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)) -> Connection.Unmetered
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Connection.Cellular
        else -> Connection.Metered
    }
}
