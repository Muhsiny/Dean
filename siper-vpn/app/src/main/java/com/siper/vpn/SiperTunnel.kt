package com.siper.vpn

import com.wgtunnel.backend.Tunnel

class SiperTunnel : Tunnel {
    override val id: Int = 1
    override val name: String = "Siper VPN"
    override val isMetered: Boolean = false
    override val scriptsEnabled: Boolean = false

    override val ipStrategy: Tunnel.IpStrategy =
        Tunnel.IpStrategy.PreferIpv6(recoveryEnabled = true)

    override val features: Set<Tunnel.Feature> = setOf(
        Tunnel.Feature.ActiveConfigMonitor(intervalSeconds = 3),
        Tunnel.Feature.Recovery(
            seamlessRecovery = true,
            dynamicDnsRecovery = true,
            bounceDelaySeconds = 10
        )
    )

    @Volatile
    var lastState: Tunnel.State = Tunnel.State.Down
        private set

    override fun updateState(state: Tunnel.State) {
        lastState = state
    }
}
