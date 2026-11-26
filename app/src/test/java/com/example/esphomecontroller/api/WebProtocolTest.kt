package com.example.esphomecontroller.api

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import okio.Buffer
import java.io.EOFException

class WebProtocolTest {
    @Test fun understandsModernAndLegacyEntityIdentifiers() {
        assertEquals(listOf("sensor", "Room temperature"), parseWebId("sensor/Room temperature"))
        assertEquals(listOf("light", "Garage", "Main light"), parseWebId("light/Garage/Main light"))
        assertEquals(listOf("switch", "relay-one"), parseWebId("switch-relay-one"))
        assertNull(parseWebId("sensor/../outside")); assertNull(parseWebId("sensor/"))
    }
    @Test fun readsStateEventsAndIgnoresLogs() {
        val buffer = Buffer().writeUtf8("event: log\ndata: ignored\n\nevent: state\ndata: {\"id\":\"switch/Relay\",\"state\":\"ON\"}\n\n")
        val events = mutableListOf<String>()
        assertThrows(EOFException::class.java) { consumeWebEvents(buffer, { false }) { events.add(it) } }
        assertEquals(1, events.size); assertEquals(EntityState.OnOff(true), decodeWebEvent(events.single())?.state)
    }
    @Test fun decodesStatesAndAdvertisedBrightness() {
        val light = decodeWebEvent("""{"id":"light/Lamp","state":"ON","brightness":128}""")!!
        assertEquals("Lamp", light.name); assertEquals(Platform.Light(true), light.platform)
        val state = light.state as EntityState.Light
        assertTrue(state.on); assertEquals(128f / 255f, state.brightness!!, 0.0001f)
        val sensor = decodeWebEvent("""{"id":"sensor-temp","state":"21.4 °C"}""")!!
        assertEquals(EntityState.Text("21.4 °C"), sensor.state)
        assertTrue(decodeWebEvent("""{"id":"sensor-hidden","is_disabled_by_default":true}""")!!.hidden)
        assertNull(decodeWebEvent("not json"))
        val climate = decodeWebEvent("""{"id":"climate/Heater","mode":"HEAT"}""")!!
        assertEquals(Platform.Unsupported("Climate"), climate.platform); assertNull(climate.state)
    }
    @Test fun buildsOnlySupportedCommandsAndEscapesEntityNames() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val command = createWebCommand(server.url("/"), "light/Garage/Main lamp", "turn_on", 128)!!
            createWebClient(null, null).newCall(command).execute().close()
            val received = server.takeRequest()
            assertEquals("POST", received.method)
            assertEquals("/light/Garage/Main%20lamp/turn_on?brightness=128", received.path)
            assertNull(createWebCommand(server.url("/"), "sensor/temp", "turn_on", null))
            assertNull(createWebCommand(server.url("/"), "light/lamp", "toggle", null))
        }
    }
    @Test fun selectOptionsAndCommandAreEncodedCorrectly() {
        val event = decodeWebEvent("""{"id":"select/Sleep timer","state":"Disabled","option":["Disabled","15 min"]}""")!!
        assertEquals(Platform.Select(listOf("Disabled", "15 min")), event.platform); assertEquals(EntityState.Text("Disabled"), event.state); assertEquals(listOf("Disabled", "15 min"), event.options)
        assertNull(decodeWebEvent("""{"id":"select/Sleep timer","state":"Disabled"}""")!!.options)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val request = createWebSelectCommand(server.url("/"), "select/Sleep timer", "15 min")!!
            createWebClient(null, null).newCall(request).execute().close()
            val received = server.takeRequest()
            assertEquals("POST", received.method)
            assertEquals("/select/Sleep%20timer/set?option=15%20min", received.path)
            assertNull(createWebSelectCommand(server.url("/"), "light/Lamp", "15 min"))
        }
    }
    @Test fun noCredentialsSentToPublicServer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            createWebClient(null, null).newCall(Request.Builder().url(server.url("/events")).build()).execute().use { assertEquals(200, it.code) }
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun supportsBasicAndDigestChallenges() {
        for (scheme in listOf("Basic realm=\"test\"", "Digest realm=\"test\", nonce=\"abc123\", qop=\"auth\", algorithm=MD5")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", scheme))
                server.enqueue(MockResponse().setBody("ok"))
                createWebClient("test-user", "test-password").newCall(Request.Builder().url(server.url("/events")).build()).execute().use { assertEquals(200, it.code) }
                assertNull(server.takeRequest().getHeader("Authorization"))
                assertTrue(server.takeRequest().getHeader("Authorization")!!.startsWith(scheme.substringBefore(' '), ignoreCase = true))
            }
        }
    }
    @Test fun doesNotFollowRedirectsWithCredentials() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://example.com/"))
            createWebClient("user", "password").newCall(Request.Builder().url(server.url("/events")).build()).execute().use { assertEquals(302, it.code) }
        }
    }

    @Test fun decodesNewWebPlatforms() {
        val number = decodeWebEvent("""{"id":"number/Delay","value":"24.0","state":"24.0 s"}""")!!
        assertTrue(number.needsDetail); assertEquals(EntityState.Numeric(24f), number.state)
        val detailed = decodeWebEvent("""{"id":"number/Delay","value":"24.0","min_value":"0","max_value":"60","step":"0.5","mode":2,"uom":"s"}""")!!
        assertFalse(detailed.needsDetail); assertEquals(Platform.Number(0f, 60f, 0.5f, "s", NumberMode.SLIDER), detailed.platform)
        assertEquals(Platform.Button, decodeWebEvent("""{"id":"button/Restart"}""")!!.platform)
        val cover = decodeWebEvent("""{"id":"cover/Blinds","value":0.3,"state":"OPEN","current_operation":"OPENING","position":0.3,"tilt":1.0}""")!!
        assertEquals(Platform.Cover(true, true, true), cover.platform); assertEquals(EntityState.Positioned(0.3f, 1f, Operation.OPENING), cover.state)
        val valve = decodeWebEvent("""{"id":"valve/Water","value":0.0,"state":"CLOSED","current_operation":"IDLE"}""")!!
        assertEquals(Platform.Valve(false, true), valve.platform); assertEquals(EntityState.Positioned(0f, null, Operation.IDLE), valve.state)
        val fan = decodeWebEvent("""{"id":"fan/Fan","value":true,"state":"ON","speed_level":2,"speed_count":3,"oscillation":false}""")!!
        assertEquals(Platform.Fan(true, false, 3), fan.platform); assertEquals(EntityState.Fan(true, false, false, 2, null), fan.state)
        assertEquals(EntityState.Lock(LockStatus.LOCKED), decodeWebEvent("""{"id":"lock/Door","value":1,"state":"LOCKED"}""")!!.state)
    }

    @Test fun buildsWebRequestsForNewPlatforms() {
        MockWebServer().use { server ->
            val base = server.url("/")
            fun path(entity: Entity, command: Command): String? = createWebRequest(base, entity, command)?.url?.let { it.encodedPath + (it.encodedQuery?.let { q -> "?$q" } ?: "") }
            val number = Entity(1, 0, "Delay", Platform.Number(0f, 60f, 0.5f), webId = "number/Delay")
            assertEquals("/number/Delay/set?value=12.5", path(number, Command.SetNumber(12.5f)))
            assertNull(path(number, Command.SetNumber(61f)))
            assertEquals("/button/Restart/press", path(Entity(2, 0, "Restart", Platform.Button, webId = "button/Restart"), Command.Press))
            val cover = Entity(3, 0, "Blinds", Platform.Cover(true, false, true), webId = "cover/Blinds")
            assertEquals("/cover/Blinds/open", path(cover, Command.Open))
            assertEquals("/cover/Blinds/set?position=0.250", path(cover, Command.SetPosition(0.25f)))
            assertNull(path(cover, Command.SetTilt(0.5f)))
            val fan = Entity(4, 0, "Fan", Platform.Fan(true, false, 3), webId = "fan/Fan", state = EntityState.Fan(false, false, false, 1, null))
            assertEquals("/fan/Fan/turn_on?speed_level=2", path(fan, Command.SetFanSpeed(2)))
            assertEquals("/fan/Fan/turn_off?oscillation=on", path(fan, Command.SetOscillation(true)))
            val lock = Entity(5, 0, "Door", Platform.Lock(false, false), webId = "lock/Door")
            assertEquals("/lock/Door/unlock", path(lock, Command.Lock(LockAction.UNLOCK)))
            assertNull(path(lock, Command.Lock(LockAction.OPEN)))
            assertNull(createWebAction(base, "sensor/Temp", "set"))
        }
    }

    @Test fun logEventsAreDeliveredSeparately() {
        val buffer = Buffer().writeUtf8("event: log\ndata: [I][app]: hello\n\nevent: state\ndata: {\"id\":\"switch/Relay\",\"state\":\"ON\"}\n\n")
        val logs = mutableListOf<String>(); val events = mutableListOf<String>()
        assertThrows(EOFException::class.java) { consumeWebEvents(buffer, { false }, onLog = { logs.add(it) }) { events.add(it) } }
        assertEquals(listOf("[I][app]: hello"), logs); assertEquals(1, events.size)
    }
}
