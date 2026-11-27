package com.example.esphomecontroller.api

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/** ESPHome log levels, most severe first; [protoValue] is api.proto's `LogLevel`, [code] the letter ESPHome prints. */
enum class LogLevel(val code: String, val protoValue: Int, val label: String) {
    ERROR("E", 1, "Error"), WARN("W", 2, "Warning"), INFO("I", 3, "Info"), CONFIG("C", 4, "Config"),
    DEBUG("D", 5, "Debug"), VERBOSE("V", 6, "Verbose"), VERY_VERBOSE("VV", 7, "Very verbose");

    companion object {
        fun ofCode(code: String) = entries.firstOrNull { it.code == code }
        fun ofProto(value: Int) = entries.firstOrNull { it.protoValue == value }
    }
}

/**
 * One line in a device's log view. [seq] increases across all sessions, so it identifies a line and orders lines. [time] is when the phone
 * received it (ESPHome lines carry no wall-clock time). [level] and [tag] are null when the text has no ESPHome prefix. State lines are
 * entity updates added by the app; their [tag] is the entity name and [message] its formatted value.
 */
data class LogLine(val seq: Long, val time: Long, val level: LogLevel?, val tag: String?, val message: String, val isState: Boolean = false) {
    /** Plain text for copying or sharing: `15:42:07 [W][wifi]: message`. */
    fun export(zone: ZoneId = ZoneId.systemDefault()): String {
        val t = exportTime.withZone(zone).format(Instant.ofEpochMilli(time))
        return when {
            isState -> "$t [state] $tag: $message"
            level != null -> "$t [${level.code}]" + (tag?.let { "[$it]" } ?: "") + ": $message"
            else -> "$t $message"
        }
    }
}

private val exportTime = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val nextSeq = AtomicLong()
fun nextLogSeq() = nextSeq.incrementAndGet()

// `[I][wifi:123]: text` (the line number is optional; some builds print `[I][wifi]: text`).
private val esphomePrefix = Regex("""^\[(VV|[EWICDV])]\[([^\]:]+)(?::\d+)?]:?\s?""")

/** Parses an ESPHome log line; [levelHint] (the Native API's level field) is used when the text has no prefix. */
fun parseLogLine(text: String, time: Long = System.currentTimeMillis(), levelHint: Int? = null, seq: Long = nextLogSeq()): LogLine {
    val match = esphomePrefix.find(text)
    if (match != null) return LogLine(seq, time, LogLevel.ofCode(match.groupValues[1]), match.groupValues[2], text.substring(match.range.last + 1))
    return LogLine(seq, time, levelHint?.let { LogLevel.ofProto(it) }, null, text)
}

/** A log-view line for a state update, like the web UI's live state stream; devices do not log these at DEBUG. */
fun stateLogLine(entity: Entity, time: Long = System.currentTimeMillis(), seq: Long = nextLogSeq()) =
    LogLine(seq, time, null, entity.name, displayValue(entity) ?: "unknown", isState = true)

private val mac = Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b""")
private val ipv4 = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b""")
// Full eight-group addresses, or compressed ones with `::` (so times such as 12:34:56 are left alone).
private val ipv6 = Regex("""\b(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}\b|\b(?:[0-9A-Fa-f]{1,4}:){1,7}:(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})?""")

/** Replaces MAC, IPv4 and IPv6 addresses before logs are shared. */
fun redactAddresses(text: String): String =
    text.replace(mac, "<mac>").replace(ipv6, "<ipv6>").replace(ipv4, "<ip>")
