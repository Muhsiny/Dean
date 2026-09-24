package com.siper.vpn

import com.wgtunnel.backend.Backend
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.model.BackendMode
import com.wgtunnel.parser.Config
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class TurboOptimizer(
    private val backend: Backend,
    private val tunnelId: Int
) {

    data class Selection(
        val provisioned: WarpProvisioner.Provisioned,
        val megabytesPerSecond: Double,
        val handshakeMillis: Long
    )

    suspend fun chooseFastest(
        candidates: List<WarpProvisioner.Provisioned>,
        onProgress: suspend (String) -> Unit = {}
    ): Selection {
        require(candidates.isNotEmpty()) { "No WARP candidates" }

        var best: Selection? = null
        val shortlist = candidates.take(4)

        for ((index, candidate) in shortlist.withIndex()) {
            onProgress(
                "Turbo " + (index + 1) + "/" + shortlist.size +
                    " • " + candidate.label
            )

            stopQuietly()
            delay(250)

            val config = Config.parseQuickString(candidate.profile)
            val tunnel = SiperTunnel()
            val startedAt = System.nanoTime()

            val started = runCatching {
                backend.start(
                    tunnel = tunnel,
                    mode = BackendMode.Vpn(config),
                    tunnelDnsConfig = null
                ).getOrThrow()
            }.isSuccess

            if (!started) {
                stopQuietly()
                continue
            }

            val healthy = withTimeoutOrNull(7_000) {
                backend.status.first { status ->
                    status.activeTunnels[tunnelId]?.transportState is Tunnel.State.Up.Healthy
                }
            } != null

            if (!healthy) {
                stopQuietly()
                continue
            }

            val handshakeMillis =
                (System.nanoTime() - startedAt) / 1_000_000L

            val mbps = runCatching {
                measureDownloadMegabytesPerSecond()
            }.getOrDefault(0.0)

            val selection = Selection(
                provisioned = candidate,
                megabytesPerSecond = mbps,
                handshakeMillis = handshakeMillis
            )

            if (
                best == null ||
                selection.megabytesPerSecond > best!!.megabytesPerSecond ||
                (
                    selection.megabytesPerSecond == best!!.megabytesPerSecond &&
                    selection.handshakeMillis < best!!.handshakeMillis
                )
            ) {
                best = selection
            }

            stopQuietly()
            delay(250)
        }

        val winner = best ?: throw IllegalStateException(
            "هیچ مسیر WARP در این شبکه Handshake سالم نداد"
        )

        onProgress("بهترین مسیر: " + winner.provisioned.label)

        val finalConfig = Config.parseQuickString(winner.provisioned.profile)
        backend.start(
            tunnel = SiperTunnel(),
            mode = BackendMode.Vpn(finalConfig),
            tunnelDnsConfig = null
        ).getOrThrow()

        val finalHealthy = withTimeoutOrNull(8_000) {
            backend.status.first { status ->
                status.activeTunnels[tunnelId]?.transportState is Tunnel.State.Up.Healthy
            }
        } != null

        if (!finalHealthy) {
            stopQuietly()
            throw IllegalStateException("مسیر انتخاب‌شده در اتصال نهایی Handshake نداد")
        }

        return winner
    }

    private suspend fun stopQuietly() {
        runCatching { backend.stop(tunnelId) }
        delay(120)
    }

    private suspend fun measureDownloadMegabytesPerSecond(): Double =
        withContext(Dispatchers.IO) {
            val bytesRequested = 1_048_576
            val url = URL(
                "https://speed.cloudflare.com/__down?bytes=" + bytesRequested +
                    "&cache=" + System.nanoTime()
            )

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 8_000
                useCaches = false
                setRequestProperty("Cache-Control", "no-store")
                setRequestProperty("Accept", "application/octet-stream")
            }

            val startedAt = System.nanoTime()
            var total = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                }
            }
            val elapsedSeconds = max(
                (System.nanoTime() - startedAt) / 1_000_000_000.0,
                0.001
            )
            connection.disconnect()

            (total / (1024.0 * 1024.0)) / elapsedSeconds
        }
}
