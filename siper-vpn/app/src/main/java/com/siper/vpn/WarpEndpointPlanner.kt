package com.siper.vpn

object WarpEndpointPlanner {

    fun preferredPorts(discovered: List<Int>, maxCandidates: Int): List<Int> {
        require(maxCandidates > 0)
        val sane = discovered
            .filter { it in 1..65535 }
            .distinct()

        val preference = listOf(443, 2408, 4500, 500, 1701, 4443, 8443)
        val ordered = preference.filter { sane.contains(it) } +
            sane.filterNot { preference.contains(it) }

        return (ordered + listOf(2408))
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

        // Bare IPv6 has multiple colons and should not have its tail mistaken for a port.
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
