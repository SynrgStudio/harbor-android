package com.synrg.harbor.core

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

/** The connection as the platform reports it. */
interface NetworkMonitor {
    val state: StateFlow<Connection>

    /** Asked right now, so it is right even before [state] first changes. */
    val current: Connection
}
