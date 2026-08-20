package org.sayeh.wificontrol.core

import org.apache.commons.net.telnet.TelnetClient
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.SocketException
import java.util.Locale


data class CliDevice(
    val mac: String,
    val ip: String? = null,
    val signalDbm: Int? = null,
    val rateMbps: Int? = null,
    val online: Boolean = true
)

data class CliSnapshot(
    val devices: List<CliDevice>,
    val policy: Int,
    val acl: Set<String>,
    val rawWireless: String,
    val rawArp: String,
    val rawNode: String
)

data class CliActionResult(
    val success: Boolean,
    val message: String,
    val snapshot: CliSnapshot? = null
)

class TelnetRouter(
    private val host: String,
    private val password: String,
    private val port: Int = 23,
    private val nodeIndex: Int = 1
) {
    companion object {
        private val MAC_RE = Regex("(?i)([0-9a-f]{2}(?::[0-9a-f]{2}){5})")
        private val IPV4_RE = Regex("(\\d{1,3}(?:\\.\\d{1,3}){3})")
        private val RSSI_RE = Regex("(-?\\d+)/(-?\\d+)/(-?\\d+)")
        private val POLICY_RE = Regex("(?i)WLAN\\s+policy\\([^)]*\\)\\s*:\\s*([0-2])")

        fun normalizeMac(value: String): String = value.trim().lowercase(Locale.US)
    }

    fun probe(): CliSnapshot = withSession { session ->
        session.command("rtwlan node index $nodeIndex")
        val wireless = session.command("rtwlan showmactable", timeoutMs = 7000)
        val arp = session.command("ip arp status", timeoutMs = 7000)
        val node = session.command("rt node display", timeoutMs = 7000)
        parseSnapshot(wireless, arp, node)
    }

    fun setBlocked(mac: String, blocked: Boolean): CliActionResult {
        val target = normalizeMac(mac)
        return try {
            val before = probe()
            if (before.policy == 1) {
                return CliActionResult(
                    false,
                    "حالت Allow‑List فعال است. اول حالت ضد اشتراک را خاموش کنید.",
                    before
                )
            }

            val desired = when {
                blocked && before.policy == 2 -> before.acl.toMutableSet().apply { add(target) }
                blocked -> linkedSetOf(target)
                !blocked && before.policy == 2 -> before.acl.toMutableSet().apply { remove(target) }
                else -> before.acl.toMutableSet()
            }

            val desiredPolicy = if (desired.isEmpty()) 0 else 2
            applyPolicy(desiredPolicy, desired)
        } catch (t: Throwable) {
            CliActionResult(false, cleanError(t))
        }
    }

    fun enableAllowOnly(allowedMacs: Set<String>, managerMac: String): CliActionResult {
        val manager = normalizeMac(managerMac)
        val allowed = allowedMacs.map(::normalizeMac).toMutableSet().apply { add(manager) }
        if (allowed.isEmpty()) return CliActionResult(false, "هیچ دستگاه مجازی انتخاب نشده است.")
        return try {
            applyPolicy(1, allowed)
        } catch (t: Throwable) {
            CliActionResult(false, cleanError(t))
        }
    }

    fun disableMacFilter(): CliActionResult {
        return try {
            applyPolicy(0, emptySet())
        } catch (t: Throwable) {
            CliActionResult(false, cleanError(t))
        }
    }

    private fun applyPolicy(policy: Int, acl: Set<String>): CliActionResult {
        require(policy in 0..2)
        val normalized = acl.map(::normalizeMac).filter { MAC_RE.matches(it) }.toCollection(linkedSetOf())

        withSession { session ->
            session.command("rtwlan node index $nodeIndex")
            if (policy != 0 && normalized.isNotEmpty()) {
                // This command was verified on TD-W8961N V4. The firmware accepts a comma-separated ACL.
                val joined = normalized.joinToString(",")
                session.command("rt node acladdentry $joined")
            }
            session.command("rt node accesspolicy $policy")
            session.command("rt node display")
            session.command("rt node save", timeoutMs = 6000, allowConnectionClose = true)
        }

        Thread.sleep(1800)
        val after = probeWithRetry()
        val ok = when (policy) {
            0 -> after.policy == 0
            1 -> after.policy == 1 && normalized.all { it in after.acl }
            2 -> after.policy == 2 && normalized.all { it in after.acl }
            else -> false
        }

        return if (ok) {
            CliActionResult(
                true,
                when (policy) {
                    0 -> "فیلتر MAC خاموش شد و اتصال دوباره آزاد است."
                    1 -> "Allow‑List واقعی فعال شد. فقط دستگاه‌های مجاز می‌توانند وصل شوند."
                    else -> "تغییر Block/Unblock روی خود روتر اعمال و Verify شد."
                },
                after
            )
        } else {
            CliActionResult(
                false,
                "روتر فرمان را پذیرفت، اما Verify نهایی مطابق انتظار نبود. تنظیم دیگری اعمال نشد.",
                after
            )
        }
    }

    private fun probeWithRetry(): CliSnapshot {
        var last: Throwable? = null
        repeat(4) { attempt ->
            try {
                return probe()
            } catch (t: Throwable) {
                last = t
                Thread.sleep(900L + attempt * 500L)
            }
        }
        throw last ?: IllegalStateException("Verify failed")
    }

    private fun parseSnapshot(wireless: String, arp: String, node: String): CliSnapshot {
        val ipByMac = linkedMapOf<String, String>()
        arp.lineSequence().forEach { line ->
            val mac = MAC_RE.find(line)?.groupValues?.get(1)?.let(::normalizeMac)
            val ip = IPV4_RE.find(line)?.groupValues?.get(1)
            if (mac != null && ip != null && mac != "ff:ff:ff:ff:ff:ff") ipByMac[mac] = ip
        }

        val online = linkedMapOf<String, CliDevice>()
        wireless.lineSequence().forEach { line ->
            val macMatch = MAC_RE.find(line) ?: return@forEach
            val mac = normalizeMac(macMatch.groupValues[1])
            if (mac == "ff:ff:ff:ff:ff:ff") return@forEach
            if (!line.trimStart().startsWith(macMatch.value, ignoreCase = true)) return@forEach

            val rssi = RSSI_RE.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val tokens = line.trim().split(Regex("\\s+"))
            val rate = tokens.asReversed().drop(1).firstOrNull { it.toIntOrNull() != null }?.toIntOrNull()
            online[mac] = CliDevice(mac = mac, ip = ipByMac[mac], signalDbm = rssi, rateMbps = rate, online = true)
        }

        val policy = POLICY_RE.find(node)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val acl = parseAcl(node)

        // Keep blocked/allowed offline entries visible so the user can unblock them later.
        if (policy != 0) {
            acl.forEach { mac ->
                if (mac !in online) {
                    online[mac] = CliDevice(mac = mac, ip = ipByMac[mac], online = false)
                }
            }
        }

        return CliSnapshot(
            devices = online.values.toList(),
            policy = policy,
            acl = acl,
            rawWireless = wireless,
            rawArp = arp,
            rawNode = node
        )
    }

    private fun parseAcl(node: String): Set<String> {
        val lines = node.lines()
        val start = lines.indexOfFirst { it.contains("WLAN AccessControlList", ignoreCase = true) }
        if (start < 0) return emptySet()
        val out = linkedSetOf<String>()
        for (i in start + 1 until lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("WLAN ", ignoreCase = true)) break
            val mac = MAC_RE.find(line)?.groupValues?.get(1)?.let(::normalizeMac)
            if (mac != null && mac != "ff:ff:ff:ff:ff:ff") out.add(mac)
        }
        return out
    }

    private fun <T> withSession(block: (CliSession) -> T): T {
        val session = CliSession(host, port, password)
        try {
            session.open()
            return block(session)
        } finally {
            session.close()
        }
    }

    private fun cleanError(t: Throwable): String {
        val raw = t.message?.trim().orEmpty()
        return when {
            raw.contains("refused", true) -> "اتصال Telnet رد شد. مطمئن شوید به Wi‑Fi همین روتر وصل هستید."
            raw.contains("password", true) || raw.contains("login", true) -> "رمز مدیریت روتر پذیرفته نشد."
            raw.contains("timeout", true) -> "روتر در زمان تعیین‌شده پاسخ نداد."
            raw.isNotEmpty() -> raw
            else -> "ارتباط CLI با روتر ناموفق بود."
        }
    }

    private class CliSession(
        private val host: String,
        private val port: Int,
        private val password: String
    ) {
        private val telnet = TelnetClient()
        private lateinit var input: BufferedInputStream
        private lateinit var output: BufferedOutputStream

        fun open() {
            telnet.setConnectTimeout(5000)
            telnet.setDefaultTimeout(5000)
            telnet.connect(host, port)
            try { telnet.setSoTimeout(1000) } catch (_: SocketException) {}
            input = BufferedInputStream(telnet.inputStream)
            output = BufferedOutputStream(telnet.outputStream)

            val hello = readUntil(setOf("Password:", "TP-LINK>"), 6000)
            if (hello.contains("Password:", ignoreCase = true)) {
                sendLine(password)
                val login = readUntil(setOf("TP-LINK>", "Password:"), 6000)
                if (!login.contains("TP-LINK>", ignoreCase = true)) {
                    throw IllegalStateException("Password/login rejected")
                }
            } else if (!hello.contains("TP-LINK>", ignoreCase = true)) {
                throw IllegalStateException("Login timeout")
            }
        }

        fun command(command: String, timeoutMs: Long = 5000, allowConnectionClose: Boolean = false): String {
            sendLine(command)
            return try {
                readUntil(setOf("TP-LINK>"), timeoutMs, allowConnectionClose)
            } catch (t: Throwable) {
                if (allowConnectionClose) "" else throw t
            }
        }

        private fun sendLine(text: String) {
            output.write((text + "\r\n").toByteArray(Charsets.US_ASCII))
            output.flush()
        }

        private fun readUntil(
            needles: Set<String>,
            timeoutMs: Long,
            allowConnectionClose: Boolean = false
        ): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            val sb = StringBuilder()
            var lastData = System.currentTimeMillis()
            while (System.currentTimeMillis() < deadline) {
                val available = try { input.available() } catch (_: Throwable) { 0 }
                if (available > 0) {
                    val buffer = ByteArray(minOf(available, 4096))
                    val n = try { input.read(buffer) } catch (t: Throwable) {
                        if (allowConnectionClose) return sb.toString()
                        throw t
                    }
                    if (n < 0) {
                        if (allowConnectionClose) return sb.toString()
                        break
                    }
                    if (n > 0) {
                        sb.append(String(buffer, 0, n, Charsets.ISO_8859_1))
                        lastData = System.currentTimeMillis()
                        val text = sb.toString()
                        if (needles.any { text.contains(it, ignoreCase = true) }) return text
                    }
                } else {
                    if (allowConnectionClose && System.currentTimeMillis() - lastData > 900) return sb.toString()
                    Thread.sleep(35)
                }
            }
            if (allowConnectionClose) return sb.toString()
            throw IllegalStateException("CLI timeout waiting for ${needles.joinToString()}")
        }

        fun close() {
            try { telnet.disconnect() } catch (_: Throwable) {}
        }
    }
}
