package com.siper.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.wgtunnel.backend.system.NetworkMonitor
import com.wgtunnel.backend.system.NetworkSnapshot
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class UnderlayNetworkMonitor(context: Context) : NetworkMonitor {

    data class LinkStatus(
        val networkKey: String = "",
        val label: String = "در جستجوی مسیر…",
        val validated: Boolean = false,
        val metered: Boolean = true,
        val downstreamKbps: Int = 0,
        val upstreamKbps: Int = 0,
        val rttMs: Long? = null,
        val score: Int = Int.MIN_VALUE,
        val availableLinks: Int = 0
    )

    private data class Candidate(
        val network: Network,
        val snapshot: NetworkSnapshot,
        val capabilities: NetworkCapabilities,
        val label: String,
        val updatedAt: Long,
        val rttMs: Long? = null
    )

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val candidates = ConcurrentHashMap<Network, Candidate>()
    private val _networkState = MutableStateFlow<NetworkSnapshot?>(null)
    private val _linkStatus = MutableStateFlow(LinkStatus())

    override val networkState: StateFlow<NetworkSnapshot?> = _networkState
    val linkStatus: StateFlow<LinkStatus> = _linkStatus

    private fun newCallback() = object : ConnectivityManager.NetworkCallback() {
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
            candidates.remove(network)
            selectBest()
        }
    }

    private val genericCallback = newCallback()
    private val wifiCallback = newCallback()
    private val cellularCallback = newCallback()

    init {
        val generic = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        cm.registerNetworkCallback(generic, genericCallback)

        // Keep Wi-Fi and cellular visible at the same time when Android permits it.
        // This does not create bandwidth; it lets the VPN move to the healthier underlay quickly.
        runCatching {
            val wifi = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.requestNetwork(wifi, wifiCallback)
        }

        runCatching {
            val cellular = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.requestNetwork(cellular, cellularCallback)
        }

        cm.activeNetwork?.let { refresh(it) }

        scope.launch {
            while (isActive) {
                probeAll()
                delay(6_000)
            }
        }
    }

    private fun refresh(network: Network) {
        val caps = cm.getNetworkCapabilities(network) ?: return
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return

        val lp = cm.getLinkProperties(network)
        val hasIpv6 = lp?.linkAddresses
            ?.map(LinkAddress::getAddress)
            ?.any { address ->
                address is Inet6Address &&
                    !address.isLinkLocalAddress &&
                    !address.isLoopbackAddress
            } == true

        val label = transportLabel(caps)
        val snapshot = NetworkSnapshot(
            key = buildString {
                append(network.toString())
                append(':')
                append(lp?.interfaceName ?: label)
            },
            hasIpv6 = hasIpv6,
            isUsable = true,
            network = network
        )

        val old = candidates[network]
        candidates[network] = Candidate(
            network = network,
            snapshot = snapshot,
            capabilities = caps,
            label = label,
            updatedAt = System.currentTimeMillis(),
            rttMs = old?.rttMs
        )
        selectBest()
    }

    private suspend fun probeAll() {
        val copy = candidates.values.toList()
        copy.forEach { candidate ->
            val rtt = probe(candidate.network)
            val current = candidates[candidate.network] ?: return@forEach
            candidates[candidate.network] = current.copy(rttMs = rtt)
        }
        selectBest()
    }

    private suspend fun probe(network: Network): Long? = withContext(Dispatchers.IO) {
        val targets = listOf(
            InetSocketAddress("1.1.1.1", 443),
            InetSocketAddress("8.8.8.8", 443),
            InetSocketAddress("9.9.9.9", 443)
        )

        var best: Long? = null
        for (target in targets) {
            val started = System.nanoTime()
            val ok = runCatching {
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(target, 1_500)
                }
            }.isSuccess
            if (ok) {
                val elapsed = (System.nanoTime() - started) / 1_000_000
                best = if (best == null) elapsed else kotlin.math.min(best!!, elapsed)
            }
        }
        best
    }

    private fun selectBest() {
        val now = System.currentTimeMillis()
        val fresh = candidates.values
            .filter { now - it.updatedAt < 30_000 }
        if (fresh.isEmpty()) {
            _networkState.value = null
            _linkStatus.value = LinkStatus()
            return
        }

        val best = fresh.maxByOrNull { score(it) } ?: return
        val caps = best.capabilities
        _networkState.value = best.snapshot
        _linkStatus.value = LinkStatus(
            networkKey = best.snapshot.key,
            label = best.label,
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            downstreamKbps = caps.linkDownstreamBandwidthKbps,
            upstreamKbps = caps.linkUpstreamBandwidthKbps,
            rttMs = best.rttMs,
            score = score(best),
            availableLinks = fresh.size
        )
    }

    private fun score(candidate: Candidate): Int {
        val caps = candidate.capabilities
        var value = 0

        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) value += 100_000
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) value -= 200_000
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) value += 4_000
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)) value += 2_500

        value += caps.linkDownstreamBandwidthKbps.coerceAtMost(1_000_000) / 20
        value += caps.linkUpstreamBandwidthKbps.coerceAtMost(500_000) / 40

        value += when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 5_000
            Build.VERSION.SDK_INT >= 35 &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) -> 4_500
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3_500
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 3_000
            else -> 1_000
        }

        candidate.rttMs?.let { rtt ->
            value += max(-8_000, 8_000 - (rtt.toInt() * 20))
        }

        return value
    }

    private fun transportLabel(caps: NetworkCapabilities): String {
        return when {
            Build.VERSION.SDK_INT >= 35 &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) -> "Satellite uplink"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth tether"
            else -> "Internet link"
        }
    }
}
