package com.siper.vpn

object WarpEndpointPlanner {

    private val primaryFallback =
        listOf(443, 2408, 500, 4500, 1701)

    private val secondaryFallback =
        listOf(4443, 8443)

    fun preferredPorts(discovered: List<Int>, maxCandidates: Int): List<Int> {
        require(maxCandidates > 0)
        val sane = discovered
            .filter { it in 1..65535 }
            .distinct()

        // Keep a resilient fallback set even when the API advertises only one port.
        // Unknown advertised ports are still retained after the well-known candidates.
        val custom = sane.filterNot {
            primaryFallback.contains(it) || secondaryFallback.contains(it)
        }
        return (primaryFallback + custom + secondaryFallback)
            .distinct()
            .take(maxCandidates)
    }

    fun stripPort(raw: String): String {
        val value = raw.trim()
        if (value.startsWith("[") && value.contains("]")) {
            return value.substringAfter("[").substringBefore("]")
        }

        val colonCount = value.count { it == ':' }
        if (colonCount == 1) {
            val lastColon = value.lastIndexOf(':')
            if (
                lastColon > 0 &&
                value.substring(lastColon + 1).toIntOrNull() in 1..65535
            ) {
                return value.substring(0, lastColon)
            }
        }

        return value
    }

    fun endpoint(host: String, port: Int): String {
        require(port in 1..65535)
        val cleaned = stripPort(host)
        require(cleaned.isNotBlank())
        return if (cleaned.contains(':')) {
            "[$cleaned]:$port"
        } else {
            "$cleaned:$port"
        }
    }
}
