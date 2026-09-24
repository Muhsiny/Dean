package com.siper.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.wgtunnel.backend.system.NetworkMonitor
import com.wgtunnel.backend.system.NetworkSnapshot
import java.net.Inet6Address
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class UnderlayNetworkMonitor(context: Context) : NetworkMonitor {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val snapshots = ConcurrentHashMap<Network, NetworkSnapshot>()
    private val _networkState = MutableStateFlow<NetworkSnapshot?>(null)

    override val networkState: StateFlow<NetworkSnapshot?> = _networkState

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh(network)

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) = refresh(network)

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: android.net.LinkProperties
        ) = refresh(network)

        override fun onLost(network: Network) {
            snapshots.remove(network)
            _networkState.value = snapshots.values.firstOrNull()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)

        cm.activeNetwork?.let { network ->
            val caps = cm.getNetworkCapabilities(network)
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true) {
                refresh(network)
            }
        }
    }

    private fun refresh(network: Network) {
        val caps = cm.getNetworkCapabilities(network) ?: return
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return

        val lp = cm.getLinkProperties(network)
        val hasIpv6 = lp?.linkAddresses
            ?.map(LinkAddress::getAddress)
            ?.any { address ->
                address is Inet6Address &&
                    !address.isLinkLocalAddress &&
                    !address.isLoopbackAddress
            } == true

        val snapshot = NetworkSnapshot(
            key = buildString {
                append(network.toString())
                append(':')
                append(lp?.interfaceName ?: "underlay")
            },
            hasIpv6 = hasIpv6,
            isUsable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            network = network
        )
        snapshots[network] = snapshot
        _networkState.value = snapshot
    }
}
