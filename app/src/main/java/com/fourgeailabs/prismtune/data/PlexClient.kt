package com.fourgeailabs.prismtune.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal Plex HTTP client, JSON-first.
 *
 * Plex's own apps send `Accept: application/json`; plex.tv and Plex Media
 * Server both honour it, so we never have to parse Plex XML.
 *
 * Auth (TV / console style, exactly as the user asked):
 *  1. POST https://plex.tv/api/v2/pins with an EMPTY body -> { id, code }
 *     (a 4-character weak code — the flow that worked for sign-in in
 *     1.0.0. Do NOT send strong=true here: strong PINs return a
 *     24-character code that plex.tv/link rejects, and the strong-PIN
 *     Auth App flow was still rejected on real Equinox hardware in
 *     1.0.2, so the user chose the 4-character flow for 1.0.3.)
 *  2. Show `code` large + a QR code for plain https://plex.tv/link on
 *     the car screen, and offer "Open sign-in in car browser" which
 *     opens the same https://plex.tv/link in Chrome in the car.
 *  3. The user (on phone or in the car browser) signs in to Plex and
 *     types the 4-character code at plex.tv/link.
 *  4. Poll GET https://plex.tv/api/v2/pins/{id} until `authToken` appears.
 *  5. That token is the account token; servers may then supply their own
 *     per-server token via /api/v2/resources.
 *
 * Known trade-off, stated plainly: a weak-PIN token was rejected by
 * /api/v2/resources with 401 on real hardware in 1.0.0, and 1.0.1
 * already handles that by clearing the token and returning to Sign-in
 * with a plain message instead of showing an empty library. The user
 * chose the 4-character flow anyway for 1.0.3, because the strong-PIN
 * flow never got them signed in at all.
 */
/**
 * 1.1.0 pure helpers, unit-testable.
 * Duration: Plex puts a track's duration at the top level, but
 * some listings only carry it in Media[0] or Part[0]; reading only
 * the top level is what showed 0:00 for real tracks.
 */
fun resolveTrackDurationMs(topLevel: Long, mediaDuration: Long, partDuration: Long): Long =
    when {
        topLevel > 0 -> topLevel
        mediaDuration > 0 -> mediaDuration
        partDuration > 0 -> partDuration
        else -> 0
    }

/**
 * 1.1.0 playlist counts: smart/dynamic playlists report leafCount 0
 * on /playlists even with items. The children totalSize is the real
 * count; a missing/negative children count never invents one.
 */
fun correctedPlaylistCount(leafCount: Int, childrenTotalSize: Int): Int =
    if (leafCount != 0) leafCount
    else if (childrenTotalSize >= 0) childrenTotalSize
    else leafCount

/**
 * 1.5.4: pure parser for a Plex secondary-directory
 * response (mood/style lists). Reads "title" from
 * Directory entries, then Metadata entries, then a
 * bare "tag" string. Case-insensitive dedup, first
 * spelling wins, blanks dropped. Unit-testable.
 */
fun parseTagTitles(json: JSONObject): List<String> {
    val container = json.optJSONObject("MediaContainer") ?: return emptyList()
    val seen = mutableSetOf<String>()
    val out = mutableListOf<String>()
    fun add(value: String?) {
        val v = value?.trim().orEmpty()
        if (v.isBlank()) return
        if (seen.add(v.lowercase())) out.add(v)
    }
    for (arrayName in listOf("Directory", "Metadata")) {
        val arr = container.optJSONArray(arrayName) ?: continue
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val title = obj.optString("title").takeIf { it.isNotBlank() }
                ?: obj.optString("tag").takeIf { it.isNotBlank() }
            add(title)
        }
    }
    return out
}

