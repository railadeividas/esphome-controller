package com.example.esphomecontroller

import org.junit.Assert.*
import org.junit.Test

class DiscoveryTest {
    private fun txt(vararg pairs: Pair<String, String?>) = pairs.associate { (k, v) -> k to v?.toByteArray() }

    @Test fun parsesEsphomeTxtRecords() {
        val d = parseEsphomeTxt("living-room", "192.0.2.5:6053", txt(
            "friendly_name" to "Living room", "version" to "2026.11.0", "platform" to "ESP32", "board" to "esp32dev",
            "api_encryption" to "Noise_NNpsk0_25519_ChaChaPoly_SHA256", "project_name" to "acme.lamp", "project_version" to "1.0"))
        assertEquals("Living room", d.displayName); assertEquals(EncryptionAdvert.REQUIRED, d.encryption)
        assertEquals("ESP32 · esp32dev · ESPHome 2026.11.0", d.summary); assertEquals("acme.lamp 1.0", d.project)
        assertEquals("encrypted", recommendedMode(d))
    }

    @Test fun encryptionAdvertisementAndMissingTxt() {
        val supported = parseEsphomeTxt("a", "x:1", txt("version" to "2026.1.0", "api_encryption_supported" to "Noise_NNpsk0_25519_ChaChaPoly_SHA256"))
        assertEquals(EncryptionAdvert.AVAILABLE, supported.encryption); assertEquals("plain", recommendedMode(supported))
        val plain = parseEsphomeTxt("b", "x:1", txt("version" to "2026.1.0", "friendly_name" to ""))
        assertEquals(EncryptionAdvert.NONE, plain.encryption); assertEquals("b", plain.displayName)
        // No TXT data at all says nothing about encryption, so the user must choose.
        val unknown = parseEsphomeTxt("c", "x:1", emptyMap())
        assertEquals(EncryptionAdvert.UNKNOWN, unknown.encryption); assertNull(recommendedMode(unknown)); assertNull(recommendedMode(null))
        assertEquals(EncryptionAdvert.REQUIRED, parseEsphomeTxt("d", "x:1", txt("API_ENCRYPTION" to null)).encryption)
    }
}
