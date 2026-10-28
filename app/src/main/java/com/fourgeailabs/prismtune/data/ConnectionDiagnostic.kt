package com.fourgeailabs.prismtune.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress

/**
 * 1.0.5 connection diagnostic, for the sign-in screen's "Test
 * connection" action.
 *
 * Why this exists: on 2026-10-25 the phone showed "Could not reach
 * Plex: Unable to resolve host \"plex.tv\"" while Plex Amp, Plex, and
 * the car's Chrome all reached plex.tv fine on the same device, so
 * the failure is PrismTune-specific, not a device DNS outage — and a
 * single error line cannot say where it fails. This diagnostic runs
 * five steps separately, in order, and reports each one in plain
 * words, so the next test shows which step actually fails:
 *   (a) a system DNS lookup for plex.tv (InetAddress, the same
 *       resolver the system and other apps use),
 *   (b) a system DNS lookup for clients.plex.tv,
 *   (c) a plain OkHttp GET to https://plex.tv with a fresh default
 *       client (no PrismTune headers, no PIN body),
 *   (d) a plain OkHttp GET to https://clients.plex.tv the same way,
 *   (e) the real PIN POST through PlexClient (the exact call sign-in
 *       uses, including its plex.tv -> clients.plex.tv fallback and
 *       its DoH fallback). The PIN created by (e) is discarded — it
 *       is never shown as a code, never polled, and expires in 15
 *       minutes — so it cannot be confused with a sign-in code.
 *       Its step detail names which host it was created on, so the
 *       test shows whether the fallback host actually worked.
 *
 * Deliberately NOT done here: no hardcoded IP for plex.tv, no TLS
 * bypass, no invented DNS server. Those would mask the failure
 * instead of diagnosing it.
 */
data class DiagnosticStep(
    val label: String,
    val passed: Boolean,
    val detail: String,
)

/** Plain-words rendering of diagnostic steps, for the sign-in screen. */
fun formatDiagnostic(steps: List<DiagnosticStep>): List<String> =
    steps.map { step ->
        val mark = if (step.passed) "✓" else "✗"
        "$mark ${step.label}: ${step.detail}"
    }

private fun dnsStep(hostname: String): DiagnosticStep {
    return try {
        InetAddress.getByName(hostname)
        DiagnosticStep("DNS lookup $hostname", true, "this device found $hostname")
    } catch (e: Exception) {
        DiagnosticStep("DNS lookup $hostname", false, "this device could not find $hostname")
    }
}

private fun plainGetStep(url: String, label: String): DiagnosticStep {
    return try {
        val plain = OkHttpClient.Builder().build()
        val request = Request.Builder().url(url).get().build()
        plain.newCall(request).execute().use {
            // Any HTTP response means the network reached the host;
            // the status itself is not the diagnostic.
            DiagnosticStep(label, true, "reached $url over the network")
        }
    } catch (e: Exception) {
        DiagnosticStep(label, false, "could not reach $url over the network")
    }
}

suspend fun runConnectionDiagnostic(client: PlexClient, deviceId: String): List<DiagnosticStep> =
    withContext(Dispatchers.IO) {
        val steps = mutableListOf<DiagnosticStep>()

        // (a) System DNS lookup, plex.tv.
        steps.add(dnsStep("plex.tv"))
        // (b) System DNS lookup, clients.plex.tv.
        steps.add(dnsStep("clients.plex.tv"))

        // (c) Plain GET, fresh default client, no PrismTune headers.
        steps.add(plainGetStep("https://plex.tv", "Reach plex.tv"))
        // (d) Same plain GET, fallback host.
        steps.add(plainGetStep("https://clients.plex.tv", "Reach clients.plex.tv"))

        // (e) The real PIN POST, exactly as sign-in performs it,
        // including its host fallback and DoH fallback.
        // The returned PIN is discarded on purpose (see header).
        try {
            val pin = client.createPin(deviceId)
            if (pin.code.isBlank()) {
                steps.add(
                    DiagnosticStep(
                        "Plex sign-in request",
                        false,
                        "Plex answered but gave no link code",
                    ),
                )
            } else {
                val hostLabel = when (pin.host) {
                    PlexUrls.CLIENTS_PLEX_TV -> "via clients.plex.tv"
                    else -> "via plex.tv"
                }
                steps.add(
                    DiagnosticStep(
                        "Plex sign-in request",
                        true,
                        "Plex created a sign-in request $hostLabel",
                    ),
                )
            }
        } catch (e: Exception) {
            // 1.0.6: report the status/body snippet (truncated, no
            // token/code/PIN id) instead of a bare "failed", so a
            // PIN-creation failure on the device can be compared
            // with the sign-in screen's pinCreationErrorMessage.
            // DNS failures keep their plain wording via the message
            // check below; other failures show host/status/body.
            val raw = e.message.orEmpty()
            val detail = when {
                raw.contains("Unable to resolve host", ignoreCase = true) ||
                    raw.contains("No address associated with hostname", ignoreCase = true) ->
                    "Plex sign-in request failed: could not find plex.tv"
                raw.contains("/api/v2/pins") ->
                    "Plex sign-in request failed: ${raw.take(400)}"
                else -> "Plex sign-in request failed: ${raw.take(200)}"
            }
            steps.add(
                DiagnosticStep(
                    "Plex sign-in request",
                    false,
                    detail,
                ),
            )
        }

        steps
    }
