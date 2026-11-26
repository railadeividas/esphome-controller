package com.example.esphomecontroller

data class Endpoint(val host: String, val port: Int)

fun parseEndpoint(host: String, port: String): Endpoint? {
    val normalizedHost = host.trim()
    val number = port.toIntOrNull()
    if (normalizedHost.isEmpty() || normalizedHost.any { it.isWhitespace() } || number == null || number !in 1..65535) return null
    return Endpoint(normalizedHost, number)
}
