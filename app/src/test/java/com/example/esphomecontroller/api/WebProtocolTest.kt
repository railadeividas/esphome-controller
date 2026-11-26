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
        val cover = decodeWebEvent("""{"id":"cover/Garage door","state":"OPEN"}""")!!
        assertEquals(Platform.Unsupported("Cover"), cover.platform); assertNull(cover.state)
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
}
