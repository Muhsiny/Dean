package com.siper.vpn

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PsiphonFallbackConfigTest {
    @Test
    fun configContainsVerifiedBootstrapAndAdaptiveSettings() {
        val root = File(System.getProperty("java.io.tmpdir"), "siper-psiphon-test")
        val json = JSONObject(PsiphonFallbackConfig.render(root, "7"))

        assertEquals("FFFFFFFFFFFFFFFF", json.getString("PropagationChannelId"))
        assertEquals("1111111111111111", json.getString("SponsorId"))
        assertEquals(12, json.getInt("ConnectionWorkerPoolSize"))
        assertEquals(300, json.getInt("EstablishTunnelTimeoutSeconds"))
        assertEquals(0, json.getInt("LocalSocksProxyPort"))
        assertTrue(json.getBoolean("DisableLocalHTTPProxy"))
        assertTrue(json.getString("RemoteServerListUrl").startsWith("https://"))
        assertTrue(json.getString("ServerEntrySignaturePublicKey").isNotBlank())
        assertTrue(json.getString("RemoteServerListSignaturePublicKey").length > 500)
        assertFalse(json.has("DisableTactics"))
    }
}
