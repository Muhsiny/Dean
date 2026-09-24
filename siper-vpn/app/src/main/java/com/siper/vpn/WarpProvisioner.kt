package com.siper.vpn

import android.os.Build
import com.wgtunnel.parser.Config
import com.wgtunnel.parser.crypto.Key
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONObject

class WarpProvisioner {

    data class Provisioned(
        val profile: String,
        val endpoint: String,
        val label: String
    )

    fun createProfiles(maxCandidates: Int = 6): List<Provisioned> {
        val privateKey = Key.generatePrivateKey()
        val publicKey = Key.generatePublicKey(privateKey)

        val requestBody = JSONObject().apply {
            put("fcm_token", "")
            put("install_id", "")
            put("key", publicKey.toBase64())
            put("locale", "en_US")
            put("model", Build.MODEL ?: "Android")
            put("tos", Instant.now().toString())
            put("serial_number", "")
            put("os_version", "16.0.0")
            put("key_type", "curve25519")
            put("tunnel_type", "wireguard")
        }.toString()

        val connection = (URL("https://api.cloudflareclient.com/v0a5641/reg")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "1.1.1.1/6.38.9-5641 (Android 16.0.0)")
            setRequestProperty("CF-Client-Version", "a-6.38.9-5641")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "Keep-Alive")
        }

        connection.outputStream.use { out ->
            out.write(requestBody.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            .use { it.readText() }

        if (code !in 200..299) {
            throw IllegalStateException("WARP registration failed: HTTP " + code)
        }

        val root = JSONObject(response)
        val config = root.optJSONObject("config")
            ?: throw IllegalStateException("WARP config missing")
        val iface = config.getJSONObject("interface").getJSONObject("addresses")
        val address4 = iface.getString("v4")
        val address6 = iface.getString("v6")
        val peers = config.getJSONArray("peers")
        if (peers.length() == 0) throw IllegalStateException("WARP peer missing")

        val peer = peers.getJSONObject(0)
        val peerPublicKey = peer.getString("public_key")
        val endpointObj = peer.getJSONObject("endpoint")

        val portsJson = endpointObj.optJSONArray("ports")
        val discoveredPorts = buildList {
            if (portsJson != null) {
                for (i in 0 until portsJson.length()) {
                    add(portsJson.getInt(i))
                }
            }
        }

        val ports = WarpEndpointPlanner.preferredPorts(
            discovered = discoveredPorts,
            maxCandidates = maxCandidates
        )

        val endpointHosts = buildList {
            endpointObj.optString("v4").takeIf { it.isNotBlank() }?.let { raw ->
                add(WarpEndpointPlanner.stripPort(raw))
            }
            endpointObj.optString("host").takeIf { it.isNotBlank() }?.let { raw ->
                add(WarpEndpointPlanner.stripPort(raw))
            }
        }.distinct()

        if (endpointHosts.isEmpty()) {
            throw IllegalStateException("WARP endpoint missing")
        }

        val candidates = mutableListOf<Provisioned>()
        endpointHosts.forEach { host ->
            ports.forEach { port ->
                val endpoint = WarpEndpointPlanner.endpoint(host, port)
                val profile = buildProfile(
                    privateKey = privateKey.toBase64(),
                    address4 = address4,
                    address6 = address6,
                    peerPublicKey = peerPublicKey,
                    endpoint = endpoint
                )
                Config.parseQuickString(profile)
                candidates += Provisioned(
                    profile = profile,
                    endpoint = endpoint,
                    label = "WARP UDP " + port
                )
            }
        }

        return candidates.distinctBy { it.endpoint }.take(maxCandidates)
    }

    fun createProfile(): Provisioned = createProfiles(1).first()

    private fun buildProfile(
        privateKey: String,
        address4: String,
        address6: String,
        peerPublicKey: String,
        endpoint: String
    ): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = " + privateKey)
        appendLine("Address = " + address4 + "/32, " + address6 + "/128")
        appendLine("DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001")
        appendLine("MTU = 1280")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = " + peerPublicKey)
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        appendLine("Endpoint = " + endpoint)
        appendLine("PersistentKeepalive = 25")
    }

}

