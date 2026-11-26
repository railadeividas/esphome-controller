package com.example.esphomecontroller

data class Endpoint(val host: String, val port: Int)

fun parseEndpoint(host: String, port: String): Endpoint? {
    val normalizedHost = host.trim()
    val number = port.toIntOrNull()
    if (normalizedHost.isEmpty() || normalizedHost.any { it.isWhitespace() } || number == null || number !in 1..65535) return null
    return Endpoint(normalizedHost, number)
}

/** Splits "host:port", "[v6]:port" or a bare host (default Native API port). */
fun splitAddress(address: String): Pair<String, String> {
    val index = address.lastIndexOf(':')
    return if (index > 0) address.substring(0, index).removePrefix("[").removeSuffix("]") to address.substring(index + 1) else address to "6053"
}

fun joinAddress(host: String, port: Int): String = if (':' in host) "[$host]:$port" else "$host:$port"
