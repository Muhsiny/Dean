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
        val endpoint: String
    )

    fun createProfile(): Provisioned {
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

        val endpoint = when {
            endpointObj.optString("host").isNotBlank() ->
                endpointObj.getString("host")
            endpointObj.optString("v4").isNotBlank() -> {
                val raw = endpointObj.getString("v4")
                if (raw.contains(":") && raw.substringAfterLast(":").toIntOrNull() != null) {
                    raw
                } else {
                    val ports = endpointObj.optJSONArray("ports")
                    val port = if (ports != null && ports.length() > 0) ports.getInt(0) else 2408
                    raw + ":" + port
                }
            }
            else -> throw IllegalStateException("WARP endpoint missing")
        }

        val profile = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = " + privateKey.toBase64())
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

        Config.parseQuickString(profile)
        return Provisioned(profile = profile, endpoint = endpoint)
    }
}
