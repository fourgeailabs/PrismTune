package com.fourgeailabs.prismtune.data

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 1.0.5 DNS-over-HTTPS fallback, scoped to Plex hosts only.
 *
 * Order of operations for any lookup, and only for plex.tv and
 * clients.plex.tv (every other hostname goes through the system
 * resolver only, so local media-server hostnames are never sent to a
 * public DoH service):
 *   1. System DNS, exactly as before (Dns.SYSTEM).
 *   2. Only if that throws UnknownHostException: query a public DoH
 *      JSON endpoint (Google first, Cloudflare second) for A and
 *      AAAA records, and parse the returned address strings as IP
 *      literals — no further DNS lookup is performed on them.
 *
 * What DoH does NOT change: TLS and hostname verification for the
 * original request are untouched. DoH only changes how the address
 * is looked up; the certificate is still checked against plex.tv /
 * clients.plex.tv, and no IP is ever hardcoded. If the DoH endpoint
 * itself cannot be resolved by system DNS, the original
 * UnknownHostException is rethrown, so a globally broken system DNS
 * still surfaces as a network error rather than a fake success.
 *
 * No new dependency: the DoH JSON responses are parsed with
 * org.json, which the app already uses, instead of adding
 * okhttp-dnsoverhttps.
 */
object PlexDns : Dns {

    private val plexHosts = setOf("plex.tv", "clients.plex.tv")
    private val dohEndpoints = listOf(
        "https://dns.google/resolve",
        "https://cloudflare-dns.com/dns-query",
    )

    override fun lookup(hostname: String): List<InetAddress> {
        try {
            return Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            if (hostname !in plexHosts) throw e
            for (endpoint in dohEndpoints) {
                val addresses = dohLookup(endpoint, hostname)
                if (addresses.isNotEmpty()) return addresses
            }
            throw e
        }
    }

    private fun dohLookup(endpoint: String, hostname: String): List<InetAddress> {
        return try {
            val client = OkHttpClient.Builder().build()
            val out = mutableListOf<InetAddress>()
            for (type in listOf(1, 28)) {
                val url = "$endpoint?name=$hostname&type=$type"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return@use
                    val answers = JSONObject(body).optJSONArray("Answer") ?: return@use
                    for (i in 0 until answers.length()) {
                        val answer = answers.optJSONObject(i) ?: continue
                        if (answer.optInt("type") != type) continue
                        val data = answer.optString("data")
                        if (data.isBlank()) continue
                        try {
                            out.add(InetAddress.getByName(data))
                        } catch (_: Exception) {
                            // Skip a malformed answer; try the next one.
                        }
                    }
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