class PlexClient(
    // 1.0.5: PlexDns keeps system DNS first and only falls back to
    // DNS-over-HTTPS for plex.tv / clients.plex.tv when system DNS
    // throws UnknownHostException. TLS and hostname verification are
    // untouched; see PlexDns.kt.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .dns(PlexDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    // 1.1.1 cellular fallback: probing a server connection must not
    // hang for the full 15 s connect timeout per connection. The
    // fallback probe uses a 5 s connect / 15 s read timeout, so
    // trying local -> remote -> relay stays bounded (plus the
    // 10 s per-attempt cap in tryServerConnections).
    private val fallbackHttp: OkHttpClient = OkHttpClient.Builder()
        .dns(PlexDns)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        const val PRODUCT = "PrismTune"
        const val VERSION = "1.1.1"
        const val PLATFORM = "Android Automotive"
        const val DEVICE = "Chevrolet Equinox EV"

        /** Pure client-identifier mapping, shared by headers and tests. */
        fun clientIdFor(deviceId: String): String = "prismtune-$deviceId"

        /**
         * 1.0.5: true only for a DNS failure — an UnknownHostException
         * anywhere in the cause chain, or the resolver's message text.
         * Host fallback (plex.tv -> clients.plex.tv) runs ONLY on this
         * result, never on HTTP errors, timeouts, or other failures.
         */
        fun isDnsFailure(e: Throwable): Boolean {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is java.net.UnknownHostException) return true
                cause = cause.cause
            }
            val message = e.message.orEmpty()
            return message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("No address associated with hostname", ignoreCase = true)
        }

        /**
         * 1.0.6: combines a plex.tv PIN failure and a clients.plex.tv
         * PIN failure into one PlexException carrying both statuses
         * and bodies, each body truncated to 200 chars. Pure and
         * unit-testable: no network, no token, no code, no PIN id.
         * The first failure's statusCode is kept so callers that
         * branch on status still see the primary host's status.
         */
        fun combinePinFailures(first: Exception, second: Exception): PlexException {
            fun snippet(e: Exception): String {
                val raw = e.message.orEmpty()
                // postPin messages are "POST <host>/api/v2/pins
                // failed: <status> <body>"; keep the whole segment
                // but cap the body part at 200 chars.
                val statusIdx = raw.indexOf("failed:")
                if (statusIdx < 0) return raw.take(260)
                val head = raw.substring(0, statusIdx + "failed:".length)
                val rest = raw.substring(statusIdx + "failed:".length).trim()
                val status = rest.substringBefore(' ').take(10)
                val body = rest.substringAfter(' ', "").take(200)
                return if (body.isBlank()) "$head $status" else "$head $status $body"
            }
            val status = (first as? PlexException)?.statusCode
                ?: Regex("failed: (\\d{3})").find(first.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            return PlexException(
                "${snippet(first)} | ${snippet(second)}",
                status,
            )
        }

        /**
         * 1.0.7: combines every failed server-list attempt into one
         * PlexException, in attempt order, each body truncated to
         * 200 chars. Pure and unit-testable: no network, and no
         * token ever appears — get() messages carry only the URL,
         * status, and Plex body. The first failure's statusCode is
         * kept so callers branching on 401 still see it.
         */
        fun combineResourcesFailures(failures: List<Exception>): PlexException {
            fun snippet(e: Exception): String {
                val raw = e.message.orEmpty()
                val statusIdx = raw.indexOf("failed:")
                if (statusIdx < 0) return raw.take(260)
                val head = raw.substring(0, statusIdx + "failed:".length)
                val rest = raw.substring(statusIdx + "failed:".length).trim()
                val status = rest.substringBefore(' ').take(10)
                val body = rest.substringAfter(' ', "").take(200)
                return if (body.isBlank()) "$head $status" else "$head $status $body"
            }
            val first = failures.firstOrNull()
            val status = (first as? PlexException)?.statusCode
                ?: first?.message?.let {
                    Regex("failed: (\\d{3})").find(it)?.groupValues?.get(1)?.toIntOrNull()
                }
            val parts = failures.joinToString("; ") { snippet(it) }
            return PlexException("Server list failed: $parts. No servers were returned.", status)
        }

        /**
         * Builds the exact PIN-creation request that createPin() sends.
         * Extracted so unit tests can assert on it: the URL is the pins
         * endpoint, and the body is EMPTY — no strong=true, no strong
         * parameter at all. That empty body is the pre-1.0.1 path proven
         * to return a 4-character code.
         */
        fun buildPinRequest(deviceId: String, base: String = PlexUrls.PLEX_TV): Request {
            val builder = Request.Builder()
                .url("$base/api/v2/pins")
                .post(ByteArray(0).toRequestBody())
            mapOf(
                "X-Plex-Product" to PRODUCT,
                "X-Plex-Version" to VERSION,
                "X-Plex-Client-Identifier" to clientIdFor(deviceId),
                "X-Plex-Platform" to PLATFORM,
                "X-Plex-Device" to DEVICE,
                "Accept" to "application/json",
            ).forEach { (k, v) -> builder.header(k, v) }
            return builder.build()
        }
    }

    private fun clientId(deviceId: String): String = clientIdFor(deviceId)

    private fun plexHeaders(deviceId: String): Map<String, String> = mapOf(
        "X-Plex-Product" to PRODUCT,
        "X-Plex-Version" to VERSION,
        "X-Plex-Client-Identifier" to clientId(deviceId),
        "X-Plex-Platform" to PLATFORM,
        "X-Plex-Device" to DEVICE,
        "Accept" to "application/json",
    )

    private suspend fun get(url: String, token: String?, deviceId: String): JSONObject =
        getFrom(http, url, token, deviceId)

    private suspend fun getFrom(client: OkHttpClient, url: String, token: String?, deviceId: String): JSONObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            plexHeaders(deviceId).forEach { (k, v) -> builder.header(k, v) }
            if (!token.isNullOrBlank()) builder.header("X-Plex-Token", token)
            client.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw PlexException("GET $url failed: ${resp.code} $body", resp.code)
                JSONObject(body)
            }
        }

    /** 1.1.1: music sections over the short-timeout client, for connection probing. */
    suspend fun musicSectionsFast(serverBase: String, token: String, deviceId: String): List<PlexSection> {
        val json = getFrom(fallbackHttp, PlexUrls.join(serverBase, "/library/sections"), token, deviceId)
        val out = mutableListOf<PlexSection>()
        val dirs = json.optJSONObject("MediaContainer")?.optJSONArray("Directory") ?: JSONArray()
        for (i in 0 until dirs.length()) {
            val d = dirs.optJSONObject(i) ?: continue
            if (d.optString("type") == "artist") {
                out.add(PlexSection(key = d.optString("key"), title = d.optString("title")))
            }
        }
        return out
    }

    /** 1.1.1: token fallback, same rule as musicSectionsWithTokenFallback, over the fast client. */
    suspend fun musicSectionsWithTokenFallbackFast(
        serverBase: String, primaryToken: String, fallbackToken: String, deviceId: String,
    ): Pair<List<PlexSection>, String> {
        try {
            return musicSectionsFast(serverBase, primaryToken, deviceId) to primaryToken
        } catch (e: Exception) {
            val is401 = (e as? PlexException)?.statusCode == 401 || e.message?.contains(" 401 ") == true
            if (!is401 || fallbackToken.isBlank() || fallbackToken == primaryToken) throw e
            return musicSectionsFast(serverBase, fallbackToken, deviceId) to fallbackToken
        }
    }

    private suspend fun postPin(hostBase: String, deviceId: String): JSONObject =
        withContext(Dispatchers.IO) {
            // 1.0.3: back to the empty-body POST that returns a
            // 4-character code. Do NOT add strong=true (or any strong
            // parameter): that returns a 24-character code that
            // plex.tv/link rejects, and the 1.0.2 Auth App flow for it
            // was still rejected on the Equinox.
            http.newCall(buildPinRequest(deviceId, hostBase)).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw PlexException("POST $hostBase/api/v2/pins failed: ${resp.code} $body", resp.code)
                JSONObject(body)
            }
        }

    /**
     * Step 1 of PIN login. Returns the pin id + the 4-character code
     * to display, plus the host the PIN was created on.
     *
     * 1.0.5 fallback, in order, and ONLY on a DNS failure: plex.tv
     * first (the path that worked in 1.0.0), then Plex's own current
     * API host clients.plex.tv with the same headers and empty body.
     * If clients.plex.tv also fails with a DNS failure, the DoH
     * fallback inside PlexDns has already had its chance on both
     * hosts, and the DNS error is rethrown for the plain-language
     * network message. Non-DNS failures are never retried on the
     * other host.
     */
    suspend fun createPin(deviceId: String): PinResult {
        try {
            val json = postPin(PlexUrls.PLEX_TV, deviceId)
            return PinResult(
                id = json.optLong("id"),
                code = json.optString("code"),
                host = PlexUrls.PLEX_TV,
            )
        } catch (first: Exception) {
            // 1.0.6: plex.tv PIN creation is now retried on
            // clients.plex.tv for ANY failure, not only DNS. On
            // 2026-10-25 the phone's real sign-in PIN POST failed
            // while the diagnostic PIN POST had succeeded, and the
            // generic message hid which host/status failed, so a
            // single-host failure must not end the attempt, and a
            // both-hosts failure must carry both statuses/bodies
            // (each truncated) so the sign-in screen can show them.
            // No token, code, or PIN id is ever in these messages:
            // postPin's message is only host + status + Plex body.
            val json = try {
                postPin(PlexUrls.CLIENTS_PLEX_TV, deviceId)
            } catch (second: Exception) {
                throw combinePinFailures(first, second)
            }
            return PinResult(
                id = json.optLong("id"),
                code = json.optString("code"),
                host = PlexUrls.CLIENTS_PLEX_TV,
            )
        }
    }

    /**
     * Step 4 of PIN login. Returns authToken once the user has
     * approved on their phone. Polling MUST use the same host the
     * PIN was created on — the ViewModel passes pin.host here —
     * because a PIN created on clients.plex.tv is not guaranteed to
     * be pollable on plex.tv, and vice versa.
     */
    suspend fun pollPin(pinId: Long, deviceId: String, host: String = PlexUrls.PLEX_TV): PinResult {
        val json = get("$host/api/v2/pins/$pinId", null, deviceId)
        val token = json.optString("authToken").takeIf { it.isNotBlank() }
        return PinResult(
            id = json.optLong("id", pinId),
            code = json.optString("code"),
            authToken = token,
            host = host,
        )
    }

    /**
     * 1.0.8: username/password sign-in, an additional option
     * alongside PIN login — it bypasses PIN issuance entirely.
     * On 2026-10-25 a fresh weak-PIN token was rejected by the
     * server list on all four hosts/query variants with 401
     * code 1001 "User could not be authenticated", even after
     * all app data was cleared, so a token from Plex's direct
     * login endpoint is the next test: if that token lists
     * servers, PIN issuance was the broken part; if it also
     * gets 1001, the problem is account-level, not a flow.
     *
     * Endpoint: POST {host}/api/v2/users/sign_in.json with a
     * form body of login + password, under the same PrismTune
     * headers as every other Plex call. Fallbacks, in order,
     * only on 404 (a wrong endpoint shape, not a wrong
     * password): {host}/api/v2/users/signin with the same
     * form body, then the legacy {host}/users/sign_in.json
     * with user[login]/user[password]. A 401 is never
     * retried on another endpoint shape: a wrong password
     * must surface immediately, not burn extra failed
     * attempts. Host fallback mirrors PIN/resources: plex.tv
     * first, then clients.plex.tv on any failure.
     *
     * Two-factor: Plex reports a missing verification code
     * as a 401 whose body mentions a verification code
     * (python-plexapi raises TwoFactorRequired on exactly
     * that text). That case throws TwoFactorRequired, never
     * a generic rejection, so the UI can prompt for the
     * code. A retry WITH a code sends the commonly
     * documented X-Plex-Verification-Code header — Plex
     * does not document this header in its public PIN
     * guide, so if Plex ever rejects the code header the
     * 2FA error text will say so and the retry can be
     * adjusted to the field Plex actually accepts.
     *
     * Password safety, hard rules: the password is sent
     * only in the HTTPS request body, never in a URL, never
     * in a header, never in any exception message, never
     * logged, and never stored. Only the returned token is
     * kept, by the caller's TokenStore. Local references
     * are cleared in a finally block (best effort: Kotlin
     * Strings are immutable, so this drops references
     * rather than wiping memory). Token extraction handles
     * every shape Plex has used for this endpoint:
     * top-level authToken / authentication_token /
     * authenticationToken, and the same keys under a
     * "user" object.
     */
    suspend fun signInWithPassword(
        login: String,
        password: String,
        deviceId: String,
        verificationCode: String? = null,
    ): String {
        var pw: String? = password
        try {
            val failures = mutableListOf<Exception>()
            var twoFactor: TwoFactorRequired? = null
            for (host in listOf(PlexUrls.PLEX_TV, PlexUrls.CLIENTS_PLEX_TV)) {
                try {
                    val json = postSignInHost(host, login, pw.orEmpty(), deviceId, verificationCode)
                    val token = extractToken(json)
                    if (!token.isNullOrBlank()) return token
                    throw PlexException(
                        "POST $host/api/v2/users/sign_in.json failed: no authToken in Plex response",
                        null,
                    )
                } catch (e: TwoFactorRequired) {
                    twoFactor = e
                    failures.add(e)
                } catch (e: Exception) {
                    failures.add(e)
                }
            }
            // A 2FA failure on either host outranks a generic
            // failure: the user can fix it by entering a code,
            // while a generic combined message would hide it.
            twoFactor?.let { throw it }
            val first = failures.firstOrNull()
            throw first ?: PlexException("Username/password sign-in failed: no host responded", null)
        } finally {
            pw = null
        }
    }

    /** Pure token extraction, unit-testable: no network, no password, no token logging. */
    fun extractToken(json: JSONObject): String? {
        for (key in listOf("authToken", "authentication_token", "authenticationToken")) {
            json.optString(key).takeIf { it.isNotBlank() }?.let { return it }
        }
        val user = json.optJSONObject("user") ?: return null
        for (key in listOf("authToken", "authentication_token", "authenticationToken")) {
            user.optString(key).takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** Pure 2FA detection: a 401 whose Plex body mentions a verification code. */
    fun isTwoFactorRequired(e: Throwable): Boolean {
        val status = (e as? PlexException)?.statusCode
        if (status != null && status != 401) return false
        val body = e.message.orEmpty()
        return body.contains("verification code", ignoreCase = true) ||
            body.contains("two-factor", ignoreCase = true) ||
            body.contains("two factor", ignoreCase = true) ||
            body.contains("2fa", ignoreCase = true)
    }

    private suspend fun postSignInHost(
        hostBase: String,
        login: String,
        password: String,
        deviceId: String,
        verificationCode: String?,
    ): JSONObject = withContext(Dispatchers.IO) {
        val variants = listOf(
            "$hostBase/api/v2/users/sign_in.json" to
                okhttp3.FormBody.Builder()
                    .add("login", login).add("password", password).build(),
            "$hostBase/api/v2/users/signin" to
                okhttp3.FormBody.Builder()
                    .add("login", login).add("password", password).build(),
            "$hostBase/users/sign_in.json" to
                okhttp3.FormBody.Builder()
                    .add("user[login]", login).add("user[password]", password).build(),
        )
        var last404: PlexException? = null
        for ((url, formBody) in variants) {
            val builder = Request.Builder().url(url).post(formBody)
            plexHeaders(deviceId).forEach { (k, v) -> builder.header(k, v) }
            if (!verificationCode.isNullOrBlank()) {
                builder.header("X-Plex-Verification-Code", verificationCode)
            }
            http.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val failure = PlexException("POST $url failed: ${resp.code} $body", resp.code)
                    if (resp.code == 404) {
                        last404 = failure
                        // Fall through to the next endpoint shape.
                    } else {
                        if (isTwoFactorRequired(failure)) throw TwoFactorRequired(failure.message.orEmpty())
                        throw failure
                    }
                } else {
                    return@withContext JSONObject(body)
                }
            }
        }
        throw last404 ?: PlexException("POST $hostBase/api/v2/users/sign_in.json failed: no endpoint responded", null)
    }

    /**
     * Servers reachable with this account token.
     *
     * 1.0.7, in order, before giving up: (a) plex.tv with
     * includeHttps/includeRelay, (b) clients.plex.tv with the same
     * query, on ANY failure of (a) — not just DNS, because on
     * 2026-10-25 a fresh token was rejected with 401 on plex.tv's
     * server list even after all app data was cleared, and
     * clients.plex.tv is Plex's current API host — (c) plex.tv
     * with no query params, (d) clients.plex.tv with no query
     * params. Only if all four fail is a combined PlexException
     * thrown, carrying each host/status/body (each body truncated
     * to 200 chars) so the sign-in screen can show exactly what
     * Plex said, instead of clearing the token on a generic
     * message. No token ever appears in these messages: get()
     * messages carry only URL, status, and Plex body.
     */
    /**
     * 1.0.9: raw-body fetch for the server list, mirroring get()
     * except it returns the body string instead of parsing it as
     * a JSONObject. Needed because /api/v2/resources with
     * Accept: application/json returns a BARE JSON ARRAY of
     * device objects, and get()'s JSONObject(body) throws
     * "Value [...] cannot be converted to JSONObject" before
     * any parsing runs. Error messages keep get()'s exact
     * "GET $url failed: <status> <body>" shape so
     * combineResourcesFailures() renders them identically.
     */
    private suspend fun getRaw(url: String, token: String?, deviceId: String): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            plexHeaders(deviceId).forEach { (k, v) -> builder.header(k, v) }
            if (!token.isNullOrBlank()) builder.header("X-Plex-Token", token)
            http.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw PlexException("GET $url failed: ${resp.code} $body", resp.code)
                body
            }
        }

    suspend fun resources(token: String, deviceId: String): List<PlexServer> {
        val attempts = listOf(
            "${PlexUrls.PLEX_TV}/api/v2/resources?includeHttps=1&includeRelay=1",
            "${PlexUrls.CLIENTS_PLEX_TV}/api/v2/resources?includeHttps=1&includeRelay=1",
            "${PlexUrls.PLEX_TV}/api/v2/resources",
            "${PlexUrls.CLIENTS_PLEX_TV}/api/v2/resources",
        )
        val failures = mutableListOf<Exception>()
        for (url in attempts) {
            try {
                val body = getRaw(url, token, deviceId)
                val out = mutableListOf<PlexServer>()
                // 1.0.9: the body may be a bare JSON array (the
                // shape Plex actually returned on 2026-10-26, which
                // 1.0.8 failed to parse) or a MediaContainer
                // object; parseResourcesBody handles both, and a
                // malformed body falls through to the next attempt
                // like any other failure instead of crashing.
                parseResourcesBody(body, out)
                return out
            } catch (e: Exception) {
                failures.add(e)
            }
        }
        throw combineResourcesFailures(failures)
    }

    /**
     * 1.0.9: parse a server-list body in either shape. A bare
     * JSONArray is the /api/v2/resources JSON shape; a JSONObject
     * goes through the legacy MediaContainer path. Pure and
     * unit-testable: no network, no token.
     */
    fun parseResourcesBody(body: String, out: MutableList<PlexServer>) {
        val parsed = try {
            org.json.JSONTokener(body).nextValue()
        } catch (e: Exception) {
            throw PlexException("Server list response was not valid JSON: ${e.message}", null)
        }
        when (parsed) {
            is JSONArray -> parseDevices(parsed, out)
            is JSONObject -> parseResources(parsed, out)
            else -> Unit // Empty / scalar body: no servers, not a crash.
        }
    }

    private fun parseResources(json: JSONObject, out: MutableList<PlexServer>) {
        val container = json.opt("MediaContainer") ?: return
        val devices: JSONArray = when (container) {
            is JSONArray -> container
            is JSONObject -> container.optJSONArray("Device")
                ?: container.optJSONArray("device")
                ?: JSONArray()
            else -> JSONArray()
        }
        parseDevices(devices, out)
    }

    /** Plex booleans arrive as JSON true/false or as 1/0; accept both, defaulting as given. */
    private fun optFlag(obj: JSONObject, key: String, default: Boolean): Boolean =
        when (val v = obj.opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v == "1" || v.equals("true", ignoreCase = true)
            else -> default
        }

    /** A device provides "server" either as a string or as an array containing it. */
    private fun providesServer(d: JSONObject): Boolean =
        when (val v = d.opt("provides")) {
            is String -> v == "server"
            is JSONArray -> (0 until v.length()).any { v.optString(it) == "server" }
            else -> false
        }

    private fun parseDevices(devices: JSONArray, out: MutableList<PlexServer>) {
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            if (!providesServer(d)) continue
            val conns = mutableListOf<PlexConnection>()
            val connArr = d.optJSONArray("Connection")
                ?: d.optJSONArray("connections")
                ?: JSONArray()
            for (j in 0 until connArr.length()) {
                val c = connArr.optJSONObject(j) ?: continue
                val uri = c.optString("uri").ifBlank { c.optString("address") }
                if (uri.isNotBlank()) {
                    conns.add(
                        PlexConnection(
                            uri = uri,
                            local = optFlag(c, "local", false),
                            protocol = c.optString("protocol").ifBlank { "http" },
                        ),
                    )
                }
            }
            val token = d.optString("accessToken").ifBlank { d.optString("access_token") }
            val id = d.optString("clientIdentifier").ifBlank { d.optString("client_identifier") }
            out.add(
                PlexServer(
                    id = id,
                    name = d.optString("name"),
                    product = d.optString("product"),
                    connections = conns,
                    accessToken = token,
                    owned = optFlag(d, "owned", true),
                ),
            )
        }
    }

    /**
     * 1.0.7: music sections with a one-time token retry. The
     * server's per-server accessToken from /resources and the
     * account token are not always interchangeable: if the first
     * token gets a 401 from the media server, retry once with the
     * other token before reporting failure. Returns the sections
     * plus the token that actually worked, so the rest of the
     * library load uses the working token. Non-401 failures are
     * never retried, and if both tokens are the same (or the
     * fallback is blank) there is no second attempt.
     */
    suspend fun musicSectionsWithTokenFallback(
        serverBase: String,
        primaryToken: String,
        fallbackToken: String,
        deviceId: String,
    ): Pair<List<PlexSection>, String> {
        try {
            return musicSections(serverBase, primaryToken, deviceId) to primaryToken
        } catch (e: Exception) {
            val is401 = (e as? PlexException)?.statusCode == 401 ||
                e.message?.contains(" 401 ") == true
            if (!is401 || fallbackToken.isBlank() || fallbackToken == primaryToken) throw e
            return musicSections(serverBase, fallbackToken, deviceId) to fallbackToken
        }
    }


    /**
     * 1.1.0 pagination: Plex /all and /children endpoints return only
     * one page (the 45k-song library previously surfaced just over
     * 200 items because only the first page was parsed). Loop with
     * X-Plex-Container-Start / X-Plex-Container-Size (500 per page)
     * until fetched >= MediaContainer.totalSize (or a page returns
     * fewer than requested, or 200 pages — a 100k-item guard against
     * an infinite loop on a misbehaving server). Returns raw
     * Metadata JSONObjects, lightweight: Media/Part is fetched on
     * demand for playback only, never for a 45k listing.
     */
    private suspend fun fetchAllMetadata(serverBase: String, path: String, token: String, deviceId: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var start = 0
        var pages = 0
        while (pages < 200) {
            val sep = if (path.contains("?")) "&" else "?"
            val paged = "$path${sep}X-Plex-Container-Start=$start&X-Plex-Container-Size=500"
            val json = get(PlexUrls.join(serverBase, paged), token, deviceId)
            val container = json.optJSONObject("MediaContainer") ?: break
            val metas = container.optJSONArray("Metadata") ?: JSONArray()
            for (i in 0 until metas.length()) {
                metas.optJSONObject(i)?.let { out.add(it) }
            }
            pages++
            val total = container.optInt("totalSize", -1)
            val fetched = out.size
            if (metas.length() < 500) break
            if (total >= 0 && !hasMorePages(fetched, total)) break
            start += 500
        }
        return out
    }

    /** Music library sections on a server. */
    suspend fun musicSections(serverBase: String, token: String, deviceId: String): List<PlexSection> {
        val json = get(PlexUrls.join(serverBase, "/library/sections"), token, deviceId)
        val out = mutableListOf<PlexSection>()
        val dirs = json.optJSONObject("MediaContainer")?.optJSONArray("Directory") ?: JSONArray()
        for (i in 0 until dirs.length()) {
            val d = dirs.optJSONObject(i) ?: continue
            if (d.optString("type") == "artist") {
                out.add(PlexSection(key = d.optString("key"), title = d.optString("title")))
            }
        }
        return out
    }

    /** All artists in a section, paginated (1.1.0): the full
     *  artist list, not just the first page. Artist thumb is the
     *  artist's own Plex image, never album art. */
    suspend fun artists(serverBase: String, token: String, deviceId: String, sectionKey: String): List<PlexArtist> {
        return fetchAllMetadata(serverBase, "/library/sections/$sectionKey/all?type=8", token, deviceId).map { d ->
            PlexArtist(
                key = d.optString("ratingKey"),
                title = d.optString("title"),
                thumb = d.optString("thumb").takeIf { it.isNotBlank() },
                summary = d.optString("summary").takeIf { it.isNotBlank() },
                keyPath = d.optString("key").takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * 1.1.0 artist children, fixed for the 404s seen on 2026-10-26
     * (metadata/51261, 51264, 51265 children -> 404 HTML, artists
     * list still on screen, no albums). Two causes are handled:
     * (a) the path: if the artist carries Plex's own "key" field,
     * use it ("/library/metadata/<id>" -> +"/children", or an
     * already-"/children" key as-is) instead of guessing from
     * ratingKey alone; (b) the type: if plain children 404s,
     * retry once with ?type=9 (albums) — some servers only serve
     * music-artist children when the type is named. A genuine
     * 404 after both tries is thrown with its status, and the
     * ViewModel shows it on the dedicated artist view only,
     * never as a persistent error on the Artists list.
     */
    fun artistChildrenPath(artist: PlexArtist): String {
        val kp = artist.keyPath.orEmpty()
        return when {
            kp.endsWith("/children") -> kp
            kp.startsWith("/library/metadata/") -> "$kp/children"
            else -> "/library/metadata/${artist.key}/children"
        }
    }

    suspend fun albumsForArtist(serverBase: String, token: String, deviceId: String, artistKey: String): List<PlexAlbum> =
        albumsForArtistFor(serverBase, token, deviceId, PlexArtist(key = artistKey, title = ""))

    suspend fun albumsForArtistFor(serverBase: String, token: String, deviceId: String, artist: PlexArtist): List<PlexAlbum> {
        val path = artistChildrenPath(artist)
        return try {
            fetchAllMetadata(serverBase, path, token, deviceId).map { d ->
                PlexAlbum(
                    key = d.optString("ratingKey"),
                    title = d.optString("title"),
                    artistTitle = d.optString("parentTitle"),
                    thumb = d.optString("thumb").takeIf { it.isNotBlank() },
                    year = d.optInt("year").takeIf { it > 0 },
                    artistThumb = d.optString("parentThumb").takeIf { it.isNotBlank() },
                    viewCount = d.optInt("viewCount", 0),
                    userRating = d.optDouble("userRating", -1.0).let { if (it < 0) null else it.toFloat() },
                )
            }
        } catch (e: Exception) {
            val is404 = (e as? PlexException)?.statusCode == 404 || e.message?.contains(" 404 ") == true
            if (!is404 || path.contains("type=9")) throw e
            val sep = if (path.contains("?")) "&" else "?"
            fetchAllMetadata(serverBase, "$path${sep}type=9", token, deviceId).map { d ->
                PlexAlbum(
                    key = d.optString("ratingKey"),
                    title = d.optString("title"),
                    artistTitle = d.optString("parentTitle"),
                    thumb = d.optString("thumb").takeIf { it.isNotBlank() },
                    year = d.optInt("year").takeIf { it > 0 },
                    artistThumb = d.optString("parentThumb").takeIf { it.isNotBlank() },
                    viewCount = d.optInt("viewCount", 0),
                    userRating = d.optDouble("userRating", -1.0).let { if (it < 0) null else it.toFloat() },
                )
            }
        }
    }

    /** Tracks for one album (children of the album), paginated (1.1.0). */
    suspend fun tracksForAlbum(serverBase: String, token: String, deviceId: String, albumKey: String): List<PlexTrack> =
        fetchAllMetadata(serverBase, "/library/metadata/$albumKey/children", token, deviceId).map { parseTrack(it) }

    /**
     * 1.1.2 playlist tracks, fixed for the All Music 404 seen
     * 2026-10-26 (metadata/51262/children -> 404 HTML): a
     * playlist ratingKey is a playlist ID, not a library
     * metadata ID, so tracks load from /playlists/<id>/items
     * first (paginated, 500 per page via fetchAllMetadata).
     * Only if items 404s is the old metadata-children path
     * tried as a fallback; any other failure (e.g. 401) is
     * thrown immediately, and only an error after both 404
     * is reported. An empty playlist returns empty, never
     * invented tracks.
     */
    suspend fun tracksForPlaylistFor(serverBase: String, token: String, deviceId: String, playlist: PlexPlaylist): List<PlexTrack> {
        val itemsPath = playlistItemsPath(playlist)
        // 1.5.6 — 2026-10-27 05:42: his smart/dynamic
        // playlists (Tracks, Fresh, Recently Played) open
        // EMPTY even though Plex shows items in them, while
        // regular playlists load fine. The items endpoint
        // succeeds for them but returns no Metadata — a
        // wrong-endpoint empty, not a truly empty playlist.
        // So an empty items result now tries the metadata-
        // children path and the type=10 items variant before
        // giving up, instead of silently showing "0 tracks".
        // A playlist that is genuinely empty still returns
        // empty after every fallback — nothing is invented.
        val first = try {
            fetchAllMetadata(serverBase, itemsPath, token, deviceId).map { parseTrack(it) }
        } catch (e: Exception) {
            if (!isPlaylistItems404(e)) throw e
            fetchAllMetadata(serverBase, playlistMetadataChildrenPath(playlist), token, deviceId).map { parseTrack(it) }
        }
        if (first.isNotEmpty()) return first
        val children = runCatching {
            fetchAllMetadata(serverBase, playlistMetadataChildrenPath(playlist), token, deviceId).map { parseTrack(it) }
        }.getOrDefault(emptyList())
        if (children.isNotEmpty()) return children
        val typed = runCatching {
            fetchAllMetadata(serverBase, "$itemsPath?type=10", token, deviceId).map { parseTrack(it) }
        }.getOrDefault(emptyList())
        return typed
    }

    /** Tracks in a playlist, paginated (1.1.0, path fixed 1.1.2). */
    suspend fun tracksForPlaylist(serverBase: String, token: String, deviceId: String, playlistKey: String): List<PlexTrack> =
        tracksForPlaylistFor(serverBase, token, deviceId, PlexPlaylist(key = playlistKey, title = ""))

    /** Tracks in a section, most recently added first (used by Home). */
    suspend fun recentlyAdded(serverBase: String, token: String, deviceId: String, sectionKey: String): List<PlexTrack> {
        val json = get(
            PlexUrls.join(serverBase, "/library/sections/$sectionKey/recentlyAdded?type=10&X-Plex-Container-Size=24"),
            token,
            deviceId,
        )
        return parseTracks(json)
    }

    /** 1.2.3: albums in a section, most recently added first.
     *  Home's Recently Added is albums now, not songs — type=9
     *  is album, type=10 is track. Used by Home. */
    suspend fun recentlyAddedAlbums(serverBase: String, token: String, deviceId: String, sectionKey: String): List<PlexAlbum> {
        val json = get(
            PlexUrls.join(serverBase, "/library/sections/$sectionKey/recentlyAdded?type=9&X-Plex-Container-Size=24"),
            token,
            deviceId,
        )
        val container = json.optJSONObject("MediaContainer") ?: return emptyList()
        val metadata = container.optJSONArray("Metadata") ?: return emptyList()
        val out = ArrayList<PlexAlbum>()
        for (i in 0 until metadata.length()) {
            val d = metadata.optJSONObject(i) ?: continue
            out.add(PlexAlbum(
                key = d.optString("ratingKey"),
                title = d.optString("title"),
                artistTitle = d.optString("parentTitle"),
                thumb = d.optString("thumb").takeIf { it.isNotBlank() },
                year = d.optInt("year").takeIf { it > 0 },
                artistThumb = d.optString("parentThumb").takeIf { it.isNotBlank() },
                viewCount = d.optInt("viewCount", 0),
                userRating = d.optDouble("userRating", -1.0).let { if (it < 0) null else it.toFloat() },
            ))
        }
        return out
    }

    suspend fun playlists(serverBase: String, token: String, deviceId: String): List<PlexPlaylist> {
        return fetchAllMetadata(serverBase, "/playlists?playlistType=audio", token, deviceId).map { d ->
            PlexPlaylist(
                key = d.optString("ratingKey"),
                title = d.optString("title"),
                thumb = d.optString("thumb").takeIf { it.isNotBlank() },
                itemCount = d.optInt("leafCount", 0),
                // 1.1.2: capture Plex's own key ("/playlists/<id>")
                // so track loading uses the items endpoint, not
                // a guessed /library/metadata children path.
                keyPath = d.optString("key").takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * 1.1.0 playlist item count, independent of leafCount: smart /
     * dynamic playlists (Tracks, Fresh, Recently Played in the user's
     * library) report leafCount 0 on /playlists even when they
     * have items, while All Music / Recently Added / Bedtime
     * report correctly. Children with Container-Size=0 returns
     * MediaContainer.totalSize without loading any tracks, so
     * this gives the true count without inventing one. Returns
     * -1 when the server gives no totalSize.
     */
    /**
     * 1.1.2 playlist item count, same endpoint fix as tracks:
     * items endpoint with Container-Size=0 for totalSize,
     * falling back to metadata children on 404 only, so
     * smart/dynamic playlist counts stay correct and are
     * never invented. Returns -1 when no totalSize is given.
     */
    suspend fun playlistItemCountFor(serverBase: String, token: String, deviceId: String, playlist: PlexPlaylist): Int {
        if (playlist.key.isBlank()) return -1
        val itemsJson = try {
            get(PlexUrls.join(serverBase, "${playlistItemsPath(playlist)}?X-Plex-Container-Size=0"), token, deviceId)
        } catch (e: Exception) {
            if (!isPlaylistItems404(e)) throw e
            get(PlexUrls.join(serverBase, "${playlistMetadataChildrenPath(playlist)}?X-Plex-Container-Size=0"), token, deviceId)
        }
        return itemsJson.optJSONObject("MediaContainer")?.optInt("totalSize", -1) ?: -1
    }

    suspend fun playlistItemCount(serverBase: String, token: String, deviceId: String, playlistKey: String): Int =
        playlistItemCountFor(serverBase, token, deviceId, PlexPlaylist(key = playlistKey, title = ""))

    /**
     * 1.3.8: PLEX NATIVE mood/genre/style filter, server-side.
     * 2026-10-26 23:59: "Plex already has a moods
     * section built into the server. Is there any way that
     * we can tap into that?" Yes — and we should have from
     * the start. Plex's /library/sections/{key}/all endpoint
     * accepts advanced filters as query parameters, ANDed
     * together, with tag fields using = for "is tagged with".
     * For music tracks (type=10), the documented filter
     * parameters are: track.mood, track.style, and genre
     * (plus artist.mood / artist.style at the artist level,
     * which is NOT what we want for tracks — a track's own
     * Mood/Style tags are what Plex's own Moods section uses).
     * This function asks Plex for tracks tagged with that
     * exact mood/style/genre, paged 500 at a time, up to
     * 2000 tracks, instead of downloading the first 100
     * songs in the server and filtering them locally — which
     * is why tapping a mood "only shows the first 100 songs
     * in the server, not actually selecting songs for that
     * particular mood". If Plex returns 0 for a filter (e.g.
     * a taxonomy term like "Happy" that is not a real Plex
     * tag on this server), the caller falls back to the local
     * taxonomy filter, so a mood never plays empty just
     * because Plex's tag spelling differs.
     * URL-encoding: mood names with spaces (e.g. "Laid Back")
     * must be encoded, or Plex will split them.
     */
    suspend fun tracksForMood(
        serverBase: String, token: String, deviceId: String, sectionKey: String,
        kind: String, name: String, limit: Int = 2000,
    ): List<PlexTrack> {
        val param = when (kind) {
            "mood" -> "track.mood"
            "vibe" -> "track.style"
            else -> "genre"
        }
        val encoded = java.net.URLEncoder.encode(name, "UTF-8")
        val out = mutableListOf<PlexTrack>()
        var start = 0
        while (out.size < limit) {
            val pageSize = minOf(500, limit - out.size)
            val json = get(
                PlexUrls.join(serverBase, "/library/sections/$sectionKey/all?type=10&$param=$encoded&X-Plex-Container-Start=$start&X-Plex-Container-Size=$pageSize"),
                token, deviceId,
            )
            val container = json.optJSONObject("MediaContainer") ?: break
            val metas = container.optJSONArray("Metadata") ?: JSONArray()
            if (metas.length() == 0) break
            for (i in 0 until metas.length()) {
                metas.optJSONObject(i)?.let { out.add(parseTrack(it)) }
            }
            val total = container.optInt("totalSize", -1)
            start += metas.length()
            if (metas.length() < pageSize) break
            if (total >= 0 && start >= total) break
        }
        return out
    }

    /**
     * 1.5.4 SERVER MOOD LIST — 2026-10-27 04:54: in
     * Windows File Explorer > Network, his Plex server shows
     * a filtered Moods view. That view is Plex's DLNA server
     * rendering the server's own mood index — the same index
     * Plex Amp renders. DLNA itself is useless in the car
     * (home-LAN only, no sign-in, no remote), but the Plex
     * API exposes the same index as a section's secondary
     * directories: /library/sections/{key}/mood and
     * /library/sections/{key}/style. One small call each
     * returns the full Plex-spelled mood/style list, with NO
     * 45k-track scan and NO genre guessing. That is the
     * primary Moods list source in 1.5.4; the track-tag
     * scan stays as the fallback and as the count source.
     * PROBE, NOT A PROMISE: the exact secondary-directory
     * path is not verified against the user's server (it was
     * unreachable from hosted Plex Web), so every call is
     * runCatching — a 404/empty response returns an empty
     * list and the caller falls back to scan terms, never
     * an error screen. Titles are read from Directory
     * entries first, Metadata second, case-insensitive
     * dedup, first spelling wins, blanks dropped.
     * Counts are deliberately NOT invented here: a server
     * term with no scan count yet is shown with no count
     * (see FeelsScreen), never "0 tracks".
     */
    data class ServerTagLists(val moods: List<String> = emptyList(), val styles: List<String> = emptyList())

    suspend fun serverTagLists(
        serverBase: String, token: String, deviceId: String, sectionKey: String,
    ): ServerTagLists {
        suspend fun fetch(path: String): List<String> = runCatching {
            val json = get(
                PlexUrls.join(serverBase, "/library/sections/$sectionKey/$path?X-Plex-Container-Size=1000"),
                token, deviceId,
            )
            parseTagTitles(json)
        }.getOrDefault(emptyList())
        return ServerTagLists(moods = fetch("mood"), styles = fetch("style"))
    }

    /** 1.1.0 single page of tracks for Moods incremental loading:
     *  returns (tracks, totalSize or null). Never loads the whole
     *  section in one call. */
    suspend fun trackPage(serverBase: String, token: String, deviceId: String, sectionKey: String, start: Int, pageSize: Int = 500): Pair<List<PlexTrack>, Int?> {
        val json = get(PlexUrls.join(serverBase, "/library/sections/$sectionKey/all?type=10&X-Plex-Container-Start=$start&X-Plex-Container-Size=$pageSize"), token, deviceId)
        val container = json.optJSONObject("MediaContainer") ?: return emptyList<PlexTrack>() to null
        val metas = container.optJSONArray("Metadata") ?: JSONArray()
        val out = mutableListOf<PlexTrack>()
        for (i in 0 until metas.length()) {
            metas.optJSONObject(i)?.let { out.add(parseTrack(it)) }
        }
        val total = container.optInt("totalSize", -1).takeIf { it >= 0 }
        return out to total
    }

    /**
     * 1.1.0 server-side search: Plex's documented /library/search
     * endpoint (already used in 1.0.x for track-only search), now
     * grouping ALL result types instead of discarding non-tracks.
     * The query goes to the server; only matching artists, albums,
     * and tracks come back, paged with X-Plex-Container-Start/Size,
     * so search never triggers a full-library load. /hubs/search
     * is deliberately NOT used: it returns Hub objects for the
     * Plex UI, not the flat artist/album/track Metadata this
     * player needs. Each SearchResult's Metadata carries its
     * type (artist/album/track); unknown types are skipped.
     */
    suspend fun searchServer(
        serverBase: String, token: String, deviceId: String, query: String,
        start: Int = 0, pageSize: Int = 50,
    ): ServerSearchResults {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val json = get(
            PlexUrls.join(serverBase, "/library/search?query=$encoded&X-Plex-Container-Start=$start&X-Plex-Container-Size=$pageSize"),
            token, deviceId,
        )
        val artists = mutableListOf<PlexArtist>()
        val albums = mutableListOf<PlexAlbum>()
        val tracks = mutableListOf<PlexTrack>()
        val results = json.optJSONObject("MediaContainer")?.optJSONArray("SearchResult") ?: JSONArray()
        for (i in 0 until results.length()) {
            val m = results.optJSONObject(i)?.optJSONObject("Metadata") ?: continue
            when (m.optString("type")) {
                "artist" -> artists.add(PlexArtist(
                    key = m.optString("ratingKey"),
                    title = m.optString("title"),
                    thumb = m.optString("thumb").takeIf { it.isNotBlank() },
                    summary = m.optString("summary").takeIf { it.isNotBlank() },
                    keyPath = m.optString("key").takeIf { it.isNotBlank() },
                ))
                "album" -> albums.add(PlexAlbum(
                    key = m.optString("ratingKey"),
                    title = m.optString("title"),
                    artistTitle = m.optString("parentTitle"),
                    thumb = m.optString("thumb").takeIf { it.isNotBlank() },
                    year = m.optInt("year").takeIf { it > 0 },
                    artistThumb = m.optString("parentThumb").takeIf { it.isNotBlank() },
                ))
                "track" -> tracks.add(parseTrack(m))
            }
        }
        return ServerSearchResults(artists = artists, albums = albums, tracks = tracks)
    }

    /** Back-compat: track-only results from server search. */
    suspend fun search(serverBase: String, token: String, deviceId: String, query: String): List<PlexTrack> =
        searchServer(serverBase, token, deviceId, query).tracks

    /**
     * 1.1.0: full metadata for one track by ratingKey. Listings
     * (recentlyAdded, /all) often omit Media/Part, so this is the
     * on-demand source for a playable partId plus full metadata
     * (year, summary, genres, moods, artist/album thumbs).
     */
    suspend fun trackMetadata(serverBase: String, token: String, deviceId: String, ratingKey: String): PlexTrack? {
        if (ratingKey.isBlank()) return null
        val json = get(PlexUrls.join(serverBase, "/library/metadata/$ratingKey"), token, deviceId)
        val metas = json.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: return null
        if (metas.length() == 0) return null
        return parseTrack(metas.optJSONObject(0) ?: return null)
    }

    /** 1.1.0: a track's playable partId, fetching full metadata
     *  only when the listing left partId at -1. Returns -1 only
     *  when the server genuinely has no playable part. Pure
     *  part-selection helper for tests: a listed part wins. */
    fun partIdFor(track: PlexTrack, fullMetadataPartId: Long = -1): Long =
        if (track.partId >= 0) track.partId else fullMetadataPartId

    suspend fun resolvePartId(serverBase: String, token: String, deviceId: String, track: PlexTrack): Long {
        if (track.partId >= 0) return track.partId
        return trackMetadata(serverBase, token, deviceId, track.key)?.partId ?: -1L
    }

    private suspend fun tracksFromMetadata(serverBase: String, token: String, deviceId: String, path: String): List<PlexTrack> {
        val json = get(PlexUrls.join(serverBase, path), token, deviceId)
        return parseTracks(json)
    }

    private fun parseTracks(json: JSONObject): List<PlexTrack> {
        val out = mutableListOf<PlexTrack>()
        val metas = json.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: JSONArray()
        for (i in 0 until metas.length()) {
            val d = metas.optJSONObject(i) ?: continue
            out.add(parseTrack(d))
        }
        return out
    }

    /** Plex tag arrays: [{tag: "Rock"}] or ["Rock"] or a plain string. */
    private fun parseTags(d: JSONObject, key: String): List<String> {
        val out = mutableListOf<String>()
        val arr = d.optJSONArray(key)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                val tag = when (item) {
                    is JSONObject -> item.optString("tag")
                    is String -> item
                    else -> ""
                }
                if (tag.isNotBlank()) out.add(tag)
            }
        } else {
            val single = d.optString(key)
            if (single.isNotBlank()) out.add(single)
        }
        return out.distinct()
    }

    /** All tracks in a section (type=10), paginated (1.1.0) — the
     *  full library The Feels and voice search run on. Lightweight
     *  listings only; Media/Part is resolved on demand at play time. */
    suspend fun allTracks(serverBase: String, token: String, deviceId: String, sectionKey: String): List<PlexTrack> =
        fetchAllMetadata(serverBase, "/library/sections/$sectionKey/all?type=10", token, deviceId).map { parseTrack(it) }

    private fun parseTrack(d: JSONObject): PlexTrack {
        var partId = -1L
        val media = d.optJSONArray("Media")
        if (media != null && media.length() > 0) {
            val parts = media.optJSONObject(0)?.optJSONArray("Part")
            if (parts != null && parts.length() > 0) {
                partId = parts.optJSONObject(0)?.optLong("id", -1L) ?: -1L
            }
        }
        return PlexTrack(
            key = d.optString("ratingKey"),
            title = d.optString("title"),
            artistTitle = d.optString("grandparentTitle"),
            albumTitle = d.optString("parentTitle"),
            thumb = d.optString("thumb").takeIf { it.isNotBlank() }
                ?: d.optString("parentThumb").takeIf { it.isNotBlank() }
                ?: d.optString("grandparentThumb").takeIf { it.isNotBlank() },
            durationMs = run {
                val media = d.optJSONArray("Media")
                val m0 = if (media != null && media.length() > 0) media.optJSONObject(0) else null
                val parts = m0?.optJSONArray("Part")
                resolveTrackDurationMs(
                    d.optLong("duration", 0),
                    m0?.optLong("duration", 0) ?: 0,
                    if (parts != null && parts.length() > 0) parts.optJSONObject(0)?.optLong("duration", 0) ?: 0 else 0,
                )
            },
            partId = partId,
            index = d.optInt("index", 0),
            albumThumb = d.optString("parentThumb").takeIf { it.isNotBlank() },
            artistThumb = d.optString("grandparentThumb").takeIf { it.isNotBlank() },
            year = d.optInt("year").takeIf { it > 0 }
                ?: d.optInt("parentYear").takeIf { it > 0 },
            summary = d.optString("summary").takeIf { it.isNotBlank() },
            discNumber = d.optInt("parentIndex", 0),
            artistKey = d.optString("grandparentRatingKey").takeIf { it.isNotBlank() },
            albumKey = d.optString("parentRatingKey").takeIf { it.isNotBlank() },
            genres = parseTags(d, "Genre"),
            viewCount = d.optInt("viewCount", 0),
            userRating = d.optDouble("userRating", -1.0).let { if (it < 0) null else it.toFloat() },
            moods = parseTags(d, "Mood"),
            styles = parseTags(d, "Style"),
        )
    }
}

open class PlexException(message: String, val statusCode: Int? = null) : Exception(message)

/**
 * 1.0.8: Plex needs a two-factor verification code before it
 * will issue a token. Distinct from a wrong password so the UI
 * can prompt for the code instead of showing a generic
 * rejection. Carries only the Plex status/body, never the
 * password and never the code itself.
 */
class TwoFactorRequired(message: String) : PlexException(message, 401)
