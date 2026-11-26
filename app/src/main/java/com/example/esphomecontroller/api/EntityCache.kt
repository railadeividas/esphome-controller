package com.example.esphomecontroller.api

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel

/** In-memory only; retained over activity recreation but never written to storage. */
class DashboardCache : ViewModel() {
    val devices = mutableStateMapOf<String, List<DeviceSession.Entity>>()
    fun remember(device: String, entities: List<DeviceSession.Entity>) {
        if (entities.isNotEmpty()) devices[device] = mergeCachedEntities(devices[device].orEmpty(), entities)
    }
    fun remove(device: String) { devices.remove(device) }
}

fun sameEntity(a: DeviceSession.Entity, b: DeviceSession.Entity): Boolean =
    if (a.webId != null || b.webId != null) a.webId != null && a.webId == b.webId
    else a.key == b.key && a.deviceId == b.deviceId && a.kind == b.kind

/** Metadata arriving during reconnect must not erase the last reported state. */
fun mergeCachedEntities(cached: List<DeviceSession.Entity>, fresh: List<DeviceSession.Entity>): List<DeviceSession.Entity> {
    val merged = cached.toMutableList()
    for (entity in fresh) {
        val index = merged.indexOfFirst { sameEntity(it, entity) }
        if (index < 0) merged.add(entity)
        else {
            val previous = merged[index]
            merged[index] = if (entity.receivedState) entity else entity.copy(
                state = previous.state, value = previous.value, brightness = previous.brightness,
                receivedState = previous.receivedState
            )
        }
    }
    return merged
}

/** Cached values are for display; commands require state from the current connection. */
fun isControlReady(connected: Boolean, live: DeviceSession.Entity?): Boolean =
    connected && live?.receivedState == true && live.state != null

fun isSelectReady(connected: Boolean, live: DeviceSession.Entity?): Boolean =
    connected && live?.receivedState == true && live.value != null && live.options.isNotEmpty()
