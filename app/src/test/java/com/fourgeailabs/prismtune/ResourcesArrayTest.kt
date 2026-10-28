package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.PlexClient
import com.fourgeailabs.prismtune.data.PlexServer
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.9: /api/v2/resources with Accept: application/json returns
 * a BARE JSON ARRAY of device objects, not a MediaContainer
 * object. 1.0.8 parsed every body as a JSONObject, so a valid
 * token's server list threw "Value [...] cannot be converted
 * to JSONObject" four times and reported "No servers were
 * returned." No live server, no real token or password, and
 * every fixture value below is invented for the test.
 */
class ResourcesArrayTest {

    // Invented fixture only: mirrors the shape Plex returned
    // (name/product/platform/device/clientIdentifier/provides/
    // owned as a boolean/Connection array), with invented values.
    private val bareArrayJson = """
        [
          {"name":"Test Media Server","product":"Plex Media Server",
           "productVersion":"1.43.5-test","platform":"Windows",
           "platformVersion":"10.0 (Build 26200)","device":"TestBox",
           "clientIdentifier":"test-server-001","provides":"server",
           "owned":true,"accessToken":"test-server-token",
           "Connection":[{"uri":"http://192.168.1.9:32400","local":true,"protocol":"http"},
                         {"uri":"https://example-remote.test:32400","local":false,"protocol":"https"}]},
          {"name":"Test Player","product":"Plex for Test",
           "clientIdentifier":"test-player-001","provides":"client",
           "owned":true,"accessToken":"player-token",
           "Connection":[{"uri":"http://192.168.1.10:32400","local":true,"protocol":"http"}]}
        ]
    """.trimIndent()

    private val legacyJson = """
        {"MediaContainer":{"Device":[{
            "provides":"server","clientIdentifier":"legacy-server-001","name":"LegacyServer",
            "accessToken":"legacy-server-token","owned":1,
            "Connection":[{"uri":"http://192.168.1.2:32400","local":1,"protocol":"http"}]
        }]}}
    """.trimIndent()

    private fun cannedClient(body: String): PlexClient {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        return PlexClient(http = client)
    }

    @Test
    fun bareArrayResourcesParsesServerAndFiltersNonServer() = runBlocking {
        val servers = cannedClient(bareArrayJson).resources("account-token", "test-device")
        assertEquals(1, servers.size)
        val server: PlexServer = servers[0]
        assertEquals("test-server-001", server.id)
        assertEquals("Test Media Server", server.name)
        assertEquals("Plex Media Server", server.product)
        assertEquals("test-server-token", server.accessToken)
        assertTrue(server.owned)
        assertEquals(2, server.connections.size)
        assertEquals("http://192.168.1.9:32400", server.connections[0].uri)
        assertTrue(server.connections[0].local)
        assertEquals("https", server.connections[1].protocol)
        assertFalse(server.connections[1].local)
    }

    @Test
    fun legacyMediaContainerResourcesStillParses() = runBlocking {
        val servers = cannedClient(legacyJson).resources("account-token", "test-device")
        assertEquals(1, servers.size)
        assertEquals("legacy-server-001", servers[0].id)
        assertEquals("LegacyServer", servers[0].name)
        assertEquals("legacy-server-token", servers[0].accessToken)
    }

    @Test
    fun parseResourcesBodyAcceptsBothShapesDirectly() {
        val client = cannedClient(bareArrayJson)
        val out = mutableListOf<PlexServer>()
        client.parseResourcesBody(bareArrayJson, out)
        assertEquals(1, out.size)
        assertEquals("test-server-001", out[0].id)
        out.clear()
        client.parseResourcesBody(legacyJson, out)
        assertEquals(1, out.size)
        assertEquals("legacy-server-001", out[0].id)
    }
}
