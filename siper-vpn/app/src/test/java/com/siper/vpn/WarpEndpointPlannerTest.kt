package com.siper.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class WarpEndpointPlannerTest {

    @Test
    fun advertisedPreferredPortsComeFirst() {
        assertEquals(
            listOf(443, 2408, 4500, 12345),
            WarpEndpointPlanner.preferredPorts(
                listOf(12345, 4500, 2408, 443),
                6
            )
        )
    }

    @Test
    fun default2408ExistsWhenApiHasNoPorts() {
        assertEquals(
            listOf(2408),
            WarpEndpointPlanner.preferredPorts(emptyList(), 4)
        )
    }

    @Test
    fun ipv4PortIsStripped() {
        assertEquals(
            "162.159.192.1",
            WarpEndpointPlanner.stripPort("162.159.192.1:2408")
        )
    }

    @Test
    fun bareIpv6IsNotCorrupted() {
        assertEquals(
            "2606:4700:d0::a29f:c001",
            WarpEndpointPlanner.stripPort("2606:4700:d0::a29f:c001")
        )
    }

    @Test
    fun endpointFormatsIpv4AndIpv6() {
        assertEquals(
            "162.159.192.1:443",
            WarpEndpointPlanner.endpoint("162.159.192.1", 443)
        )
        assertEquals(
            "[2606:4700:d0::a29f:c001]:443",
            WarpEndpointPlanner.endpoint("2606:4700:d0::a29f:c001", 443)
        )
    }
}
