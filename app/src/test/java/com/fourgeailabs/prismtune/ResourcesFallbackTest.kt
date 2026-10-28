package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.PlexClient
import com.fourgeailabs.prismtune.data.PlexException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 1.0.7 server-list fallbacks. No live Plex server: the test
 * PlexClient is built with an OkHttp interceptor that answers
 * each resources/sections request with a canned status/body, so
 * the attempt order and the combined-failure message are proven
 * without network. No token ever appears in any asserted message
 * beyond the dummy test tokens used as request headers.
 */
class ResourcesFallbackTest {

    private val resourcesJson = """
        {"MediaContainer":{"Device":[{
            "provides":"server","clientIdentifier":"srv1","name":"MyServer",
            "accessToken":"server-token","owned":1,
            "Connection":[{"uri":"http://192.168.1.2:32400","local":1,"protocol":"http"}]
        }]}}
    """.trimIndent()

    private val sectionsJson = """
        {"MediaContainer":{"Directory":[
            {"key":"1","title":"Music","type":"artist"}
        ]}}
    """.trimIndent()

    private fun cannedClient(handler: (String, String?) -> Pair<Int, String>): PlexClient {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val (code, body) = handler(request.url.toString(), request.header("X-Plex-Token"))
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Unauthorized")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        return PlexClient(http = client)
    }

    @Test
    fun resourcesFallsBackToClientsOnPlexTv401() = runBlocking {
        val requested = mutableListOf<String>()
        val client = cannedClient { url, _ ->
            requested.add(url)
            when {
                url.startsWith("https://plex.tv/api/v2/resources") ->
                    401 to """{"errors":[{"code":1001,"message":"User could not be authenticated","status":401}]}"""
                url.startsWith("https://clients.plex.tv/api/v2/resources") ->
                    200 to resourcesJson
                else -> 404 to "{}"
            }
        }
        val servers = client.resources("account-token", "test-device")
        assertEquals(1, servers.size)
        assertEquals("srv1", servers[0].id)
        assertEquals("MyServer", servers[0].name)
        // First attempt was plex.tv with query, second was the fallback host.
        assertTrue(requested[0].startsWith("https://plex.tv/api/v2/resources?"))
        assertTrue(requested[1].startsWith("https://clients.plex.tv/api/v2/resources?"))
    }

    @Test
    fun resourcesFallsThroughToNoQueryAttempts() = runBlocking {
        val requested = mutableListOf<String>()
        val client = cannedClient { url, _ ->
            requested.add(url)
            when {
                url.contains("?includeHttps") -> 401 to """{"errors":[{"status":401}]}"""
                url == "https://plex.tv/api/v2/resources" -> 200 to resourcesJson
                else -> 404 to "{}"
            }
        }
        val servers = client.resources("account-token", "test-device")
        assertEquals(1, servers.size)
        assertEquals(3, requested.size)
        assertEquals("https://plex.tv/api/v2/resources", requested[2])
    }

    @Test
    fun allResourcesAttemptsFailWithBothHostsAndStatuses() = runBlocking {
        val client = cannedClient { url, _ ->
            val host = if (url.contains("clients.plex.tv")) "clients.plex.tv" else "plex.tv"
            401 to """{"errors":[{"message":"rejected on $host","status":401}]}"""
        }
        try {
            client.resources("account-token", "test-device")
            fail("resources() should throw when every attempt fails")
        } catch (e: PlexException) {
            assertEquals(401, e.statusCode)
            val msg = e.message.orEmpty()
            assertTrue(msg.startsWith("Server list failed:"))
            assertTrue(msg.contains("plex.tv"))
            assertTrue(msg.contains("clients.plex.tv"))
            assertTrue(msg.contains("401"))
            assertTrue(msg.contains("No servers were returned."))
            // The dummy test token must never leak into the message.
            assertTrue(!msg.contains("account-token"))
        }
    }

    @Test
    fun combineResourcesFailuresTruncatesBodiesTo200Chars() {
        val longBody = "x".repeat(500)
        val first = PlexException("GET https://plex.tv/api/v2/resources failed: 401 $longBody", 401)
        val second = PlexException("GET https://clients.plex.tv/api/v2/resources failed: 403 denied", 403)
        val combined = PlexClient.combineResourcesFailures(listOf(first, second))
        assertEquals(401, combined.statusCode)
        val msg = combined.message.orEmpty()
        assertTrue(msg.contains("x".repeat(200)))
        assertTrue(!msg.contains("x".repeat(201)))
        assertTrue(msg.contains("clients.plex.tv"))
        assertTrue(msg.contains("403"))
    }

    @Test
    fun musicSectionsRetriesWithAccountTokenAfterServerToken401() = runBlocking {
        val client = cannedClient { url, token ->
            if (!url.endsWith("/library/sections")) return@cannedClient 404 to "{}"
            when (token) {
                "server-token" -> 401 to """{"errors":[{"status":401}]}"""
                "account-token" -> 200 to sectionsJson
                else -> 403 to "{}"
            }
        }
        val (sections, workingToken) = client.musicSectionsWithTokenFallback(
            "http://192.168.1.2:32400", "server-token", "account-token", "test-device",
        )
        assertEquals(1, sections.size)
        assertEquals("Music", sections[0].title)
        assertEquals("account-token", workingToken)
    }

    @Test
    fun musicSectionsDoesNotRetryOnNon401() = runBlocking {
        val client = cannedClient { url, _ ->
            if (url.endsWith("/library/sections")) 500 to "boom" else 404 to "{}"
        }
        try {
            client.musicSectionsWithTokenFallback(
                "http://192.168.1.2:32400", "server-token", "account-token", "test-device",
            )
            fail("non-401 failures must not be retried or swallowed")
        } catch (e: PlexException) {
            assertEquals(500, e.statusCode)
        }
    }
}
