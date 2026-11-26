package com.example.esphomecontroller

/** What a device's mDNS TXT records say about Native API encryption. */
enum class EncryptionAdvert {
    /** `api_encryption`: a key is configured; the device only accepts encrypted connections. */
    REQUIRED,
    /** `api_encryption_supported`: encryption is compiled in but no key is set; plaintext works. */
    AVAILABLE,
    /** No encryption record: plaintext only. */
    NONE,
    /** No TXT data was available, so nothing can be assumed. */
    UNKNOWN,
}

/** A resolved `_esphomelib._tcp` service and its advertised metadata. TXT data is a hint, never a security decision. */
data class DiscoveredDevice(
    val serviceName: String,
    val address: String,
    val friendlyName: String? = null,
    val version: String? = null,
    val platform: String? = null,
    val board: String? = null,
    val project: String? = null,
    val encryption: EncryptionAdvert = EncryptionAdvert.UNKNOWN,
) {
    val displayName: String get() = friendlyName ?: serviceName
    /** e.g. "ESP32 · esp32dev · ESPHome 2026.11.0". */
    val summary: String get() = listOfNotNull(platform, board?.takeIf { it != platform?.lowercase() }, version?.let { "ESPHome $it" }).joinToString(" · ")
}

/** Parses ESPHome's TXT records (see esphome/components/mdns/mdns_component.cpp). */
fun parseEsphomeTxt(serviceName: String, address: String, attributes: Map<String, ByteArray?>): DiscoveredDevice {
    val txt = attributes.mapKeys { it.key.lowercase() }
    fun value(key: String) = txt[key]?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
    val encryption = when {
        txt.containsKey("api_encryption") -> EncryptionAdvert.REQUIRED
        txt.containsKey("api_encryption_supported") -> EncryptionAdvert.AVAILABLE
        // ESPHome always publishes "version"; without it the TXT data is missing rather than describing a plaintext device.
        txt.containsKey("version") -> EncryptionAdvert.NONE
        else -> EncryptionAdvert.UNKNOWN
    }
    val project = listOfNotNull(value("project_name"), value("project_version")).joinToString(" ").ifBlank { null }
    return DiscoveredDevice(serviceName, address, value("friendly_name"), value("version"), value("platform"), value("board"), project, encryption)
}

/** The connection type to pre-select for a newly discovered device, or null when the user has to choose. */
fun recommendedMode(device: DiscoveredDevice?): String? = when (device?.encryption) {
    EncryptionAdvert.REQUIRED -> "encrypted"
    EncryptionAdvert.AVAILABLE, EncryptionAdvert.NONE -> "plain"
    else -> null
}
