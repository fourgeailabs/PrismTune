package com.fourgeailabs.prismtune.data

/**
 * 1.1.0 in-app diagnostics, copyable, no secrets.
 *
 * An entry records WHAT failed (call name), WHERE (endpoint path
 * only, never a full URL), the HTTP status, Plex's error code, and
 * a truncated body snippet — never a token, password, PIN,
 * verification code, authToken, or credential-bearing query string.
 * Sanitising happens at record time, so the stored entry and the
 * copied text are safe by construction, not by the UI remembering
 * to redact.
 */
data class DiagnosticEntry(
    val timeMs: Long,
    val call: String,
    val endpointPath: String,
    val status: Int?,
    val plexCode: Int?,
    val message: String,
)

/** Strip anything that could carry a credential from diagnostic text. */
fun sanitizeDiagnosticText(raw: String): String {
    var out = raw
    // Drop query strings wholesale: tokens travel in them.
    out = out.replace(Regex("[?&][^\\s]*"), "")
    val patterns = listOf(
        Regex("(?i)(authToken|authentication_token|authenticationToken|token|password|pin|verification[_-]?code)[\"'=: ]+[^\\s,;}\"]+"),
        Regex("(?i)X-Plex-Token[^\\s,;]*"),
    )
    for (p in patterns) out = p.replace(out, "$1=[redacted]")
    return out.take(200)
}

/** Endpoint path only: host and query stripped, so no token leaks via URL. */
fun diagnosticPath(raw: String): String {
    val noQuery = raw.substringBefore("?")
    val path = noQuery.substringAfter("://", noQuery).substringAfter("/", "")
    return if (path.isEmpty()) noQuery else "/$path"
}

/** Build an entry from a thrown error; PlexException carries status/body. */
fun diagnosticEntryFor(call: String, endpoint: String, e: Throwable, timeMs: Long = System.currentTimeMillis()): DiagnosticEntry {
    val plex = e as? PlexException
    val raw = e.message.orEmpty()
    // Plex error code lives in the body JSON ("code":1001); the
    // exception carries status only, so parse the code out here.
    val code = Regex("\"code\"\\s*:\\s*(\\d+)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return DiagnosticEntry(
        timeMs = timeMs,
        call = call,
        endpointPath = diagnosticPath(endpoint),
        status = plex?.statusCode,
        plexCode = code,
        message = sanitizeDiagnosticText(raw),
    )
}

/** Capped ring buffer (last 20). Pure, unit-testable. */
fun appendDiagnostic(entries: List<DiagnosticEntry>, entry: DiagnosticEntry, max: Int = 20): List<DiagnosticEntry> =
    (entries + entry).takeLast(max)

/** Copy text for Settings > Diagnostics > Copy. Already sanitised. */
fun diagnosticsCopyText(entries: List<DiagnosticEntry>): String {
    if (entries.isEmpty()) return "PrismTune diagnostics: no errors recorded."
    return buildString {
        appendLine("PrismTune diagnostics (no tokens or passwords are included):")
        for (e in entries) {
            appendLine("- [${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(e.timeMs))}] ${e.call} ${e.endpointPath} status=${e.status ?: "?"} plexCode=${e.plexCode ?: "?"}: ${e.message}")
        }
    }
}
