package com.example.esphomecontroller

import android.util.AtomicFile
import com.example.esphomecontroller.api.DeviceDetails
import com.example.esphomecontroller.api.Entity
import com.example.esphomecontroller.api.EntityCodec
import org.json.JSONObject
import java.io.File

/**
 * Saved controls (entity metadata) and device details per "homeId/deviceId", so offline devices still
 * show their controls after the app restarts. States are never written. Stored in no-backup storage.
 */
class ControlCache(file: File) {
    data class Entry(val entities: List<Entity>, val details: DeviceDetails?)
    private val atomic = AtomicFile(file)

    fun load(): Map<String, Entry> = try {
        val root = JSONObject(String(atomic.readFully(), Charsets.UTF_8))
        root.keys().asSequence().associateWith { key ->
            val o = root.getJSONObject(key)
            Entry(EntityCodec.decodeEntities(o.optJSONArray("entities")), EntityCodec.decodeDetails(o.optJSONObject("details")))
        }
    } catch (_: Exception) { emptyMap() }

    fun save(entries: Map<String, Entry>) {
        val root = JSONObject()
        entries.forEach { (key, e) ->
            root.put(key, JSONObject().put("entities", EntityCodec.encodeEntities(e.entities)).apply { e.details?.let { put("details", EntityCodec.encodeDetails(it)) } })
        }
        val out = atomic.startWrite()
        try { out.write(root.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
    }
}
