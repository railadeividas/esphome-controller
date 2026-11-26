package com.example.esphomecontroller

import org.junit.Assert.*
import org.junit.Test

class HomeStoreMigrationTest {
    private fun d(id: String, home: String, name: String = id) = HomeStore.Device(id, name, "192.0.2.1:6053", "plain", homeId = home)

    @Test fun mergesCopiesIntoOneDevicePreferringTheSelectedHome() {
        val legacy = listOf(d("lamp", "a", "Lamp A"), d("stairs", "a"), d("lamp", "b", "Lamp B"), d("garage", "b"))
        val merged = HomeStore.migrate(legacy, selectedHome = "b")
        assertEquals(listOf("lamp", "stairs", "garage"), merged.map { it.id })
        val lamp = merged.first { it.id == "lamp" }
        assertEquals("b", lamp.homeId); assertEquals("Lamp B", lamp.name)
        assertEquals("a", merged.first { it.id == "stairs" }.homeId)
    }

    @Test fun fallsBackToTheFirstHomeThatHadTheDevice() {
        val merged = HomeStore.migrate(listOf(d("lamp", "a", "Lamp A"), d("lamp", "b", "Lamp B")), selectedHome = "c")
        assertEquals(HomeStore.Device("lamp", "Lamp A", "192.0.2.1:6053", "plain", homeId = "a"), merged.single())
        assertTrue(HomeStore.migrate(emptyList(), null).isEmpty())
    }
}
